package com.example.ytconverter

import kotlinx.coroutines.Job

// Data class for queue items
data class ConversionItem(
    val id: String,
    val title: String,
    val url: String,
    var status: ConversionStatus,
    var job: Job? = null,
    var progress: String = ""
)

enum class ConversionStatus {
    WAITING, CONVERTING, COMPLETED, FAILED, CANCELLED
}