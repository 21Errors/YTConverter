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
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFmpeg
import kotlinx.coroutines.*
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ConversionService : Service() {

    private val CHANNEL_ID = "PlaylistConversionServiceChannel"
    private val NOTIFICATION_ID = 101

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mainConversionJob: Job? = null
    private val playlistSongs = mutableListOf<String>() // Store file paths for M3U
    private var playlistName: String? = null
    private var playlistFolder: String? = null

    companion object {
        val conversionQueue = mutableListOf<ConversionItem>()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (YTConverterActivity.DownloaderImpl.getInstance() == null) {
            YTConverterActivity.DownloaderImpl.init()
        }
        NewPipe.init(YTConverterActivity.DownloaderImpl.getInstance())
        Config.enableLogCallback { message ->
            Log.d("FFmpeg_Service", message.text)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val playlistUrl = intent?.getStringExtra("PLAYLIST_URL")

        if (playlistUrl != null) {
            if (mainConversionJob?.isActive == true) {
                return START_NOT_STICKY
            }

            val notification = createNotification("Starting conversion...")
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification.build(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )

            startConversion(playlistUrl)
        }

        if (intent?.action == "ACTION_CANCEL_ALL") {
            cancelAllConversions()
        }

        return START_NOT_STICKY
    }

    private fun startConversion(url: String) {
        mainConversionJob = serviceScope.launch {
            try {
                // Fetch playlist info
                val playlistInfo = withContext(Dispatchers.IO) { PlaylistInfo.getInfo(url) }
                playlistName = playlistInfo.name?.replace(Regex("[^A-Za-z0-9 \\-_]"), "")
                    ?.replace(Regex("\\s+"), "_")?.take(50) ?: "Playlist_${System.currentTimeMillis()}"
                playlistFolder = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)}/$playlistName"
                File(playlistFolder).mkdirs() // Create playlist directory
                playlistSongs.clear()

                val videos = playlistInfo.relatedItems

                // Clear and populate the queue
                conversionQueue.clear()
                videos.forEachIndexed { index, item ->
                    val conversionItem = ConversionItem(
                        id = "${System.currentTimeMillis()}_$index",
                        title = item.name ?: "Unknown ${index + 1}",
                        url = item.url,
                        status = ConversionStatus.WAITING
                    )
                    conversionQueue.add(conversionItem)
                }
                sendUpdate(action = "QUEUE_READY")

                // Process each video
                for ((index, item) in videos.withIndex()) {
                    if (!isActive) {
                        break
                    }

                    try {
                        conversionQueue[index].status = ConversionStatus.CONVERTING
                        conversionQueue[index].progress = "Fetching stream info..."
                        sendUpdate(action = "UPDATE_ITEM", index = index)

                        val streamInfo = withContext(Dispatchers.IO) { StreamInfo.getInfo(item.url) }
                        val bestAudio = streamInfo.audioStreams.maxByOrNull { it.bitrate }
                        val title = streamInfo.name ?: "Unknown"

                        if (bestAudio != null && bestAudio.url != null) {
                            conversionQueue[index].progress = "Converting to MP3..."
                            sendUpdate(action = "UPDATE_ITEM", index = index)

                            val tempFile = convertToMp3(bestAudio.url!!, title)
                            if (tempFile != null) {
                                val savedFilePath = saveFile(tempFile, title)
                                if (savedFilePath != null) {
                                    playlistSongs.add(savedFilePath)
                                    conversionQueue[index].status = ConversionStatus.COMPLETED
                                    conversionQueue[index].progress = "✅ Saved successfully!"
                                } else {
                                    conversionQueue[index].status = ConversionStatus.FAILED
                                    conversionQueue[index].progress = "Failed to save file"
                                }
                            } else {
                                conversionQueue[index].status = ConversionStatus.FAILED
                                conversionQueue[index].progress = "Conversion failed"
                            }
                        } else {
                            conversionQueue[index].status = ConversionStatus.FAILED
                            conversionQueue[index].progress = "No audio stream found"
                        }
                    } catch (e: Exception) {
                        Log.e("ConversionService", "Failed to process video ${item.name}", e)
                        conversionQueue[index].status = ConversionStatus.FAILED
                        conversionQueue[index].progress = "Error: ${e.message}"
                    }
                    sendUpdate(action = "UPDATE_ITEM", index = index)
                }

                // Create M3U playlist file
                createM3UPlaylist()

                updateNotification("Conversion and playlist creation finished!", isFinished = true)
                sendUpdate(action = "CONVERSION_FINISHED")
            } catch (e: Exception) {
                Log.e("ConversionService", "Error extracting playlist", e)
                updateNotification("Playlist extraction failed: ${e.message}", isFinished = true)
                sendUpdate(action = "CONVERSION_FINISHED")
            } finally {
                stopSelf()
            }
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
        sendUpdate(action = "UPDATE_ALL")
        updateNotification("All conversions cancelled.", isFinished = true)
        stopSelf()
    }

    private fun createM3UPlaylist() {
        val m3uFile = File("$playlistFolder/$playlistName.m3u")
        val m3uContent = StringBuilder("#EXTM3U\n")
        playlistSongs.forEach { filePath ->
            val file = File(filePath)
            m3uContent.append("#EXTINF:-1,${file.nameWithoutExtension}\n")
            m3uContent.append("${file.name}\n")
        }
        try {
            m3uFile.writeText(m3uContent.toString())
            // Scan the M3U file to make it visible to media players
            MediaScannerConnection.scanFile(this, arrayOf(m3uFile.absolutePath), arrayOf("audio/x-mpegurl"), null)
        } catch (e: Exception) {
            Log.e("ConversionService", "Failed to create M3U playlist", e)
        }
    }

    private fun convertToMp3(url: String, title: String): File? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val cleanTitle = title.replace(Regex("[^A-Za-z0-9 \\-_]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(50)
        val fileName = if (cleanTitle.isNotEmpty()) "${cleanTitle}_$timeStamp" else "Audio_$timeStamp"
        val tempFile = File(cacheDir, "$fileName.mp3")

        val command = arrayOf(
            "-i", url,
            "-vn",
            "-acodec", "libmp3lame",
            "-ab", "192k",
            "-ar", "44100",
            "-y",
            tempFile.absolutePath
        )

        val rc = FFmpeg.execute(command)
        return if (rc == 0) tempFile else null
    }

    private fun saveFile(tempFile: File, title: String): String? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val cleanTitle = title.replace(Regex("[^A-Za-z0-9 \\-_]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(50)
        val fileName = if (cleanTitle.isNotEmpty()) "${cleanTitle}_$timeStamp" else "Audio_$timeStamp"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "$fileName.mp3")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$playlistName")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val outputUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                outputUri?.let { uri ->
                    resolver.openOutputStream(uri).use { out ->
                        tempFile.inputStream().use { it.copyTo(out!!) }
                    }
                    val finalValues = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                    resolver.update(uri, finalValues, null, null)
                    return "$playlistFolder/$fileName.mp3"
                }
            } else {
                val outputFile = File(playlistFolder, "$fileName.mp3")
                tempFile.copyTo(outputFile, overwrite = true)
                MediaScannerConnection.scanFile(this, arrayOf(outputFile.absolutePath), arrayOf("audio/mpeg"), null)
                return outputFile.absolutePath
            }
        } catch (e: Exception) {
            Log.e("ConversionService", "Exception during file saving", e)
            return null
        } finally {
            tempFile.delete()
        }
        return null
    }

    private fun sendUpdate(action: String, index: Int = -1) {
        val intent = Intent("com.example.musicplayer2.CONVERSION_UPDATE")
        intent.putExtra("ACTION", action)
        if (index != -1) {
            intent.putExtra("INDEX", index)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Playlist Conversion Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(message: String, progress: Int? = null, max: Int? = null, isFinished: Boolean = false): NotificationCompat.Builder {
        val notificationIntent = Intent(this, PlaylistConverterActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Converting Playlist")
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

    private fun updateNotification(message: String, progress: Int? = null, max: Int? = null, isFinished: Boolean = false) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = createNotification(message, progress, max, isFinished).build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d("ConversionService", "Service destroyed. Scope cancelled.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}