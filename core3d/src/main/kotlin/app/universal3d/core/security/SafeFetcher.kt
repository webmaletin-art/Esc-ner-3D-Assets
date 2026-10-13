package app.universal3d.core.security

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class FetchException(val kind: Kind, message: String, val httpCode: Int = -1, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind { POLICY, HTTP, AUTH, NOT_FOUND, TOO_LARGE, NETWORK, REDIRECT }
}

class ProbeResult(val finalUrl: String, val httpCode: Int, val contentType: String?, val totalBytes: Long?, val head: ByteArray, val rangeSupported: Boolean)

/**
 * Cliente HTTP restringido: sólo HTTPS (salvo configuración), rechaza hosts privados incluso tras DNS,
 * valida cada redirección, limita tamaño y soporta cancelación y progreso real.
 * Los encabezados Cookie/Authorization nunca se registran.
 */
class SafeFetcher(
    allowHttp: Boolean = false,
    private val blockPrivate: Boolean = true,
    private val userAgent: String = "Universal3DAssetInspector/1.0",
    client: OkHttpClient? = null,
    /** Permite leer el ajuste en vivo (el usuario puede cambiarlo sin recrear el cliente). */
    private val allowHttpProvider: (() -> Boolean)? = null,
) {
    private val allowHttpFixed = allowHttp
    private val allowHttp: Boolean get() = allowHttpProvider?.invoke() ?: allowHttpFixed

    private val http: OkHttpClient = (client ?: OkHttpClient.Builder().build()).newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val all = Dns.SYSTEM.lookup(hostname)
                if (blockPrivate && all.any { UrlPolicy.isPrivateAddress(it) }) throw UnknownHostException("Dirección privada bloqueada para $hostname")
                return all
            }
        })
        .build()

    /** Valida una URL con la misma política que usarán las peticiones. */
    fun check(url: String): UrlDecision = if (blockPrivate) UrlPolicy.checkResourceUrl(url, allowHttp) else UrlPolicy.checkNavigation(url, allowHttp)

    /** Pide los primeros bytes para verificar tipo/tamaño sin descargar todo. */
    suspend fun probe(url: String, headBytes: Int = 64, headers: Map<String, String> = emptyMap()): ProbeResult = withContext(Dispatchers.IO) {
        val (resp, finalUrl) = execute(url, headers + ("Range" to "bytes=0-${headBytes - 1}"))
        resp.use { r ->
            val code = r.code
            if (code == 401 || code == 403) throw FetchException(FetchException.Kind.AUTH, "Acceso denegado (HTTP $code)", code)
            if (code == 404 || code == 410) throw FetchException(FetchException.Kind.NOT_FOUND, "No encontrado (HTTP $code)", code)
            if (code !in 200..299) throw FetchException(FetchException.Kind.HTTP, "Error HTTP $code", code)
            val range = r.header("Content-Range")
            val total = range?.substringAfter('/')?.toLongOrNull() ?: if (code == 200) r.header("Content-Length")?.toLongOrNull() else null
            val src = r.body.source()
            val buf = ByteArray(headBytes)
            var n = 0
            while (n < headBytes) { val k = src.read(buf, n, headBytes - n); if (k <= 0) break; n += k }
            ProbeResult(finalUrl, code, r.header("Content-Type"), total, buf.copyOf(n), code == 206)
        }
    }

    /**
     * Descarga completa en memoria con límite y progreso real. [onProgress] recibe (bytesLeídos, totalConocidoONull).
     */
    suspend fun download(
        url: String, maxBytes: Long, headers: Map<String, String> = emptyMap(),
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): ByteArray = withContext(Dispatchers.IO) {
        val (resp, _) = execute(url, headers)
        resp.use { r ->
            val code = r.code
            if (code == 401 || code == 403) throw FetchException(FetchException.Kind.AUTH, "Acceso denegado (HTTP $code)", code)
            if (code == 404 || code == 410) throw FetchException(FetchException.Kind.NOT_FOUND, "No encontrado (HTTP $code)", code)
            if (code !in 200..299) throw FetchException(FetchException.Kind.HTTP, "Error HTTP $code", code)
            val total = r.body.contentLength().takeIf { it >= 0 }
            if (total != null && total > maxBytes) throw FetchException(FetchException.Kind.TOO_LARGE, "El archivo (${total / 1048576} MB) excede el límite permitido (${maxBytes / 1048576} MB).")
            val src = r.body.source()
            val ctx = currentCoroutineContext()
            if (total != null) {
                // tamaño conocido: se reserva exactamente y se rellena sin copias intermedias
                val data = ByteArray(total.toInt())
                var done = 0
                while (done < data.size) {
                    ctx.ensureActive()
                    val n = src.read(data, done, minOf(64 * 1024, data.size - done))
                    if (n < 0) throw FetchException(FetchException.Kind.NETWORK, "Descarga incompleta ($done de ${data.size} bytes).")
                    done += n
                    onProgress(done.toLong(), total)
                }
                return@use data
            }
            val out = ByteArrayOutputStream(64 * 1024)
            val buf = ByteArray(32 * 1024)
            var done = 0L
            while (true) {
                ctx.ensureActive()
                val n = src.read(buf)
                if (n < 0) break
                done += n
                if (done > maxBytes) throw FetchException(FetchException.Kind.TOO_LARGE, "La descarga superó el límite permitido (${maxBytes / 1048576} MB).")
                out.write(buf, 0, n)
                onProgress(done, null)
            }
            out.toByteArray()
        }
    }

    /** Descarga en streaming a un archivo (sin cargarlo en memoria). Si falla o se cancela, el archivo parcial se elimina. */
    suspend fun downloadToFile(
        url: String, target: java.io.File, maxBytes: Long, headers: Map<String, String> = emptyMap(),
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        val (resp, _) = execute(url, headers)
        var ok = false
        try {
            resp.use { r ->
                val code = r.code
                if (code == 401 || code == 403) throw FetchException(FetchException.Kind.AUTH, "Acceso denegado (HTTP $code)", code)
                if (code == 404 || code == 410) throw FetchException(FetchException.Kind.NOT_FOUND, "No encontrado (HTTP $code)", code)
                if (code !in 200..299) throw FetchException(FetchException.Kind.HTTP, "Error HTTP $code", code)
                val total = r.body.contentLength().takeIf { it >= 0 }
                if (total != null && total > maxBytes) throw FetchException(FetchException.Kind.TOO_LARGE, "El archivo (${total / 1048576} MB) excede el límite permitido.")
                target.parentFile?.mkdirs()
                val ctx = currentCoroutineContext()
                var done = 0L
                target.outputStream().buffered().use { out ->
                    val src = r.body.source(); val buf = ByteArray(64 * 1024)
                    while (true) {
                        ctx.ensureActive()
                        val n = src.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > maxBytes) throw FetchException(FetchException.Kind.TOO_LARGE, "La descarga superó el límite permitido.")
                        out.write(buf, 0, n); onProgress(done, total)
                    }
                }
                if (total != null && done < total) throw FetchException(FetchException.Kind.NETWORK, "Descarga incompleta ($done de $total bytes).")
                ok = true
                done
            }
        } finally { if (!ok) target.delete() }
    }

    private suspend fun execute(startUrl: String, headers: Map<String, String>): Pair<okhttp3.Response, String> {
        var url = startUrl
        val ctx = currentCoroutineContext()
        for (hop in 0..5) {
            ctx.ensureActive()
            val d = check(url)
            if (!d.allowed) throw FetchException(FetchException.Kind.POLICY, d.reason ?: "URL bloqueada")
            val req = Request.Builder().url(url).header("User-Agent", userAgent).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            val call = http.newCall(req)
            val job = ctx[kotlinx.coroutines.Job]
            val handle = job?.invokeOnCompletion { call.cancel() }
            val resp = try { call.execute() } catch (e: IOException) {
                throw if (e is FetchException) e else FetchException(FetchException.Kind.NETWORK, "Error de red: ${e.message?.take(120)}", cause = e)
            } finally { handle?.dispose() }
            if (resp.code in 300..399) {
                val loc = resp.header("Location")
                resp.close()
                if (loc == null) throw FetchException(FetchException.Kind.REDIRECT, "Redirección sin destino")
                url = ResourceUrl.resolve(url, loc) ?: throw FetchException(FetchException.Kind.REDIRECT, "Redirección inválida")
                // Authorization/Cookie no se reenvían a otro origen (se evita filtrar credenciales)
                continue
            }
            return resp to url
        }
        throw FetchException(FetchException.Kind.REDIRECT, "Demasiadas redirecciones")
    }

    private object ResourceUrl {
        fun resolve(base: String, ref: String): String? = try { java.net.URI(base).resolve(ref).toString() } catch (_: Exception) { null }
    }
}
