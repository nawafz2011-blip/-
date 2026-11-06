package com.nawaf.mohammel

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class HistoryItem(
    val id: String,
    val spec: JobSpec,
    val label: String,
    val title: String,
    val size: Long,
    val date: Long,
    val uri: String?,
    val mime: String?,
)

/** سجل التحميلات: ينحفظ في ملف داخل التطبيق. */
object History {
    private const val MAX = 500
    private lateinit var file: File
    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: StateFlow<List<HistoryItem>> = _items

    fun init(context: Context) {
        file = File(context.filesDir, "history.json")
        _items.value = load()
    }

    private fun load(): List<HistoryItem> = try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            HistoryItem(
                id = o.getString("id"),
                spec = JobSpec.fromJson(o.getJSONObject("spec")),
                label = o.optString("label"),
                title = o.optString("title"),
                size = o.optLong("size"),
                date = o.optLong("date"),
                uri = o.optStringOrNull("uri"),
                mime = o.optStringOrNull("mime"),
            )
        }
    } catch (_: Throwable) {
        emptyList()
    }

    @Synchronized
    private fun persist(list: List<HistoryItem>) {
        _items.value = list
        val arr = JSONArray()
        for (h in list) {
            arr.put(JSONObject().apply {
                put("id", h.id); put("spec", h.spec.toJson()); put("label", h.label); put("title", h.title)
                put("size", h.size); put("date", h.date)
                put("uri", h.uri ?: JSONObject.NULL); put("mime", h.mime ?: JSONObject.NULL)
            })
        }
        val tmp = File(file.parentFile, "history.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    fun add(job: Job) {
        val saved = job.saved
        val item = HistoryItem(
            id = UUID.randomUUID().toString().take(12),
            spec = job.spec,
            label = job.spec.label,
            title = job.title,
            size = saved?.size ?: 0,
            date = System.currentTimeMillis(),
            uri = saved?.uri?.toString(),
            mime = saved?.mime,
        )
        persist((listOf(item) + _items.value).take(MAX))
    }

    fun delete(id: String) = persist(_items.value.filterNot { it.id == id })

    fun clear() = persist(emptyList())

    fun uriOf(item: HistoryItem): Uri? = item.uri?.let(Uri::parse)
}
