package com.example.ytconverter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class PlaylistConverterActivity : AppCompatActivity() {

    private lateinit var playlistUrlInput: EditText
    private lateinit var convertPlaylistButton: Button
    private lateinit var cancelAllButton: Button
    private lateinit var statusText: TextView
    private lateinit var queueRecyclerView: RecyclerView
    private lateinit var queueAdapter: ConversionQueueAdapter
    private lateinit var infoButton: ImageButton
    private lateinit var backToMainButton: Button

    private val STORAGE_PERMISSION_CODE = 2001

    // The shared queue, now accessed from the ConversionService's companion object
    private val conversionQueue = ConversionService.conversionQueue

    // Broadcast receiver to get updates from the service
    private val conversionUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val action = it.getStringExtra("ACTION")
                when (action) {
                    "QUEUE_READY" -> {
                        runOnUiThread {
                            queueAdapter.notifyDataChanged()
                            statusText.text = "Status: Conversion queue loaded."
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
                    "UPDATE_ALL" -> {
                        runOnUiThread {
                            queueAdapter.notifyDataChanged()
                            statusText.text = "Status: Queue updated."
                            updateButtonStates()
                        }
                    }
                    "CONVERSION_FINISHED" -> {
                        runOnUiThread {
                            statusText.text =
                                "Status: Conversion and playlist creation finished! ✅"
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

        playlistUrlInput = findViewById(R.id.playlistUrlInput)
        convertPlaylistButton = findViewById(R.id.convertPlaylistButton)
        cancelAllButton = findViewById(R.id.cancelAllButton)
        backToMainButton = findViewById(R.id.backToMainButton)
        statusText = findViewById(R.id.statusTextPlaylist)
        queueRecyclerView = findViewById(R.id.queueRecyclerView)
        infoButton = findViewById(R.id.infoButton)

        queueAdapter = ConversionQueueAdapter(conversionQueue) { item -> }
        queueRecyclerView.layoutManager = LinearLayoutManager(this)
        queueRecyclerView.adapter = queueAdapter

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

        // ✅ Back button logic
        backToMainButton.setOnClickListener {
            val intent = Intent(this, YTConverterActivity::class.java)
            startActivity(intent)
            finish()
        }

        statusText.text = "Status: Ready. Enter a playlist URL."
        queueAdapter.notifyDataChanged()
        updateButtonStates()
    }

    private fun showInfoDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("How to Convert Playlists")
        builder.setMessage("""
            📋 Steps to convert a YouTube playlist:
            
            1️⃣ Go to YouTube and find your playlist
                - Or if you want to convert a playlist generated by youtube click on the 3 dots -> share -> copy link and paste here
            
            2️⃣ Make sure the playlist is set to PUBLIC
               • Click on your playlist
               • Click the pencil/edit icon
               • Set visibility to "Public"
            
            3️⃣ Copy the playlist URL from your browser
               • Should look like: youtube.com/playlist?list=...
            
            4️⃣ Paste the URL above and click "Convert Playlist"
            
            5️⃣ Wait for all songs to convert (this may take a while for large playlists)
            
            ⚠️ Important Notes:
            • Private/Unlisted playlists won't work
            • Some videos might fail if they're region-locked or removed
            • Converted files are saved to your Music folder
            • You can cancel individual conversions from the queue
            
            💡 Pro Tip: Start with smaller playlists to test the feature!
            
            🔄 The app will automatically create a playlist in your music player with the same name as the YouTube playlist once conversion is complete.
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
        val filter = IntentFilter("com.example.musicplayer2.CONVERSION_UPDATE")
        LocalBroadcastManager.getInstance(this).registerReceiver(conversionUpdateReceiver, filter)
        // Refresh UI state when returning to the app
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
            Toast.makeText(this, "Please enter a playlist URL", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, ConversionService::class.java).apply {
            putExtra("PLAYLIST_URL", url)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        statusText.text = "Status: Starting conversion and playlist creation..."
        updateButtonStates()
    }

    private fun updateButtonStates() {
        val isServiceRunning = isServiceRunning(ConversionService::class.java.name)
        convertPlaylistButton.isEnabled = !isServiceRunning
        cancelAllButton.isEnabled = isServiceRunning && conversionQueue.isNotEmpty()
    }

    private fun isServiceRunning(serviceClassName: String): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClassName == service.service.className) {
                return true
            }
        }
        return false
    }

    // Permission methods remain the same
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