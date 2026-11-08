package com.sahed.document_viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import kotlin.math.min

/** Bounded by bytes, not page count. Evicted bitmaps may still be displayed by a View. */
class PageCache {
    private val cache = object : LruCache<String, Bitmap>(
        min(24 * 1024 * 1024, (Runtime.getRuntime().maxMemory() / 8).toInt())
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun render(source: File, index: Int): Bitmap {
        val key = "${source.absolutePath}:$index"
        cache.get(key)?.let { return it }
        val bitmap = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(index).use { page ->
                    val scale = min(2f, 2000f / maxOf(page.width, page.height))
                    val result = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try {
                        result.eraseColor(Color.WHITE)
                        page.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        result
                    } catch (e: Exception) { result.recycle(); throw e }
                }
            }
        }
        cache.put(key, bitmap)
        return bitmap
    }

    fun clear() = cache.evictAll()
}
