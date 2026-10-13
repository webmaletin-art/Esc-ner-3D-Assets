package app.universal3d.inspector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.universal3d.inspector.ui.AppRoot
import app.universal3d.inspector.ui.U3DTheme
import app.universal3d.inspector.web.BrowserController
import app.universal3d.inspector.work.Pipelines
import app.universal3d.inspector.work.PreparedExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Acciones que requieren una Activity (selectores del sistema). No se piden permisos de almacenamiento. */
class ActivityActions(private val a: MainActivity) {
    fun pickModels(cb: (List<Uri>) -> Unit) = a.pickModels(cb)
    fun pickImages(cb: (List<Uri>) -> Unit) = a.pickImages(cb)
    fun save(p: PreparedExport) = a.saveExport(p)
}

private class CreateDocWithType : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer
    lateinit var pipelines: Pipelines
    lateinit var browser: BrowserController

    private var modelsCb: ((List<Uri>) -> Unit)? = null
    private var imagesCb: ((List<Uri>) -> Unit)? = null
    private var pendingSave: PreparedExport? = null
    private var chooserCb: ValueCallback<Array<Uri>>? = null

    private val openModels = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> modelsCb?.invoke(uris); modelsCb = null }
    private val openImages = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> imagesCb?.invoke(uris); imagesCb = null }
    private val createDoc = registerForActivityResult(CreateDocWithType()) { uri -> onSaveTarget(uri) }
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = chooserCb; chooserCb = null
        cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
    }

    fun pickModels(cb: (List<Uri>) -> Unit) { modelsCb = cb; openModels.launch(arrayOf("*/*")) }
    fun pickImages(cb: (List<Uri>) -> Unit) { imagesCb = cb; openImages.launch(arrayOf("image/*")) }

    fun saveExport(p: PreparedExport) {
        pendingSave = p
        createDoc.launch(p.fileName to p.mime)
    }

    private fun onSaveTarget(uri: Uri?) {
        val p = pendingSave; pendingSave = null
        if (uri == null || p == null) { container.toast("Guardado cancelado."); return }
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val expected = p.file.length()
                    contentResolver.openOutputStream(uri, "wt")?.use { out -> p.file.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException("No se pudo abrir el destino.")
                    // verificación: el destino existe, es legible y mide lo esperado
                    val size = contentResolver.openInputStream(uri)?.use { ins ->
                        var n = 0L; val b = ByteArray(65536)
                        while (true) { val r = ins.read(b); if (r < 0) break; n += r }
                        n
                    } ?: -1L
                    if (size != expected) throw IllegalStateException("El archivo guardado no coincide ($size de $expected bytes).")
                    "Guardado y verificado: ${p.fileName} (${expected / 1024} KB)"
                } catch (e: Exception) { "No se pudo guardar: ${e.message}" }
            }
            container.toast(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = (application as App).container
        pipelines = Pipelines(container, contentResolver)
        browser = BrowserController(container)
        browser.onFileChooser = { cb, params ->
            chooserCb?.onReceiveValue(null); chooserCb = cb
            try { chooser.launch(params.createIntent()); true } catch (e: Exception) { chooserCb = null; cb.onReceiveValue(null); false }
        }
        val actions = ActivityActions(this)
        val incoming = intent?.data?.takeIf { it.scheme == "content" }
        setContent { U3DTheme { AppRoot(container, browser, pipelines, actions, incoming) } }
    }

    override fun onDestroy() {
        if (isFinishing) { browser.destroy(); container.exports.cleanup() }
        super.onDestroy()
    }
}
