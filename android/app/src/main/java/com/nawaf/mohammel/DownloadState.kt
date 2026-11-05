package com.nawaf.mohammel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** حالة التحميل، يشاركها الـ Service مع الشاشة. */
sealed interface DownloadStatus {
    data object Idle : DownloadStatus
    data class Running(val title: String, val progress: Float, val etaSeconds: Long) : DownloadStatus
    data class Done(val title: String, val file: SavedFile) : DownloadStatus
    data class Failed(val title: String, val message: String) : DownloadStatus
    data class Canceled(val title: String) : DownloadStatus
}

object DownloadState {
    private val _status = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val status: StateFlow<DownloadStatus> = _status
    fun set(s: DownloadStatus) { _status.value = s }
}
