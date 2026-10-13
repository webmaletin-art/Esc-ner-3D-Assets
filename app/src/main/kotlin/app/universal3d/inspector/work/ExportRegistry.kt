package app.universal3d.inspector.work

import android.content.Context
import app.universal3d.core.export.ExportArtifact
import app.universal3d.core.model.Notice
import java.io.File

/** Resultado de una exportación pendiente de guardar por el usuario (archivo en caché privada + verificación). */
class PreparedExport(val file: File, val fileName: String, val mime: String, val formatLabel: String, val verifiedOk: Boolean, val verification: List<String>, val notices: List<Notice>)

class ExportRegistry(private val context: Context) {
    private val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    private val items = java.util.concurrent.ConcurrentHashMap<String, PreparedExport>()

    fun newFile(name: String): File { dir.mkdirs(); return File(dir, name) }
    fun register(p: PreparedExport) { items[p.file.path] = p }
    fun forFile(path: String?): PreparedExport? = path?.let { items[it] }?.takeIf { it.file.exists() }
    fun discard(path: String?) { path?.let { items.remove(it)?.file?.delete() } }
    fun cleanup() { dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24L * 3600_000) it.delete() } }
}
