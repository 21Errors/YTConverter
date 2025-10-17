package com.example.ytconverter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class PlaylistConverterActivity : AppCompatActivity() {

    private lateinit var playlistUrlInput: EditText
    private lateinit var convertPlaylistButton: Button
    private lateinit var cancelAllButton: Button
    private lateinit var statusText: TextView
    private lateinit var queueRecyclerView: RecyclerView
    private lateinit var queueAdapter: ConversionQueueAdapter
    private lateinit var infoButton: ImageButton
    private lateinit var backToMainButton: Button
    private lateinit var selectPathButton: Button
    private lateinit var selectedPathText: TextView

    private val STORAGE_PERMISSION_CODE = 2001
    private var selectedStorageUri: Uri? = null
    private var useCustomPath = false

    // The shared queue, now accessed from the ConversionService's companion object
    private val conversionQueue = ConversionService.conversionQueue

    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            selectedStorageUri = uri
            useCustomPath = true
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val docFile = DocumentFile.fromTreeUri(this, uri)
            val displayPath = getDisplayPath(uri)
            selectedPathText.text = displayPath ?: docFile?.name ?: "Custom folder"
            Toast.makeText(this, "Storage location updated", Toast.LENGTH_SHORT).show()
        }
    }

    // Broadcast receiver to get updates from the service
    private val conversionUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val action = it.getStringExtra("ACTION")
                when (action) {
                    "QUEUE_UPDATED" -> {
                        runOnUiThread {
                            queueAdapter.notifyDataChanged()
                            statusText.text = "Status: Queue updated."
                            updateButtonStates()
                        }
                    }
                    "ITEM_ADDED" -> {
                        runOnUiThread {
                            queueAdapter.notifyDataChanged()
                            statusText.text = "Status: Items added to queue."
                            updateButtonStates()
                        }
                    }
                    "UPDATE_ITEM" -> {
                        val index = it.getIntExtra("INDEX", -1)
                        if (index != -1 && index < conversionQueue.size) {
                            runOnUiThread {
                                queueAdapter.updateItem(index)
                                updateButtonStates()
                            }
                        }
                    }
                    "PROCESSING_NEXT" -> {
                        val currentItem = it.getStringExtra("CURRENT_ITEM") ?: ""
                        runOnUiThread {
                            statusText.text = "Status: Processing - $currentItem"
                            queueAdapter.notifyDataChanged()
                            updateButtonStates()
                        }
                    }
                    "ALL_COMPLETED" -> {
                        runOnUiThread {
                            statusText.text = "Status: All downloads completed!"
                            updateButtonStates()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_playlist_converter)

        initViews()
        setupRecyclerView()
        setupClickListeners()

        statusText.text = "Status: Ready. Enter a playlist URL."
        queueAdapter.notifyDataChanged()
        updateButtonStates()
    }

    private fun initViews() {
        playlistUrlInput = findViewById(R.id.playlistUrlInput)
        convertPlaylistButton = findViewById(R.id.convertPlaylistButton)
        cancelAllButton = findViewById(R.id.cancelAllButton)

        statusText = findViewById(R.id.statusTextPlaylist)
        queueRecyclerView = findViewById(R.id.queueRecyclerView)
        infoButton = findViewById(R.id.infoButton)

    }

    private fun setupRecyclerView() {
        queueAdapter = ConversionQueueAdapter(conversionQueue) { item -> }
        queueRecyclerView.layoutManager = LinearLayoutManager(this)
        queueRecyclerView.adapter = queueAdapter
    }

    private fun setupClickListeners() {
        convertPlaylistButton.setOnClickListener {
            if (checkStoragePermissions()) {
                startConversionService()
            } else {
                requestStoragePermissions()
            }
        }

        cancelAllButton.setOnClickListener {
            val intent = Intent(this, ConversionService::class.java).apply {
                action = "ACTION_CANCEL_ALL"
            }
            startService(intent)
            Toast.makeText(this, "Cancellation requested.", Toast.LENGTH_SHORT).show()
        }

        infoButton.setOnClickListener {
            showInfoDialog()
        }

        selectPathButton.setOnClickListener {
            openDirectoryPicker()
        }


    }

    private fun openDirectoryPicker() {
        try {
            directoryPickerLauncher.launch(null)
        } catch (e: Exception) {
            Toast.makeText(this, "Directory picker not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getDisplayPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            when {
                docId.startsWith("primary:") -> {
                    val path = docId.substringAfter("primary:")
                    if (path.isEmpty()) "Internal Storage" else "Internal Storage/$path"
                }
                docId.contains(":") -> {
                    val parts = docId.split(":")
                    if (parts.size >= 2) "${parts[0]}/${parts[1]}" else docId
                }
                else -> docId
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun showInfoDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("How to Download Videos/Playlists")
        builder.setMessage("""
        Steps to download YouTube content:
        
        FOR SINGLE VIDEOS:
        1. Go to YouTube and find your video
        2. Copy the video URL from your browser
           • Should look like: youtube.com/watch?v=... or youtu.be/...
        3. Paste the URL above and click "Convert Video/Playlist"
        
        FOR PLAYLISTS:
        1. Go to YouTube and find your playlist
           • Or click on the 3 dots -> share -> copy link
        2. Make sure the playlist is set to PUBLIC
           • Click on your playlist -> edit icon -> set visibility to "Public"
        3. Copy the playlist URL from your browser
           • Should look like: youtube.com/playlist?list=...
        4. Paste the URL above and click "Convert Video/Playlist"
        
        STORAGE LOCATION:
        • Use "Browse" to pick a custom folder
        • Or leave as default Music folder
        
        IMPORTANT NOTES:
        • Private/Unlisted playlists won't work
        • Single videos work regardless of privacy
        • Some videos might fail if region-locked or removed
        • Files are saved in Opus, AAC, or MP3 format
        • For playlists, an M3U file is created for your music player
        
        Pro Tip: Test with a single video first!
    """.trimIndent())

        builder.setPositiveButton("Got it!") { dialog, _ ->
            dialog.dismiss()
        }

        builder.setIcon(android.R.drawable.ic_dialog_info)
        val dialog = builder.create()
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.example.ytconverter.CONVERSION_UPDATE")
        LocalBroadcastManager.getInstance(this).registerReceiver(conversionUpdateReceiver, filter)
        queueAdapter.notifyDataChanged()
        updateButtonStates()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(conversionUpdateReceiver)
    }

    private fun startConversionService() {
        val url = playlistUrlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter a YouTube URL", Toast.LENGTH_SHORT).show()
            return
        }

        // Validate URL format
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) {
            Toast.makeText(this, "Please enter a valid YouTube URL", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, ConversionService::class.java).apply {
            putExtra("VIDEO_OR_PLAYLIST_URL", url)
            putExtra("USE_CUSTOM_PATH", useCustomPath)
            putExtra("ACTION", if (isServiceProcessing()) "ADD_TO_QUEUE" else "START_NEW_QUEUE")
            selectedStorageUri?.let { uri ->
                putExtra("SELECTED_STORAGE_URI", uri.toString())
            }
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        if (isServiceProcessing()) {
            statusText.text = "Status: Adding to queue..."
            Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show()
        } else {
            statusText.text = "Status: Starting download..."
        }

        // Clear the input after adding to queue
        playlistUrlInput.text.clear()
        updateButtonStates()
    }

    private fun isServiceProcessing(): Boolean {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (ConversionService::class.java.name == service.service.className) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            // Fallback: check if there are items in conversion queue with active status
            conversionQueue.any {
                it.status == ConversionStatus.CONVERTING || it.status == ConversionStatus.WAITING
            }
        }
    }

    private fun updateButtonStates() {
        val isServiceRunning = isServiceProcessing()
        val hasWaitingOrProcessingItems = conversionQueue.any {
            it.status == ConversionStatus.WAITING || it.status == ConversionStatus.CONVERTING
        }

        convertPlaylistButton.isEnabled = true // Always allow adding to queue
        convertPlaylistButton.text = if (isServiceRunning) "Add to Queue" else "Convert Video/Playlist"

        cancelAllButton.isEnabled = hasWaitingOrProcessingItems
    }

    private fun isServiceRunning(serviceClassName: String): Boolean {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClassName == service.service.className) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            // Fallback: check if there are items in conversion queue with active status
            conversionQueue.any {
                it.status == ConversionStatus.CONVERTING || it.status == ConversionStatus.WAITING
            }
        }
    }

    private fun checkStoragePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
        ActivityCompat.requestPermissions(this, permissions, STORAGE_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startConversionService()
            } else {
                Toast.makeText(this, "Storage permissions denied", Toast.LENGTH_SHORT).show()
                statusText.text = "Status: Permissions denied"
            }
        }
    }
}