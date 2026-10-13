package app.universal3d.core.replicate

import app.universal3d.core.io.ModelImportException
import app.universal3d.core.io.ObjReader
import app.universal3d.core.model.*
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class CaptureReconstruction(val scene: Scene3D, val drawsRead: Int, val drawsUsed: Int, val skipped: List<String>)

/**
 * Reconstruye geometría a partir de los buffers de vértices/índices que la página subió a la GPU y de las llamadas de dibujo
 * observadas por el script de captura. Es una reconstrucción PARCIAL: pose actual, sin esqueleto, materiales aproximados
 * y sólo con las texturas que el navegador permitió leer (sin violar restricciones de origen cruzado).
 */
object WebGlCaptureReconstructor {
    const val TRIANGLES = 4; const val TRIANGLE_STRIP = 5; const val TRIANGLE_FAN = 6
    private const val MAX_VERTICES = 3_000_000

    private class Buf(val data: ByteBuffer)
    private class Attr(val name: String, val loc: Int, val size: Int, val type: Int, val norm: Boolean, val stride: Int, val offset: Int, val buffer: String)

    fun reconstruct(json: String, name: String = "captura_webgl"): CaptureReconstruction {
        val root = try { Json.parseToJsonElement(json) as JsonObject } catch (e: Exception) {
            throw ModelImportException(ModelImportException.Code.MALFORMED, "La captura WebGL no es válida: ${e.message?.take(80)}")
        }
        val buffers = HashMap<String, Buf>()
        (root["buffers"] as? JsonObject)?.forEach { (id, v) ->
            val b64 = ((v as? JsonObject)?.get("data") as? JsonPrimitive)?.contentOrNull ?: return@forEach
            try { buffers[id] = Buf(ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(ByteOrder.LITTLE_ENDIAN)) } catch (_: Exception) { }
        }
        // Texturas legibles
        val images = ArrayList<Image3D>(); val textures = ArrayList<Texture3D>(); val texIdx = HashMap<String, Int>()
        (root["textures"] as? JsonObject)?.forEach { (id, v) ->
            val o = v as? JsonObject ?: return@forEach
            val png = (o["png"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            try {
                val bytes = Base64.getDecoder().decode(png)
                images.add(Image3D("tex_$id", app.universal3d.core.model.ImageHeader.sniffMime(bytes) ?: "image/png", bytes))
                textures.add(Texture3D("tex_$id", images.size - 1)); texIdx[id] = textures.size - 1
            } catch (_: Exception) { }
        }
        val draws = (root["draws"] as? JsonArray) ?: JsonArray(emptyList())
        val skipped = ArrayList<String>()
        val seen = HashSet<String>()
        val nodes = ArrayList<Node3D>(); val meshes = ArrayList<Mesh3D>(); val materials = ArrayList<Material3D>()
        val matForTex = HashMap<Int, Int>()
        var used = 0
        for ((di, d) in draws.withIndex()) {
            val o = d as? JsonObject ?: continue
            val mode = (o["mode"] as? JsonPrimitive)?.intOrNull ?: continue
            if (mode !in TRIANGLES..TRIANGLE_FAN) { skipped.add("dibujo $di: modo $mode no es de triángulos"); continue }
            val attrs = (o["attribs"] as? JsonArray)?.mapNotNull { parseAttr(it as? JsonObject) } ?: emptyList()
            val roles = assignRoles(attrs)
            val posA = roles["position"] ?: run { skipped.add("dibujo $di: sin atributo de posición identificable"); null } ?: continue
            val key = listOf(mode, o["count"], o["first"], o["indexType"], o["indexOffset"], o["elementBuffer"], posA.buffer, posA.offset, posA.stride).joinToString("|")
            if (!seen.add(key)) continue
            val prim = try { buildPrimitive(o, mode, attrs, roles, buffers) } catch (e: Exception) { skipped.add("dibujo $di: ${e.message}"); null } ?: continue
            if (isScreenQuad(prim)) { skipped.add("dibujo $di: parece un pase de pantalla completa"); continue }
            // textura base: primera textura legible enlazada al dibujo si hay UV
            var matIndex = -1
            val texIds = (o["textures"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val ti = if (prim.uv0 != null) texIds.firstNotNullOfOrNull { texIdx[it] } else null
            matIndex = if (ti != null) matForTex.getOrPut(ti) { materials.add(Material3D("captura_tex_$ti", baseColorTexture = TextureRef(ti), metallicFactor = 0f, roughnessFactor = 0.8f, doubleSided = true)); materials.size - 1 }
            else { matForTex.getOrPut(-1) { materials.add(Material3D("captura_gris", baseColorFactor = floatArrayOf(0.7f, 0.7f, 0.7f, 1f), metallicFactor = 0f, roughnessFactor = 0.8f, doubleSided = true)); materials.size - 1 } }
            val withMat = Primitive3D(prim.positions, prim.indices, prim.normals, prim.uv0, null, prim.colors, null, null, matIndex)
            meshes.add(Mesh3D("captura_$used", listOf(withMat)))
            // matriz de modelo si se pudo identificar por nombre
            val model = modelMatrix(o)
            val node = if (model != null) { val t = model.decompose(); Node3D("captura_$used", meshIndex = meshes.size - 1, translation = t.t, rotation = t.q, scale = t.s) } else Node3D("captura_$used", meshIndex = meshes.size - 1)
            nodes.add(node); used++
        }
        if (nodes.isEmpty()) throw ModelImportException(ModelImportException.Code.EMPTY, "La captura no contiene geometría de triángulos utilizable.")
        val notices = mutableListOf(
            Notice(Notice.Severity.WARNING, "capture", "Reconstrucción parcial: geometría tomada de los buffers de la GPU en la pose actual (sin esqueleto ni animaciones)."),
            Notice(Notice.Severity.WARNING, "capture-mat", "Los materiales son aproximados: sólo se conservan texturas legibles por el navegador; no se recuperan mapas PBR ni parámetros de shader."),
        )
        if (nodes.any { it.scale.any { s -> s != 1f } } || nodes.size > 1) notices.add(Notice(Notice.Severity.INFO, "capture-xform", "Las partes pueden estar en espacios locales distintos; sólo se aplicó la matriz de modelo cuando se identificó por nombre de uniform."))
        if (root["truncated"]?.jsonPrimitive?.booleanOrNull == true) notices.add(Notice(Notice.Severity.WARNING, "capture-trunc", "La captura alcanzó el límite de memoria: puede faltar geometría."))
        if (skipped.isNotEmpty()) notices.add(Notice(Notice.Severity.INFO, "capture-skip", "${skipped.size} dibujo(s) omitidos (pases auxiliares, líneas, pantalla completa o datos ilegibles)."))
        val scene = Scene3D(name, ModelFormat.UNKNOWN, nodes, nodes.indices.toList(), meshes, materials, textures, images, importNotices = notices)
        return CaptureReconstruction(scene, draws.size, used, skipped)
    }

    private fun parseAttr(o: JsonObject?): Attr? {
        o ?: return null
        return Attr(
            ((o["name"] as? JsonPrimitive)?.contentOrNull ?: "").lowercase(), (o["loc"] as? JsonPrimitive)?.intOrNull ?: -1,
            (o["size"] as? JsonPrimitive)?.intOrNull ?: return null, (o["type"] as? JsonPrimitive)?.intOrNull ?: return null,
            (o["norm"] as? JsonPrimitive)?.booleanOrNull ?: false, (o["stride"] as? JsonPrimitive)?.intOrNull ?: 0,
            (o["offset"] as? JsonPrimitive)?.intOrNull ?: 0, (o["buffer"] as? JsonPrimitive)?.contentOrNull ?: return null,
        )
    }

    /** Asigna roles por nombre del atributo y, si no hay nombres útiles, por forma (tamaño/tipo). */
    private fun assignRoles(attrs: List<Attr>): Map<String, Attr> {
        val r = HashMap<String, Attr>()
        for (a in attrs) {
            val n = a.name
            val role = when {
                Regex("pos|vertex|vert(?!ex_?color)").containsMatchIn(n) && !n.contains("normal") && r["position"] == null -> "position"
                n.contains("normal") || n.contains("nrm") -> "normal"
                n.contains("uv") || n.contains("texcoord") || n.contains("tex_coord") || n.contains("st") && n.length <= 4 -> if (r["uv"] == null) "uv" else "uv1"
                n.contains("color") || n.contains("colour") || n == "a_col" -> "color"
                n.contains("joint") || n.contains("bone") || n.contains("skinindex") -> "joints"
                n.contains("weight") -> "weights"
                else -> null
            }
            if (role != null && r[role] == null) r[role] = a
        }
        if (r["position"] == null) {
            attrs.firstOrNull { it.type == 5126 && it.size >= 3 && it !in r.values }?.let { r["position"] = it }
        }
        if (r["normal"] == null && r["position"] != null) attrs.firstOrNull { it !in r.values && it.size == 3 && (it.type == 5126 || it.norm) }?.let { if (it.name.isEmpty() || it.name.length < 3 || true) r["normal"] = it }
        if (r["uv"] == null) attrs.firstOrNull { it !in r.values && it.size == 2 }?.let { r["uv"] = it }
        return r
    }

    private fun comp(bb: ByteBuffer, pos: Int, type: Int, norm: Boolean): Float = when (type) {
        5126 -> bb.getFloat(pos)
        5120 -> bb.get(pos).toInt().let { if (norm) maxOf(it / 127f, -1f) else it.toFloat() }
        5121 -> (bb.get(pos).toInt() and 0xFF).let { if (norm) it / 255f else it.toFloat() }
        5122 -> bb.getShort(pos).toInt().let { if (norm) maxOf(it / 32767f, -1f) else it.toFloat() }
        5123 -> (bb.getShort(pos).toInt() and 0xFFFF).let { if (norm) it / 65535f else it.toFloat() }
        else -> throw IllegalStateException("tipo de atributo $type no soportado")
    }

    private fun typeSize(t: Int) = when (t) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5126, 5125 -> 4; else -> throw IllegalStateException("tipo $t") }

    private fun readAttr(a: Attr, buffers: Map<String, Buf>, v: Int, comps: Int): FloatArray {
        val buf = buffers[a.buffer]?.data ?: throw IllegalStateException("buffer ${a.buffer} no capturado")
        val ts = typeSize(a.type)
        val stride = if (a.stride > 0) a.stride else a.size * ts
        val out = FloatArray(comps)
        val base = a.offset + v * stride
        if (base + a.size * ts > buf.limit()) throw IllegalStateException("atributo fuera del buffer capturado")
        for (c in 0 until minOf(comps, a.size)) out[c] = comp(buf, base + c * ts, a.type, a.norm)
        return out
    }

    private fun buildPrimitive(o: JsonObject, mode: Int, attrs: List<Attr>, roles: Map<String, Attr>, buffers: Map<String, Buf>): Primitive3D? {
        val count = (o["count"] as? JsonPrimitive)?.intOrNull ?: return null
        val first = (o["first"] as? JsonPrimitive)?.intOrNull ?: 0
        val indexType = (o["indexType"] as? JsonPrimitive)?.intOrNull
        val raw = IntArray(count)
        if (indexType != null) {
            val ebId = (o["elementBuffer"] as? JsonPrimitive)?.contentOrNull ?: throw IllegalStateException("sin buffer de índices")
            val eb = buffers[ebId]?.data ?: throw IllegalStateException("buffer de índices no capturado")
            val off = (o["indexOffset"] as? JsonPrimitive)?.intOrNull ?: 0
            val ts = typeSize(indexType)
            if (off + count.toLong() * ts > eb.limit()) throw IllegalStateException("índices fuera del buffer")
            for (i in 0 until count) raw[i] = when (indexType) { 5121 -> eb.get(off + i).toInt() and 0xFF; 5123 -> eb.getShort(off + i * 2).toInt() and 0xFFFF; 5125 -> eb.getInt(off + i * 4); else -> throw IllegalStateException("tipo de índice") }
        } else for (i in 0 until count) raw[i] = first + i
        val tri = when (mode) {
            TRIANGLES -> raw.copyOf(raw.size - raw.size % 3)
            TRIANGLE_STRIP -> { val l = ArrayList<Int>(); for (i in 0 until raw.size - 2) { if (raw[i] == raw[i + 1] || raw[i + 1] == raw[i + 2] || raw[i] == raw[i + 2]) continue; if (i % 2 == 0) { l.add(raw[i]); l.add(raw[i + 1]); l.add(raw[i + 2]) } else { l.add(raw[i + 1]); l.add(raw[i]); l.add(raw[i + 2]) } }; l.toIntArray() }
            else -> { val l = ArrayList<Int>(); for (i in 1 until raw.size - 1) { l.add(raw[0]); l.add(raw[i]); l.add(raw[i + 1]) }; l.toIntArray() }
        }
        if (tri.size < 12) return null // menos de 4 triángulos
        // compactar vértices usados
        val remap = HashMap<Int, Int>()
        val order = ArrayList<Int>()
        val idx = IntArray(tri.size)
        for (i in tri.indices) {
            idx[i] = remap.getOrPut(tri[i]) { order.add(tri[i]); order.size - 1 }
            if (order.size > MAX_VERTICES) throw IllegalStateException("demasiados vértices")
        }
        val pa = roles["position"]!!
        val pos = FloatArray(order.size * 3)
        for ((k, v) in order.withIndex()) { val p = readAttr(pa, buffers, v, 3); pos[k * 3] = p[0]; pos[k * 3 + 1] = p[1]; pos[k * 3 + 2] = p[2] }
        val na = roles["normal"]
        var nrm: FloatArray? = null
        if (na != null && na.size == 3) {
            nrm = FloatArray(order.size * 3)
            for ((k, v) in order.withIndex()) {
                val n = readAttr(na, buffers, v, 3)
                val l = kotlin.math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
                if (l > 1e-6f) { nrm[k * 3] = n[0] / l; nrm[k * 3 + 1] = n[1] / l; nrm[k * 3 + 2] = n[2] / l } else nrm[k * 3 + 1] = 1f
            }
            // normales mal identificadas (p. ej. valores fuera de rango): se recalculan
            if (nrm.any { it.isNaN() }) nrm = null
        }
        val ua = roles["uv"]
        var uv: FloatArray? = null
        if (ua != null && ua.size >= 2) {
            uv = FloatArray(order.size * 2)
            for ((k, v) in order.withIndex()) { val t = readAttr(ua, buffers, v, 2); uv[k * 2] = t[0]; uv[k * 2 + 1] = t[1] }
            // WebGL suele cargar texturas con o sin FLIP_Y; sin certeza se conservan las UV tal cual
        }
        val ca = roles["color"]
        var col: FloatArray? = null
        if (ca != null && ca.size >= 3) {
            col = FloatArray(order.size * 4)
            for ((k, v) in order.withIndex()) { val c = readAttr(ca, buffers, v, 4); col[k * 4] = c[0]; col[k * 4 + 1] = c[1]; col[k * 4 + 2] = c[2]; col[k * 4 + 3] = if (ca.size >= 4) c[3] else 1f }
        }
        if (nrm == null) nrm = ObjReader.computeFlatSafeNormals(pos, idx)
        if (pos.any { it.isNaN() || it.isInfinite() }) throw IllegalStateException("posiciones inválidas")
        return Primitive3D(pos, idx, nrm, uv, null, col)
    }

    private fun isScreenQuad(p: Primitive3D): Boolean {
        if (p.triangleCount > 2) return false
        val b = p.bounds()
        return b.minX >= -1.001f && b.maxX <= 1.001f && b.minY >= -1.001f && b.maxY <= 1.001f && b.sizeZ < 1e-4f
    }

    private fun modelMatrix(o: JsonObject): app.universal3d.core.math.Mat4? {
        val ups = o["uniforms"] as? JsonArray ?: return null
        for (u in ups) {
            val uo = u as? JsonObject ?: continue
            val n = (uo["name"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: continue
            if (!(n.contains("model") && !n.contains("view")) && !n.contains("world") && !n.contains("nodematrix") && !n.contains("node_matrix")) continue
            if (n.contains("viewproj") || n.contains("mvp") || n.contains("projection")) continue
            val v = (uo["value"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull } ?: continue
            if (v.size == 16 && v.none { it.isNaN() || it.isInfinite() }) return app.universal3d.core.math.Mat4(v.toFloatArray())
        }
        return null
    }
}
