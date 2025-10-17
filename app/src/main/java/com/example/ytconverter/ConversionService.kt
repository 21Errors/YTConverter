package com.example.ytconverter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.documentfile.provider.DocumentFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class ConversionService : Service() {

    private val CHANNEL_ID = "PlaylistConversionServiceChannel"
    private val NOTIFICATION_ID = 101

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mainConversionJob: Job? = null
    private val playlistSongs = mutableListOf<String>() // Store file paths for M3U (legacy support)
    private var playlistName: String? = null
    private var playlistFolder: File? = null

    // Storage selection properties
    private var useCustomPath = false
    private var selectedStorageUri: Uri? = null
    private var customPlaylistFolder: DocumentFile? = null

    companion object {
        val conversionQueue = mutableListOf<ConversionItem>()
        private val playlistBatches = mutableMapOf<String, MutableList<String>>() // batchName -> file paths
        private var isProcessing = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val videoOrPlaylistUrl = intent?.getStringExtra("VIDEO_OR_PLAYLIST_URL")
        val action = intent?.getStringExtra("ACTION") ?: "START_NEW_QUEUE"

        // Get storage preferences from intent
        useCustomPath = intent?.getBooleanExtra("USE_CUSTOM_PATH", false) ?: false
        val storageUriString = intent?.getStringExtra("SELECTED_STORAGE_URI")
        selectedStorageUri = storageUriString?.let { Uri.parse(it) }

        if (videoOrPlaylistUrl != null) {
            if (action == "ADD_TO_QUEUE") {
                // Add to existing queue
                addToExistingQueue(videoOrPlaylistUrl)
            } else {
                // Start new queue (existing behavior)
                if (mainConversionJob?.isActive == true) {
                    return START_NOT_STICKY
                }
                startNewQueue(videoOrPlaylistUrl)
            }
        }

        if (intent?.action == "ACTION_CANCEL_ALL") {
            cancelAllConversions()
        }

        return START_NOT_STICKY
    }

    private fun addToExistingQueue(url: String) {
        serviceScope.launch {
            try {
                Log.d("ConversionService", "Adding to existing queue: $url")

                val isPlaylist = url.contains("playlist?list=")

                if (isPlaylist) {
                    val playlistInfo = withContext(Dispatchers.IO) { PlaylistInfo.getInfo(url) }
                    val batchName = playlistInfo.name?.replace(Regex("[^A-Za-z0-9 \\-_]"), "")
                        ?.replace(Regex("\\s+"), "_")?.take(50)
                        ?: "Playlist_${System.currentTimeMillis()}"

                    val videos = playlistInfo.relatedItems
                    videos.forEachIndexed { index, item ->
                        val conversionItem = ConversionItem(
                            id = "${System.currentTimeMillis()}_${conversionQueue.size + index}",
                            title = item.name ?: "Unknown ${index + 1}",
                            url = item.url,
                            status = ConversionStatus.WAITING,
                            batchName = batchName,
                            isPlaylistItem = true
                        )
                        conversionQueue.add(conversionItem)
                    }

                    // Initialize batch tracking
                    playlistBatches[batchName] = mutableListOf()

                    Log.d("ConversionService", "Added ${videos.size} items from playlist: $batchName")
                } else {
                    val streamInfo = withContext(Dispatchers.IO) { StreamInfo.getInfo(url) }
                    val batchName = streamInfo.name?.replace(Regex("[^A-Za-z0-9 \\-_]"), "")
                        ?.replace(Regex("\\s+"), "_")?.take(50)
                        ?: "Video_${System.currentTimeMillis()}"

                    val conversionItem = ConversionItem(
                        id = "${System.currentTimeMillis()}_${conversionQueue.size}",
                        title = streamInfo.name ?: "Unknown Video",
                        url = url,
                        status = ConversionStatus.WAITING,
                        batchName = batchName,
                        isPlaylistItem = false
                    )
                    conversionQueue.add(conversionItem)

                    // Initialize batch tracking
                    playlistBatches[batchName] = mutableListOf()

                    Log.d("ConversionService", "Added single video: $batchName")
                }

                sendUpdate(action = "ITEM_ADDED")

                // Start processing if not already processing
                if (!isProcessing) {
                    processQueue()
                }

            } catch (e: Exception) {
                Log.e("ConversionService", "Error adding to queue", e)
            }
        }
    }

    private fun startNewQueue(url: String) {
        if (mainConversionJob?.isActive == true) {
            return
        }

        val notification = createNotification("Starting download...")
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )

        // Clear queue and start fresh
        conversionQueue.clear()
        playlistBatches.clear()
        addToExistingQueue(url) // Reuse the logic
    }

    private fun processQueue() {
        if (isProcessing) return

        mainConversionJob = serviceScope.launch {
            isProcessing = true

            try {
                while (conversionQueue.any { it.status == ConversionStatus.WAITING }) {
                    val nextItem = conversionQueue.firstOrNull { it.status == ConversionStatus.WAITING }
                    if (nextItem == null) break

                    val index = conversionQueue.indexOf(nextItem)
                    sendUpdate(action = "PROCESSING_NEXT", extra = mapOf("CURRENT_ITEM" to nextItem.title))

                    // Setup folder for this batch if needed
                    setupFolderForBatch(nextItem.batchName, nextItem.isPlaylistItem)

                    processVideo(nextItem.url, nextItem.title, index, nextItem.batchName)

                    if (!isActive) break
                }

                // Create M3U files for each playlist batch
                createAllM3UPlaylists()

                updateNotification("All downloads completed!", isFinished = true)
                sendUpdate(action = "ALL_COMPLETED")
                Log.d("ConversionService", "All conversions completed.")

            } catch (e: Exception) {
                Log.e("ConversionService", "Error processing queue", e)
                updateNotification("Processing failed: ${e.message}", isFinished = true)
                sendUpdate(action = "ALL_COMPLETED")
            } finally {
                isProcessing = false
                delay(2000)
                stopSelf()
            }
        }
    }

    private fun setupFolderForBatch(batchName: String, isPlaylist: Boolean) {
        // Only setup if we haven't already set up this batch
        if (playlistBatches.containsKey(batchName) && playlistBatches[batchName]!!.isNotEmpty()) {
            return // Already setup
        }

        playlistName = batchName
        setupPlaylistFolder() // Your existing method
    }

    private suspend fun processVideo(videoUrl: String, videoTitle: String, index: Int, batchName: String) {
        try {
            Log.d("ConversionService", "Processing video $index: $videoTitle")

            conversionQueue[index].status = ConversionStatus.CONVERTING
            conversionQueue[index].progress = "Fetching stream info..."
            sendUpdate(action = "UPDATE_ITEM", index = index)
            updateNotification("Downloading: $videoTitle (${getQueueProgress()})")

            val streamInfo = withContext(Dispatchers.IO) { StreamInfo.getInfo(videoUrl) }

            // Find best audio stream
            val selectedAudio = streamInfo.audioStreams.firstOrNull {
                it.format?.mimeType?.contains("opus", ignoreCase = true) == true
            } ?: streamInfo.audioStreams.firstOrNull {
                it.format?.mimeType?.contains("aac", ignoreCase = true) == true ||
                        it.format?.mimeType?.contains("mp4a", ignoreCase = true) == true
            } ?: streamInfo.audioStreams.maxByOrNull { it.bitrate }

            val audioFormat = when {
                selectedAudio?.format?.mimeType?.contains("opus", ignoreCase = true) == true -> "opus"
                selectedAudio?.format?.mimeType?.contains("aac", ignoreCase = true) == true -> "aac"
                selectedAudio?.format?.mimeType?.contains("mp4a", ignoreCase = true) == true -> "aac"
                selectedAudio?.format?.mimeType?.contains("m4a", ignoreCase = true) == true -> "aac"
                selectedAudio?.format?.mimeType?.contains("mp3", ignoreCase = true) == true -> "mp3"
                selectedAudio?.format?.mimeType?.contains("mpeg", ignoreCase = true) == true -> "mp3"
                selectedAudio?.format?.mimeType?.contains("webm", ignoreCase = true) == true -> "webm"
                else -> {
                    Log.w("ConversionService", "Unknown MIME type: ${selectedAudio?.format?.mimeType}, defaulting to mp3")
                    "mp3"
                }
            }

            conversionQueue[index].format = audioFormat
            val title = streamInfo.name ?: "Unknown"

            if (selectedAudio != null && selectedAudio.url != null) {
                conversionQueue[index].progress = "Downloading audio..."
                sendUpdate(action = "UPDATE_ITEM", index = index)

                val downloadedFile = downloadAudioStream(selectedAudio.url!!, title, audioFormat, index)
                if (downloadedFile != null) {
                    conversionQueue[index].progress = "Saving file..."
                    sendUpdate(action = "UPDATE_ITEM", index = index)

                    val savedFilePath = saveFile(downloadedFile, title, audioFormat, index + 1)
                    if (savedFilePath != null) {
                        // Add to the appropriate batch
                        playlistBatches[batchName]?.add(savedFilePath)
                        // Also add to legacy playlistSongs for backward compatibility
                        playlistSongs.add(savedFilePath)

                        conversionQueue[index].status = ConversionStatus.COMPLETED
                        conversionQueue[index].progress = "✅ Saved successfully!"
                        Log.d("ConversionService", "Successfully saved: $savedFilePath")
                    } else {
                        conversionQueue[index].status = ConversionStatus.FAILED
                        conversionQueue[index].progress = "Failed to save file"
                        Log.e("ConversionService", "Failed to save file for: $title")
                    }
                } else {
                    conversionQueue[index].status = ConversionStatus.FAILED
                    conversionQueue[index].progress = "Download failed"
                    Log.e("ConversionService", "Failed to download: $title")
                }
            } else {
                conversionQueue[index].status = ConversionStatus.FAILED
                conversionQueue[index].progress = "No audio stream found"
                Log.e("ConversionService", "No audio stream found for: $title")
            }
        } catch (e: Exception) {
            Log.e("ConversionService", "Failed to process video $videoTitle", e)
            conversionQueue[index].status = ConversionStatus.FAILED
            conversionQueue[index].progress = "Error: ${e.message}"
        }
        sendUpdate(action = "UPDATE_ITEM", index = index)
    }

    private fun getQueueProgress(): String {
        val total = conversionQueue.size
        val completed = conversionQueue.count { it.status == ConversionStatus.COMPLETED || it.status == ConversionStatus.FAILED }
        return "${completed + 1}/$total"
    }

    private fun createAllM3UPlaylists() {
        playlistBatches.forEach { (batchName, filePaths) ->
            if (filePaths.isNotEmpty()) {
                createM3UPlaylistForBatch(batchName, filePaths)
            }
        }
    }

    private fun createM3UPlaylistForBatch(batchName: String, filePaths: List<String>) {
        if (batchName.isEmpty() || filePaths.isEmpty()) {
            Log.w("ConversionService", "Cannot create M3U for batch: batchName=$batchName, files=${filePaths.size}")
            return
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val playlistFileName = "${batchName}_$timeStamp.m3u"

        // Build M3U content
        val m3uContent = StringBuilder("#EXTM3U\n")
        filePaths.forEach { filePath ->
            val file = File(filePath)
            m3uContent.append("#EXTINF:-1,${file.nameWithoutExtension}\n")
            m3uContent.append("${file.name}\n")
        }

        try {
            if (useCustomPath && customPlaylistFolder != null) {
                // Save M3U to custom location
                val m3uFile = customPlaylistFolder!!.createFile("audio/x-mpegurl", playlistFileName)
                if (m3uFile != null) {
                    contentResolver.openOutputStream(m3uFile.uri).use { out ->
                        out?.write(m3uContent.toString().toByteArray())
                    }
                    Log.d("ConversionService", "M3U playlist saved to custom location: ${m3uFile.name}")
                } else {
                    Log.e("ConversionService", "Failed to create M3U file in custom location")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Save M3U via MediaStore
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, playlistFileName)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "audio/x-mpegurl")
                    put(
                        MediaStore.Files.FileColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MUSIC}/$batchName"
                    )
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }

                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)

                if (uri != null) {
                    resolver.openOutputStream(uri).use { out ->
                        out?.write(m3uContent.toString().toByteArray())
                    }

                    // Mark as not pending
                    values.clear()
                    values.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)

                    Log.d("ConversionService", "M3U playlist saved via MediaStore: $playlistFileName")
                } else {
                    Log.e("ConversionService", "Failed to create MediaStore entry for M3U playlist")
                }
            } else {
                // Pre-Android 10 fallback: save directly to public Music folder
                val playlistDir = File("${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)}/$batchName")
                playlistDir.mkdirs()
                val m3uFile = File(playlistDir, playlistFileName)
                m3uFile.writeText(m3uContent.toString())

                // Scan so media players detect it
                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(m3uFile.absolutePath),
                    arrayOf("audio/x-mpegurl"),
                    null
                )

                Log.d("ConversionService", "M3U playlist saved in Music folder: ${m3uFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("ConversionService", "Failed to create M3U playlist for batch: $batchName", e)
        }
    }

    private fun setupPlaylistFolder() {
        try {
            if (useCustomPath && selectedStorageUri != null) {
                // Use custom storage location
                val customRoot = DocumentFile.fromTreeUri(this, selectedStorageUri!!)
                if (customRoot != null) {
                    // Create or find the playlist folder in custom location
                    customPlaylistFolder = customRoot.findFile(playlistName!!)
                        ?: customRoot.createDirectory(playlistName!!)
                    Log.d(
                        "ConversionService",
                        "Custom playlist folder: ${customPlaylistFolder?.name}"
                    )
                } else {
                    Log.e("ConversionService", "Failed to access custom storage URI")
                    // Fallback to default
                    useCustomPath = false
                    setupDefaultPlaylistFolder()
                }
            } else {
                setupDefaultPlaylistFolder()
            }
        } catch (e: Exception) {
            Log.e("ConversionService", "Error setting up playlist folder", e)
            useCustomPath = false
            setupDefaultPlaylistFolder()
        }
    }

    private fun setupDefaultPlaylistFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10+, we'll rely on MediaStore to create the folder structure
            playlistFolder =
                File("${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)}/$playlistName")
            Log.d(
                "ConversionService",
                "Default playlist folder path set to: ${playlistFolder?.absolutePath}"
            )
        } else {
            // For older Android versions, create the folder directly
            playlistFolder =
                File("${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)}/$playlistName")
            val folderCreated = playlistFolder?.mkdirs() ?: false
            Log.d(
                "ConversionService",
                "Default playlist folder creation result: $folderCreated for path: ${playlistFolder?.absolutePath}"
            )
        }
    }

    private fun cancelAllConversions() {
        mainConversionJob?.cancel()
        conversionQueue.forEach {
            if (it.status == ConversionStatus.WAITING || it.status == ConversionStatus.CONVERTING) {
                it.status = ConversionStatus.CANCELLED
                it.progress = "Cancelled by user"
            }
        }
        playlistSongs.clear()
        playlistBatches.clear()
        sendUpdate(action = "UPDATE_ALL")
        updateNotification("All downloads cancelled.", isFinished = true)
        isProcessing = false
        stopSelf()
    }

    private fun createM3UPlaylist() {
        if (playlistName.isNullOrEmpty() || playlistSongs.isEmpty()) {
            Log.w(
                "ConversionService",
                "Cannot create M3U: playlistName=$playlistName, songs=${playlistSongs.size}"
            )
            return
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val playlistFileName = "${playlistName}_$timeStamp.m3u"

        // Build M3U content
        val m3uContent = StringBuilder("#EXTM3U\n")
        playlistSongs.forEach { filePath ->
            val file = File(filePath)
            m3uContent.append("#EXTINF:-1,${file.nameWithoutExtension}\n")
            m3uContent.append("${file.name}\n")
        }

        try {
            if (useCustomPath && customPlaylistFolder != null) {
                // Save M3U to custom location
                val m3uFile = customPlaylistFolder!!.createFile("audio/x-mpegurl", playlistFileName)
                if (m3uFile != null) {
                    contentResolver.openOutputStream(m3uFile.uri).use { out ->
                        out?.write(m3uContent.toString().toByteArray())
                    }
                    Log.d(
                        "ConversionService",
                        "M3U playlist saved to custom location: ${m3uFile.name}"
                    )
                } else {
                    Log.e("ConversionService", "Failed to create M3U file in custom location")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Save M3U via MediaStore
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, playlistFileName)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "audio/x-mpegurl")
                    put(
                        MediaStore.Files.FileColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MUSIC}/$playlistName"
                    )
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }

                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)

                if (uri != null) {
                    resolver.openOutputStream(uri).use { out ->
                        out?.write(m3uContent.toString().toByteArray())
                    }

                    // Mark as not pending
                    values.clear()
                    values.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)

                    Log.d(
                        "ConversionService",
                        "M3U playlist saved via MediaStore: $playlistFileName"
                    )
                } else {
                    Log.e("ConversionService", "Failed to create MediaStore entry for M3U playlist")
                }
            } else {
                // Pre-Android 10 fallback: save directly to public Music folder
                val playlistDir = playlistFolder ?: Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MUSIC
                )
                playlistDir.mkdirs()
                val m3uFile = File(playlistDir, playlistFileName)
                m3uFile.writeText(m3uContent.toString())

                // Scan so media players detect it
                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(m3uFile.absolutePath),
                    arrayOf("audio/x-mpegurl"),
                    null
                )

                Log.d(
                    "ConversionService",
                    "M3U playlist saved in Music folder: ${m3uFile.absolutePath}"
                )
            }
        } catch (e: Exception) {
            Log.e("ConversionService", "Failed to create M3U playlist", e)
        }
    }

    private fun downloadAudioStream(url: String, title: String, format: String, index: Int): File? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val cleanTitle = title.replace(Regex("[^A-Za-z0-9 \\-_]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(50)
        val fileName =
            if (cleanTitle.isNotEmpty()) "${cleanTitle}_$timeStamp" else "Audio_$timeStamp"

        val extension = when (format.lowercase()) {
            "opus" -> ".opus"
            "aac" -> ".m4a"
            "mp3" -> ".mp3"
            else -> ".mp3" // fallback
        }

        val tempFile = File(cacheDir, "$fileName$extension")

        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val totalSize = connection.contentLength
                var downloadedSize = 0

                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedSize += bytesRead

                            // Update progress
                            if (totalSize > 0) {
                                val progress = (downloadedSize * 100) / totalSize
                                conversionQueue[index].progress = "Downloading... ${progress}%"
                                sendUpdate(action = "UPDATE_ITEM", index = index)
                            }
                        }
                    }
                }
                connection.disconnect()
                Log.d(
                    "ConversionService",
                    "Downloaded: $fileName$extension (${downloadedSize} bytes)"
                )
                tempFile
            } else {
                Log.e("ConversionService", "HTTP Error: ${connection.responseCode}")
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.e("ConversionService", "Failed to download audio stream for: $title", e)
            null
        }
    }

    private fun saveFile(tempFile: File, title: String, format: String, trackNumber: Int): String? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val cleanTitle = title.replace(Regex("[^A-Za-z0-9 \\-_]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(50)
        val fileName =
            if (cleanTitle.isNotEmpty()) "${cleanTitle}_$timeStamp" else "Audio_$timeStamp"

        val (extension, mimeType) = when (format.lowercase().trim()) {
            "opus", "webm" -> ".opus" to "audio/ogg"
            "aac", "mp4a", "m4a" -> ".m4a" to "audio/mp4"
            "mp3", "mpeg" -> ".mp3" to "audio/mpeg"
            else -> ".mp3" to "audio/mpeg"
        }

        val validatedMimeType = if (mimeType.contains("*") || mimeType.isBlank()) {
            Log.w("ConversionService", "Invalid MIME type detected: $mimeType, using audio/mpeg")
            "audio/mpeg"
        } else {
            mimeType
        }

        try {
            if (useCustomPath && customPlaylistFolder != null) {
                // Save to custom location using DocumentFile
                val audioFile =
                    customPlaylistFolder!!.createFile(validatedMimeType, "$fileName$extension")
                if (audioFile != null) {
                    contentResolver.openOutputStream(audioFile.uri).use { out ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(out!!)
                        }
                    }
                    Log.d("ConversionService", "File saved to custom location: ${audioFile.name}")
                    // Return a relative path for M3U playlist compatibility
                    return "$fileName$extension"
                } else {
                    Log.e("ConversionService", "Failed to create file in custom location")
                    return null
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Save via MediaStore for default location
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "$fileName$extension")
                    put(MediaStore.Audio.Media.MIME_TYPE, validatedMimeType)
                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MUSIC}/$playlistName"
                    )
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                    // Add album metadata to group songs together
                    put(MediaStore.Audio.Media.ALBUM, playlistName)
                    put(MediaStore.Audio.Media.ALBUM_ARTIST, "YouTube Playlist")
                    put(MediaStore.Audio.Media.ARTIST, "YouTube")
                    put(MediaStore.Audio.Media.TRACK, trackNumber)
                }

                Log.d(
                    "ConversionService",
                    "Inserting file: $fileName$extension with MIME: $validatedMimeType in album: $playlistName"
                )

                val resolver = contentResolver
                val outputUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                outputUri?.let { uri ->
                    resolver.openOutputStream(uri).use { out ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(out!!)
                        }
                    }
                    // Mark as not pending
                    val finalValues =
                        ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                    resolver.update(uri, finalValues, null, null)

                    // Get the actual file path from MediaStore
                    val actualPath = getActualPathFromUri(uri)
                    Log.d("ConversionService", "File saved to actual path: $actualPath")

                    return actualPath ?: "${playlistFolder?.absolutePath}/$fileName$extension"
                }
            } else {
                // Ensure playlist folder exists for older Android versions
                playlistFolder?.mkdirs()
                val outputFile = File(playlistFolder, "$fileName$extension")
                tempFile.copyTo(outputFile, overwrite = true)
                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(outputFile.absolutePath),
                    arrayOf(validatedMimeType),
                    null
                )
                Log.d("ConversionService", "File saved to: ${outputFile.absolutePath}")
                return outputFile.absolutePath
            }
        } catch (e: Exception) {
            Log.e(
                "ConversionService",
                "Exception during file saving. Format: $format, MIME: $validatedMimeType",
                e
            )
            return null
        } finally {
            tempFile.delete()
        }
        return null
    }

    private fun getActualPathFromUri(uri: android.net.Uri): String? {
        return try {
            val cursor =
                contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val columnIndex = it.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (columnIndex != -1) {
                        return it.getString(columnIndex)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e("ConversionService", "Failed to get actual path from URI", e)
            null
        }
    }

    private fun sendUpdate(action: String, index: Int = -1, extra: Map<String, String> = emptyMap()) {
        val intent = Intent("com.example.ytconverter.CONVERSION_UPDATE")
        intent.putExtra("ACTION", action)
        if (index != -1) {
            intent.putExtra("INDEX", index)
        }
        extra.forEach { (key, value) ->
            intent.putExtra(key, value)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d("ConversionService", "Sent broadcast: $action${if (index != -1) " for index $index" else ""}")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Playlist Download Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(
        message: String,
        progress: Int? = null,
        max: Int? = null,
        isFinished: Boolean = false
    ): NotificationCompat.Builder {
        val notificationIntent = Intent(this, PlaylistConverterActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading Playlist")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(!isFinished)

        if (progress != null && max != null) {
            builder.setProgress(max, progress, false)
        }

        return builder
    }

    private fun updateNotification(
        message: String,
        progress: Int? = null,
        max: Int? = null,
        isFinished: Boolean = false
    ) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = createNotification(message, progress, max, isFinished).build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        isProcessing = false
        Log.d("ConversionService", "Service destroyed. Scope cancelled.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}