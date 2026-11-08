package com.sahed.document_viewer

import androidx.lifecycle.ViewModel
import java.io.File

// Survives rotations without keeping an Activity or an open renderer alive.
class ReaderSession : ViewModel() {
    var source: File? = null
    var title = ""
    var page = 0
    val markup = mutableMapOf<Int, MutableList<ZoomImageView.Stroke>>()
    var dirty = false
    var pendingExport: File? = null
    var originalUri: String? = null
    val pages = PageCache()

    override fun onCleared() {
        pages.clear()
        pendingExport?.delete()
    }
}
