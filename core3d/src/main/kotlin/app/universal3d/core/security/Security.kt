package app.universal3d.core.security

import java.net.InetAddress
import java.net.URI

/** Resultado de validar una URL. */
data class UrlDecision(val allowed: Boolean, val url: String, val reason: String? = null, val upgradedFromHttp: Boolean = false)

/** Política de URLs para navegación y para descargas iniciadas por la app. */
object UrlPolicy {
    private val BLOCKED_SCHEMES = setOf("file", "content", "javascript", "data", "intent", "blob", "ftp", "jar", "about")

    /** Navegación del navegador integrado. HTTP se rechaza salvo que el usuario lo permita en ajustes. */
    fun checkNavigation(input: String, allowHttp: Boolean = false): UrlDecision {
        val raw = input.trim()
        if (raw.isEmpty()) return UrlDecision(false, raw, "URL vacía")
        val uri = try { URI(raw) } catch (_: Exception) { return UrlDecision(false, raw, "URL con formato inválido") }
        val scheme = uri.scheme?.lowercase() ?: return UrlDecision(false, raw, "Falta el esquema (https://)")
        if (scheme in BLOCKED_SCHEMES) return UrlDecision(false, raw, "Esquema '$scheme' bloqueado por seguridad")
        if (scheme == "https") return if (uri.host.isNullOrEmpty()) UrlDecision(false, raw, "Falta el host") else if (uri.userInfo != null) UrlDecision(false, raw, "URLs con usuario/contraseña embebidos no están permitidas") else UrlDecision(true, raw)
        if (scheme == "http") {
            if (uri.host.isNullOrEmpty()) return UrlDecision(false, raw, "Falta el host")
            return if (allowHttp) UrlDecision(true, raw) else UrlDecision(false, raw, "HTTP sin cifrar bloqueado; probá con https:// o activalo en Ajustes")
        }
        return UrlDecision(false, raw, "Esquema '$scheme' no soportado")
    }

    /** Convierte texto escrito en la barra de direcciones en URL: dominio -> https, texto -> búsqueda. */
    fun fromAddressBar(text: String, searchTemplate: String = "https://duckduckgo.com/?q=%s"): String {
        val t = text.trim()
        if (t.contains("://")) return t
        val looksLikeHost = !t.contains(' ') && (t.contains('.') || t.startsWith("localhost")) && !t.startsWith(".") && !t.endsWith(".")
        return if (looksLikeHost) "https://$t" else searchTemplate.replace("%s", java.net.URLEncoder.encode(t, "UTF-8"))
    }

    /** Validación para URLs de recursos proporcionadas por páginas (no confiables): solo HTTPS y host público. */
    fun checkResourceUrl(url: String, allowHttp: Boolean = false): UrlDecision {
        if (url.length > 2048) return UrlDecision(false, url, "URL demasiado larga")
        val d = checkNavigation(url, allowHttp)
        if (!d.allowed) return d
        val host = URI(url).host ?: return UrlDecision(false, url, "Sin host")
        if (isPrivateHostLiteral(host)) return UrlDecision(false, url, "Dirección local/privada bloqueada")
        return d
    }

    fun isPrivateHostLiteral(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return true
        // Sólo analizar literales IP sin hacer DNS
        val isV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h)
        val isV6 = h.contains(':')
        if (!isV4 && !isV6) return false
        return try { isPrivateAddress(InetAddress.getByName(h)) } catch (_: Exception) { true }
    }

    fun isPrivateAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        if (b.size == 4) {
            val b0 = b[0].toInt() and 0xFF; val b1 = b[1].toInt() and 0xFF
            if (b0 == 100 && b1 in 64..127) return true // CGNAT
            if (b0 == 0) return true
            if (b0 == 169 && b1 == 254) return true
        } else if (b.size == 16) {
            if ((b[0].toInt() and 0xFE) == 0xFC) return true // fc00::/7
        }
        return false
    }
}

/** Evita que tokens/credenciales aparezcan en registros. */
object LogRedactor {
    private val SENSITIVE = Regex("(?i)(token|key|secret|sig|signature|auth|password|passwd|session|cookie|jwt|bearer|apikey|api_key|access_token)")

    fun redactUrl(url: String): String {
        return try {
            val u = URI(url)
            val q = u.rawQuery?.split('&')?.joinToString("&") { p ->
                val k = p.substringBefore('=')
                if (SENSITIVE.containsMatchIn(k) || p.length > 64) "$k=***" else p
            }
            URI(u.scheme, null, u.host, u.port, u.rawPath?.let { java.net.URLDecoder.decode(it, "UTF-8") }, null, null).toString() + (q?.let { "?$it" } ?: "")
        } catch (_: Exception) { url.substringBefore('?') + if (url.contains('?')) "?***" else "" }
    }

    fun redactHeader(name: String, value: String): String =
        if (name.equals("authorization", true) || name.equals("cookie", true) || name.equals("set-cookie", true) || SENSITIVE.containsMatchIn(name)) "***" else value
}
