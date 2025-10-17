package com.example.ytconverter

import kotlinx.coroutines.Job

// Data class for queue items
data class ConversionItem(
    val id: String,
    var title: String,
    val url: String,
    var status: ConversionStatus,
    var job: Job? = null,
    var progress: String = "",
    var format: String = "", // Track audio format: "opus", "aac", etc.
    var batchName: String = "", // Track which playlist/video batch this belongs to
    var isPlaylistItem: Boolean = false // Track if this came from a playlist vs single video
)

enum class ConversionStatus {
    WAITING, CONVERTING, COMPLETED, FAILED, CANCELLED
}