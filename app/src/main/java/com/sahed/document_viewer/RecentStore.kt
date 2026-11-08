package com.sahed.document_viewer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentItem(val name: String, val uri: String, val page: Int = 0, val pageCount: Int = 0)

class RecentStore(context: Context) {
    private val prefs = context.getSharedPreferences("recent_pdfs", Context.MODE_PRIVATE)

    fun list(): List<RecentItem> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                RecentItem(o.optString("name", "PDF"), o.optString("uri", ""),
                    o.optInt("page", 0).coerceAtLeast(0), o.optInt("pageCount", 0).coerceAtLeast(0))
            }.filter { it.uri.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(name: String, uri: Uri) {
        val previous = list().find { it.uri == uri.toString() }
        val items = list().filterNot { it.uri == uri.toString() }.toMutableList()
        items.add(0, RecentItem(name, uri.toString(), previous?.page ?: 0, previous?.pageCount ?: 0))
        save(items.take(12))
    }

    fun progress(uri: String, page: Int, pageCount: Int) {
        val items = list()
        if (items.any { it.uri == uri && (it.page != page || it.pageCount != pageCount) }) {
            save(items.map { if (it.uri == uri) it.copy(page = page, pageCount = pageCount) else it })
        }
    }

    fun remove(uri: String) {
        val items = list()
        if (items.any { it.uri == uri }) save(items.filterNot { it.uri == uri })
    }

    private fun save(items: List<RecentItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("name", it.name).put("uri", it.uri)
                .put("page", it.page).put("pageCount", it.pageCount))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "items"
    }
}
