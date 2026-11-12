package com.sahed.document_viewer

import java.util.Locale

enum class DocumentFormat(val extension: String, val mimeType: String, val label: String) {
    PDF("pdf", "application/pdf", "PDF"),
    DOC("doc", "application/msword", "Word"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word"),
    PPT("ppt", "application/vnd.ms-powerpoint", "PowerPoint"),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint"),
    XLS("xls", "application/vnd.ms-excel", "Excel"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel");

    companion object {
        val mimeTypes: Array<String> get() = entries.map { it.mimeType }.toTypedArray()

        fun from(name: String, mimeType: String? = null): DocumentFormat? =
            entries.find { it.extension == name.substringAfterLast('.', "").lowercase(Locale.ROOT) }
                ?: entries.find { it.mimeType == mimeType?.substringBefore(';') }
    }
}
