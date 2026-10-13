package app.universal3d.inspector.data

import android.content.Context
import app.universal3d.core.io.GlbWriter
import app.universal3d.core.io.ModelImporter
import app.universal3d.core.io.SafeNames
import app.universal3d.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

data class LibraryItem(
    val id: String, val name: String, val sourceFormat: ModelFormat, val createdAt: Long, val triangles: Long, val sizeBytes: Long, val origin: String,
    val notices: List<Notice>,
)

/** Biblioteca de modelos locales. Cada modelo se guarda como GLB (formato interno) + metadatos, en almacenamiento privado de la app. */
class LibraryRepository(context: Context) {
    private val root = File(context.filesDir, "library").apply { mkdirs() }
    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items: StateFlow<List<LibraryItem>> = _items.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _items.value = (root.listFiles() ?: emptyArray()).mapNotNull { readMeta(File(it, "meta.json")) }.sortedByDescending { it.createdAt }
    }

    private fun readMeta(f: File): LibraryItem? = try {
        val o = Json.parseToJsonElement(f.readText()).jsonObject
        LibraryItem(
            o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content,
            runCatching { ModelFormat.valueOf(o["format"]!!.jsonPrimitive.content) }.getOrDefault(ModelFormat.UNKNOWN),
            o["created"]!!.jsonPrimitive.long, o["tris"]?.jsonPrimitive?.longOrNull ?: 0, o["size"]?.jsonPrimitive?.longOrNull ?: 0,
            o["origin"]?.jsonPrimitive?.contentOrNull ?: "",
            o["notices"]?.jsonArray?.mapNotNull {
                val n = it.jsonObject
                Notice(runCatching { Notice.Severity.valueOf(n["s"]!!.jsonPrimitive.content) }.getOrDefault(Notice.Severity.INFO), n["c"]!!.jsonPrimitive.content, n["m"]!!.jsonPrimitive.content)
            } ?: emptyList(),
        )
    } catch (_: Exception) { null }

    suspend fun save(scene: Scene3D, origin: String): LibraryItem = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().take(12)
        val dir = File(root, id).apply { mkdirs() }
        try {
            val modelFile = File(dir, "model.glb")
            // escritura directa a disco por trozos: evita duplicar el modelo completo en memoria
            modelFile.outputStream().buffered(256 * 1024).use { GlbWriter.writeTo(scene, it) }
            val glbSize = modelFile.length()
            val tris = scene.meshes.sumOf { m -> m.primitives.sumOf { it.triangleCount.toLong() } }
            val item = LibraryItem(id, SafeNames.fileStem(scene.name, "modelo"), scene.sourceFormat, System.currentTimeMillis(), tris, glbSize, origin, scene.importNotices)
            writeMeta(dir, item)
            _items.value = listOf(item) + _items.value
            item
        } catch (e: Throwable) { dir.deleteRecursively(); throw e }
    }

    private fun writeMeta(dir: File, item: LibraryItem) {
        val o = buildJsonObject {
            put("id", item.id); put("name", item.name); put("format", item.sourceFormat.name); put("created", item.createdAt)
            put("tris", item.triangles); put("size", item.sizeBytes); put("origin", item.origin)
            put("notices", JsonArray(item.notices.map { buildJsonObject { put("s", it.severity.name); put("c", it.code); put("m", it.message) } }))
        }
        File(dir, "meta.json").writeText(o.toString())
    }

    suspend fun load(id: String): Scene3D = withContext(Dispatchers.IO) {
        val dir = File(root, id)
        val item = readMeta(File(dir, "meta.json")) ?: throw IllegalStateException("Modelo no encontrado en la biblioteca.")
        val scene = ModelImporter.importBytes("${item.name}.glb", File(dir, "model.glb").readBytes())
        scene.retag(name = item.name, sourceFormat = item.sourceFormat, notices = item.notices, size = File(dir, "model.glb").length())
    }

    suspend fun rename(id: String, newName: String) = withContext(Dispatchers.IO) {
        val dir = File(root, id); val item = readMeta(File(dir, "meta.json")) ?: return@withContext
        val n = item.copy(name = SafeNames.fileStem(newName, item.name)); writeMeta(dir, n)
        _items.value = _items.value.map { if (it.id == id) n else it }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively(); _items.value = _items.value.filter { it.id != id }
    }

    fun storageBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
