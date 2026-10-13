package app.universal3d.inspector.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.ViewGroup
import android.webkit.*
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.universal3d.core.detect.PageSignals
import app.universal3d.core.replicate.CaptureProvider
import app.universal3d.core.resources.ResourceClassifier
import app.universal3d.core.security.UrlPolicy
import app.universal3d.inspector.AppContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.util.UUID

data class BrowserUiState(
    val url: String = "", val title: String = "", val progress: Int = 0, val loading: Boolean = false,
    val canGoBack: Boolean = false, val canGoForward: Boolean = false, val error: String? = null,
    val webMessagingSupported: Boolean = true,
)

class DownloadRequest(val url: String, val userAgent: String?, val contentDisposition: String?, val mimeType: String?, val contentLength: Long) {
    val fileName: String get() = URLUtil.guessFileName(url, contentDisposition, mimeType)
}

/**
 * Controla un único WebView. Seguridad:
 *  - sin interfaces JavaScript nativas (addJavascriptInterface): se usa WebMessageListener con datos validados;
 *  - errores de certificado siempre cancelan; HTTP bloqueado salvo ajuste; sin acceso a archivos; permisos de página denegados.
 */
class BrowserController(private val c: AppContainer) {
    private var webView: WebView? = null
    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    var onFileChooser: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null
    var onDownload: ((DownloadRequest) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainSignals = HashMap<String, PageSignals>()   // por marco
    private val replyProxies = HashMap<String, JavaScriptReplyProxy>()
    private val pendingCaptures = HashMap<String, CaptureAssembly>()

    private class CaptureAssembly(val parts: HashMap<String, Array<String?>> = HashMap(), val done: CompletableDeferred<String?> = CompletableDeferred())

    val detection get() = c.detection.report

    // ------------------------------------------------------------------ WebView
    /** El WebView se conserva al cambiar de pestaña para no perder la página. */
    fun obtainWebView(context: Context): WebView {
        webView?.let { wv -> (wv.parent as? ViewGroup)?.removeView(wv); wv.onResume(); return wv }
        val wv = createWebView(context)
        load(c.settings.state.value.homeUrl)
        return wv
    }

    fun releaseView() { webView?.let { (it.parent as? ViewGroup)?.removeView(it); it.onPause() } }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView {
        val wv = WebView(context)
        webView = wv
        wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val s = wv.settings
        val st = c.settings.state.value
        s.javaScriptEnabled = st.javascript
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION") run { s.allowFileAccessFromFileURLs = false; s.allowUniversalAccessFromFileURLs = false }
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.setGeolocationEnabled(false)
        s.safeBrowsingEnabled = true
        s.loadWithOverviewMode = true; s.useWideViewPort = true
        s.builtInZoomControls = true; s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = true
        s.userAgentString = s.userAgentString.replace("; wv", "") // evita servir versiones "WebView" degradadas
        WebView.setWebContentsDebuggingEnabled(false)
        try { if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, true) } catch (_: Exception) { }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(wv, false) }

        installPageScript(wv)
        wv.webViewClient = client
        wv.webChromeClient = chrome
        wv.setDownloadListener { url, ua, cd, mime, len -> onDownload?.invoke(DownloadRequest(url, ua, cd, mime, len)) }
        c.headersProvider = { resource, page -> sameSiteHeaders(resource, page) }
        return wv
    }

    fun destroy() {
        webView?.let { wv -> (wv.parent as? ViewGroup)?.removeView(wv); wv.stopLoading(); wv.destroy() }
        webView = null
        c.headersProvider = { _, _ -> emptyMap() }
    }

    private fun installPageScript(wv: WebView) {
        val st = c.settings.state.value
        val canListen = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        val canStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        _state.value = _state.value.copy(webMessagingSupported = canListen && canStart)
        if (!canListen || !canStart) return
        val script = PageScript.load(wv.context, st.captureWebGl)
        // Se prueba el conjunto de reglas de origen más amplio primero; cada intento es atómico (listener + script).
        val ruleSets = listOf(setOf("*"), setOf("https://*"))
        var ok = false
        for (rules in ruleSets) {
            try {
                WebViewCompat.addWebMessageListener(wv, "U3DBridge", rules) { _, message, origin, isMain, proxy -> onBridgeMessage(message, origin.toString(), isMain, proxy) }
                WebViewCompat.addDocumentStartJavaScript(wv, script, rules)
                ok = true; break
            } catch (e: Exception) {
                try { WebViewCompat.removeWebMessageListener(wv, "U3DBridge") } catch (_: Exception) { }
            }
        }
        if (!ok) _state.value = _state.value.copy(webMessagingSupported = false)
    }

    // ------------------------------------------------------------------ mensajes de la página (no confiables)
    private fun onBridgeMessage(message: WebMessageCompat, origin: String, isMain: Boolean, proxy: JavaScriptReplyProxy) {
        val data = message.data ?: return
        if (data.length > PageSignals.MAX_JSON + 300_000) return
        val obj = try { Json.parseToJsonElement(data) as? JsonObject } catch (_: Exception) { null } ?: return
        val key = (if (isMain) "main:" else "frame:") + origin
        when (obj["t"]?.jsonPrimitive?.contentOrNull) {
            "sig" -> {
                val json = obj["d"]?.jsonPrimitive?.contentOrNull ?: return
                val sig = PageSignals.parse(json) ?: return
                if (replyProxies.size < 20) replyProxies[key] = proxy
                synchronized(mainSignals) {
                    if (mainSignals.size < 20 || mainSignals.containsKey(key)) mainSignals[key] = sig
                    val main = mainSignals.entries.firstOrNull { it.key.startsWith("main:") }?.value
                    val frames = mainSignals.filterKeys { !it.startsWith("main:") }.values
                    PageSignals.merge(main, frames)
                }?.let { merged -> c.detection.onSignals(merged) }
            }
            "cap" -> {
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return
                val asm = pendingCaptures[id] ?: return          // sólo respuestas a solicitudes propias (id aleatorio)
                val i = obj["i"]?.jsonPrimitive?.intOrNull ?: return
                val n = obj["n"]?.jsonPrimitive?.intOrNull ?: return
                if (n !in 1..400 || i !in 0 until n) return
                val d = obj["d"]?.jsonPrimitive?.contentOrNull ?: return
                val parts = asm.parts.getOrPut(key) { arrayOfNulls(n) }
                if (parts.size != n) return
                parts[i] = d
                if (parts.all { it != null }) {
                    val json = parts.joinToString("")
                    // elige la respuesta con geometría; la primera completa con draws gana
                    if (!json.contains("\"error\"") && json.contains("\"draws\":[{")) asm.done.complete(json)
                    else if (asm.parts.values.all { p -> p.all { it != null } } && asm.parts.size >= replyProxies.size.coerceAtLeast(1)) asm.done.complete(null)
                }
            }
        }
    }

    /** Captura de geometría WebGL a pedido del usuario. Devuelve JSON o null si ninguna página respondió con geometría. */
    val captureProvider = CaptureProvider {
        val id = UUID.randomUUID().toString().replace("-", "")
        val asm = CaptureAssembly()
        pendingCaptures[id] = asm
        try {
            withContext(Dispatchers.Main) {
                if (replyProxies.isEmpty()) {
                    // sin canal de mensajes: sólo marco principal por evaluateJavascript
                    webView?.evaluateJavascript("window.__u3d&&window.__u3d.requestCapture('$id')", null)
                } else replyProxies.values.forEach { p -> try { if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) p.postMessage("capture:$id") } catch (_: Exception) { } }
            }
            withTimeoutOrNull(25_000) { asm.done.await() }
        } finally { pendingCaptures.remove(id) }
    }

    private fun sameSiteHeaders(resourceUrl: String, pageUrl: String): Map<String, String> {
        return try {
            val rh = URI(resourceUrl).host?.lowercase() ?: return emptyMap()
            val ph = URI(pageUrl).host?.lowercase() ?: return emptyMap()
            val same = rh == ph || rh.endsWith(".$ph") || ph.endsWith(".$rh") || rh.substringAfter('.') == ph.substringAfter('.')
            if (!same) return emptyMap()
            val m = HashMap<String, String>()
            CookieManager.getInstance().getCookie(resourceUrl)?.takeIf { it.isNotBlank() }?.let { m["Cookie"] = it }
            m["Referer"] = pageUrl
            m
        } catch (_: Exception) { emptyMap() }
    }

    fun cookieHeaderFor(url: String, pageUrl: String) = sameSiteHeaders(url, pageUrl)

    // ------------------------------------------------------------------ navegación
    fun load(input: String) {
        val st = c.settings.state.value
        val url = UrlPolicy.fromAddressBar(input)
        val d = UrlPolicy.checkNavigation(url, st.allowHttp)
        if (!d.allowed) { _state.value = _state.value.copy(error = d.reason, url = url); return }
        _state.value = _state.value.copy(error = null)
        webView?.loadUrl(d.url)
    }
    fun reload() { _state.value = _state.value.copy(error = null); webView?.reload() }
    fun stop() { webView?.stopLoading() }
    fun back() { webView?.goBack() }
    fun forward() { webView?.goForward() }
    fun currentUrl(): String = _state.value.url
    fun clearError() { _state.value = _state.value.copy(error = null) }

    private fun refreshNav(wv: WebView) { _state.value = _state.value.copy(canGoBack = wv.canGoBack(), canGoForward = wv.canGoForward()) }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val d = UrlPolicy.checkNavigation(url, c.settings.state.value.allowHttp)
            if (!d.allowed) {
                if (request.isForMainFrame) _state.value = _state.value.copy(error = "Navegación bloqueada: ${d.reason}")
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url.toString()
            if (request.method == "GET" && ResourceClassifier.isModelCandidate(u)) c.detection.onNetworkUrl(u)
            return null // la carga la sigue haciendo WebView
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            synchronized(mainSignals) { mainSignals.clear() }
            replyProxies.clear()
            c.detection.onPageStarted(url)
            _state.value = _state.value.copy(url = url, loading = true, progress = 0, error = null)
            refreshNav(view)
        }

        override fun onPageFinished(view: WebView, url: String) {
            _state.value = _state.value.copy(url = url, loading = false, title = view.title ?: "")
            c.history.add(url, view.title)
            refreshNav(view)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { refreshNav(view) }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel() // nunca se ignoran errores de certificado
            _state.value = _state.value.copy(loading = false, error = "Error de certificado en ${error.url.substringAfter("://").substringBefore('/')}: la conexión no es segura y se bloqueó.")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) _state.value = _state.value.copy(loading = false, error = "No se pudo cargar la página (${error.description}).")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) { }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            _state.value = _state.value.copy(loading = false, error = "El proceso del navegador se detuvo (memoria insuficiente o fallo). Recargá la página.")
            return true // evita que se cierre la app
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { _state.value = _state.value.copy(progress = newProgress, loading = newProgress < 100) }
        override fun onReceivedTitle(view: WebView, title: String?) { _state.value = _state.value.copy(title = title ?: "") }
        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
            onFileChooser?.invoke(filePathCallback, params) ?: run { filePathCallback.onReceiveValue(null); false }
        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false
    }
}

object PageScript {
    @Volatile private var cached: String? = null
    fun load(context: Context, capture: Boolean): String {
        val raw = cached ?: context.assets.open("u3d_page.js").use { String(it.readBytes(), Charsets.UTF_8) }.also { cached = it }
        return raw.replace("__U3D_CAPTURE__", capture.toString()).replace("__U3D_MAXBYTES__", (24 * 1024 * 1024).toString())
    }
}
