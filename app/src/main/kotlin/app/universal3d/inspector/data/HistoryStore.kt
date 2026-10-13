package app.universal3d.inspector.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.io.File

data class HistoryEntry(val url: String, val title: String, val time: Long)

/** Historial básico en un archivo JSON privado (máx. 500 entradas, sin duplicados consecutivos). */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    private fun load(): List<HistoryEntry> = try {
        if (!file.exists()) emptyList() else Json.parseToJsonElement(file.readText()).jsonArray.mapNotNull {
            val o = it.jsonObject
            HistoryEntry(o["u"]!!.jsonPrimitive.content, o["t"]?.jsonPrimitive?.contentOrNull ?: "", o["d"]!!.jsonPrimitive.long)
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized fun add(url: String, title: String?) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        val cur = _entries.value
        if (cur.firstOrNull()?.url == url) return
        val next = (listOf(HistoryEntry(url, title ?: "", System.currentTimeMillis())) + cur).take(500)
        _entries.value = next; save(next)
    }

    @Synchronized fun clear() { _entries.value = emptyList(); file.delete() }

    @Synchronized fun remove(e: HistoryEntry) { val n = _entries.value - e; _entries.value = n; save(n) }

    private fun save(l: List<HistoryEntry>) {
        try {
            val arr = JsonArray(l.map { buildJsonObject { put("u", it.url); put("t", it.title); put("d", it.time) } })
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }
}
