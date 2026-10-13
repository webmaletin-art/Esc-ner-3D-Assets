package app.universal3d.core.io

import app.universal3d.core.model.*

/** Importador de Wavefront OBJ + MTL. */
object ObjReader {
    private class MtlEntry(
        var name: String,
        var kd: FloatArray = floatArrayOf(0.8f, 0.8f, 0.8f),
        var ke: FloatArray = floatArrayOf(0f, 0f, 0f),
        var ns: Float? = null,
        var d: Float = 1f,
        var mapKd: String? = null, var mapKe: String? = null, var mapBump: String? = null, var mapD: String? = null,
        var mapPr: String? = null, var mapPm: String? = null, var mapAo: String? = null,
        var pr: Float? = null, var pm: Float? = null, var bumpScale: Float = 1f,
    )

    fun read(bytes: ByteArray, name: String, bundle: FileBundle?, mainPath: String?): Scene3D {
        val notices = ArrayList<Notice>()
        val missing = ArrayList<String>()
        val text = String(bytes, Charsets.UTF_8)

        val pos = FloatList(); val nrm = FloatList(); val tex = FloatList()
        val mtlLibs = ArrayList<String>()
        class Group(val name: String, val material: String?)
        // Cada grupo => primitiva por (objeto, material)
        class Face(val v: IntArray, val t: IntArray, val n: IntArray)
        class Bucket(val objName: String, val material: String?, val faces: ArrayList<Face> = ArrayList())
        val buckets = LinkedHashMap<String, Bucket>()
        var curObj = name
        var curMat: String? = null
        var faceCount = 0
        var lineNo = 0

        for (raw in text.lineSequence()) {
            lineNo++
            var line = raw
            val hash = line.indexOf('#')
            if (hash >= 0) line = line.substring(0, hash)
            line = line.trim()
            if (line.isEmpty()) continue
            // continuación con '\'
            val parts = line.split(WS)
            when (parts[0]) {
                "v" -> { if (parts.size < 4) throw malformed(lineNo, "vértice incompleto"); pos.add(num(parts[1], lineNo)); pos.add(num(parts[2], lineNo)); pos.add(num(parts[3], lineNo)) }
                "vn" -> { if (parts.size < 4) throw malformed(lineNo, "normal incompleta"); nrm.add(num(parts[1], lineNo)); nrm.add(num(parts[2], lineNo)); nrm.add(num(parts[3], lineNo)) }
                "vt" -> { if (parts.size < 3) throw malformed(lineNo, "UV incompleta"); tex.add(num(parts[1], lineNo)); tex.add(num(parts[2], lineNo)) }
                "o", "g" -> { if (parts.size > 1) curObj = line.substring(parts[0].length).trim() }
                "usemtl" -> curMat = line.substring(6).trim()
                "mtllib" -> mtlLibs.add(line.substring(6).trim())
                "f" -> {
                    if (parts.size < 4) { notices.add(Notice(Notice.Severity.WARNING, "face", "Línea $lineNo: cara con menos de 3 vértices, omitida.")); continue }
                    val n = parts.size - 1
                    val v = IntArray(n); val t = IntArray(n); val nn = IntArray(n)
                    for (k in 0 until n) {
                        val c = parts[k + 1].split('/')
                        v[k] = resolve(c[0], pos.size / 3, lineNo)
                        t[k] = if (c.size > 1 && c[1].isNotEmpty()) resolve(c[1], tex.size / 2, lineNo) else -1
                        nn[k] = if (c.size > 2 && c[2].isNotEmpty()) resolve(c[2], nrm.size / 3, lineNo) else -1
                    }
                    val key = "$curObj\u0000${curMat ?: ""}"
                    buckets.getOrPut(key) { Bucket(curObj, curMat) }.faces.add(Face(v, t, nn))
                    faceCount++
                }
                "l", "p" -> notices.add(Notice(Notice.Severity.LOSS, "line", "Línea $lineNo: elementos de línea/punto no soportados."))
                "s", "vp", "usemap", "maplib", "shadow_obj", "trace_obj", "cstype", "curv", "surf", "deg", "parm", "end" -> {}
                else -> {}
            }
        }
        if (pos.size == 0 || faceCount == 0) throw ModelImportException(ModelImportException.Code.EMPTY, "El OBJ no contiene geometría (vértices o caras).")

        // ---- Materiales ----
        val mtl = LinkedHashMap<String, MtlEntry>()
        for (lib in mtlLibs) {
            val f = bundle?.find(lib, mainPath)
            if (f == null) { missing.add(lib); continue }
            parseMtl(String(f.read(), Charsets.UTF_8), mtl)
        }
        val images = ArrayList<Image3D>()
        val imageByRef = HashMap<String, Int>()
        val textures = ArrayList<Texture3D>()
        fun texture(ref: String?, base: String?): TextureRef? {
            if (ref == null) return null
            val f = bundle?.find(ref, mainPath)
            if (f == null) { missing.add(ref); return null }
            val key = FileBundle.normalize(f.path)
            val img = imageByRef.getOrPut(key) {
                val b = f.read()
                images.add(Image3D(f.fileName, ImageHeader.sniffMime(b) ?: ImageHeader.mimeForName(f.fileName) ?: "image/png", b, ref))
                images.size - 1
            }
            textures.add(Texture3D(f.fileName, img))
            return TextureRef(textures.size - 1)
        }
        val materials = ArrayList<Material3D>()
        val matIndex = HashMap<String, Int>()
        fun materialIndexFor(n: String?): Int {
            if (n == null) return -1
            matIndex[n]?.let { return it }
            val e = mtl[n]
            if (e == null) {
                if (mtlLibs.isNotEmpty()) notices.add(Notice(Notice.Severity.WARNING, "mtl", "Material '$n' no está definido en los MTL disponibles."))
            }
            val m = if (e == null) Material3D(name = n, baseColorFactor = floatArrayOf(0.8f, 0.8f, 0.8f, 1f), metallicFactor = 0f, roughnessFactor = 0.8f) else {
                val alpha = e.d.coerceIn(0f, 1f)
                val rough = e.pr ?: e.ns?.let { (1f - kotlin.math.sqrt((it / 1000f).coerceIn(0f, 1f))).coerceIn(0.04f, 1f) } ?: 0.8f
                val dTex = texture(e.mapD, null)
                val mrTex = null
                if (e.mapPr != null || e.mapPm != null || e.mapAo != null) {
                    notices.add(Notice(Notice.Severity.LOSS, "pbrmaps", "Material '$n': mapas PBR separados de MTL (Pr/Pm/ao) no se combinan; se conservan solo los valores escalares."))
                }
                Material3D(
                    name = n,
                    baseColorFactor = floatArrayOf(e.kd[0], e.kd[1], e.kd[2], alpha),
                    baseColorTexture = texture(e.mapKd, null),
                    metallicFactor = e.pm ?: 0f, roughnessFactor = rough,
                    metallicRoughnessTexture = mrTex,
                    normalTexture = texture(e.mapBump, null), normalScale = e.bumpScale,
                    emissiveFactor = e.ke, emissiveTexture = texture(e.mapKe, null),
                    alphaMode = if (dTex != null) AlphaMode.BLEND else if (alpha < 1f) AlphaMode.BLEND else AlphaMode.OPAQUE,
                    doubleSided = true,
                ).also { if (e.mapBump != null) notices.add(Notice(Notice.Severity.INFO, "bump", "Material '$n': el mapa de relieve/bump se interpreta como mapa normal; si es de altura, el sombreado puede diferir.")) }
            }
            materials.add(m)
            matIndex[n] = materials.size - 1
            return materials.size - 1
        }

        // ---- Mallas (una por objeto, una primitiva por material) ----
        val meshes = ArrayList<Mesh3D>()
        val nodes = ArrayList<Node3D>()
        val byObj = LinkedHashMap<String, MutableList<Bucket>>()
        for (b in buckets.values) byObj.getOrPut(b.objName) { ArrayList() }.add(b)
        val hasNormals = nrm.size > 0
        val hasUv = tex.size > 0
        var dropped = 0
        for ((objName, bs) in byObj) {
            val prims = ArrayList<Primitive3D>()
            for (b in bs) {
                val vmap = HashMap<Long, Int>()
                val p = FloatList(); val n = FloatList(); val t = FloatList()
                val idx = IntList()
                fun vertex(vi: Int, ti: Int, ni: Int): Int {
                    val key = (vi.toLong() shl 42) xor ((ti + 1).toLong() shl 21) xor (ni + 1).toLong()
                    return vmap.getOrPut(key) {
                        p.add(pos[vi * 3]); p.add(pos[vi * 3 + 1]); p.add(pos[vi * 3 + 2])
                        if (hasNormals) { if (ni >= 0) { n.add(nrm[ni * 3]); n.add(nrm[ni * 3 + 1]); n.add(nrm[ni * 3 + 2]) } else { n.add(0f); n.add(0f); n.add(0f) } }
                        if (hasUv) { if (ti >= 0) { t.add(tex[ti * 2]); t.add(1f - tex[ti * 2 + 1]) } else { t.add(0f); t.add(0f) } }
                        p.size / 3 - 1
                    }
                }
                for (f in b.faces) {
                    val k = f.v.size
                    // Triangulación en abanico; para polígonos cóncavos puede ser inexacta.
                    for (i in 1 until k - 1) {
                        if (f.v[0] == f.v[i] || f.v[i] == f.v[i + 1] || f.v[0] == f.v[i + 1]) { dropped++; continue }
                        idx.add(vertex(f.v[0], f.t[0], f.n[0])); idx.add(vertex(f.v[i], f.t[i], f.n[i])); idx.add(vertex(f.v[i + 1], f.t[i + 1], f.n[i + 1]))
                    }
                }
                if (idx.size == 0) continue
                var normals = if (hasNormals) n.toArray() else null
                if (normals != null) fixZeroNormals(p.toArray(), idx.toArray(), normals)
                if (normals == null) normals = computeFlatSafeNormals(p.toArray(), idx.toArray())
                prims.add(Primitive3D(
                    positions = p.toArray(), indices = idx.toArray(), normals = normals,
                    uv0 = if (hasUv && t.size > 0) t.toArray() else null,
                    materialIndex = materialIndexFor(b.material), name = b.material,
                ))
            }
            if (prims.isEmpty()) continue
            meshes.add(Mesh3D(objName, prims))
            nodes.add(Node3D(objName, meshIndex = meshes.size - 1))
        }
        if (dropped > 0) notices.add(Notice(Notice.Severity.INFO, "degenerate", "$dropped triángulos degenerados descartados."))
        if (hasUv) notices.add(Notice(Notice.Severity.INFO, "uvflip", "Coordenada V invertida (OBJ usa origen inferior; glTF superior)."))
        if (!hasNormals) notices.add(Notice(Notice.Severity.INFO, "normals", "El OBJ no trae normales: se calcularon normales suaves."))
        val roots = nodes.indices.toList()
        return Scene3D(
            name = name, sourceFormat = ModelFormat.OBJ, nodes = nodes, rootNodes = roots, meshes = meshes, materials = materials,
            textures = textures, images = images, importNotices = notices, missingDependencies = missing.distinct(), sourceSizeBytes = bytes.size.toLong(),
        )
    }

    private val WS = Regex("\\s+")

    private fun malformed(line: Int, msg: String) = ModelImportException(ModelImportException.Code.MALFORMED, "OBJ inválido en la línea $line: $msg.")

    private fun num(s: String, line: Int): Float = s.toFloatOrNull() ?: throw malformed(line, "número inválido '$s'")

    private fun resolve(s: String, count: Int, line: Int): Int {
        val i = s.toIntOrNull() ?: throw malformed(line, "índice inválido '$s'")
        val r = if (i > 0) i - 1 else count + i
        if (i == 0 || r < 0 || r >= count) throw malformed(line, "índice $i fuera de rango")
        return r
    }

    private fun parseMtl(text: String, out: MutableMap<String, MtlEntry>) {
        var cur: MtlEntry? = null
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(WS)
            val key = parts[0].lowercase()
            fun rest() = line.substring(parts[0].length).trim()
            fun mapPath(): String? {
                // quitar opciones tipo -bm 1.0 -o ... y devolver el último token (ruta)
                val toks = rest().split(WS).toMutableList()
                var i = 0
                while (i < toks.size && toks[i].startsWith("-")) {
                    i += when (toks[i].lowercase()) {
                        "-bm", "-boost", "-imfchan", "-type", "-clamp", "-blendu", "-blendv", "-cc" -> 2
                        "-mm" -> 3; "-o", "-s", "-t" -> 4; else -> 1
                    }
                }
                return if (i < toks.size) toks.drop(i).joinToString(" ") else null
            }
            when (key) {
                "newmtl" -> { cur = MtlEntry(rest()); out[cur.name] = cur }
                "kd" -> cur?.kd = rgb(parts)
                "ke" -> cur?.ke = rgb(parts)
                "ns" -> cur?.ns = parts.getOrNull(1)?.toFloatOrNull()
                "d" -> cur?.d = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
                "tr" -> cur?.d = 1f - (parts.getOrNull(1)?.toFloatOrNull() ?: 0f)
                "pr" -> cur?.pr = parts.getOrNull(1)?.toFloatOrNull()
                "pm" -> cur?.pm = parts.getOrNull(1)?.toFloatOrNull()
                "map_kd" -> cur?.mapKd = mapPath()
                "map_ke" -> cur?.mapKe = mapPath()
                "map_bump", "bump", "norm", "map_normal" -> cur?.mapBump = mapPath()
                "map_d" -> cur?.mapD = mapPath()
                "map_pr" -> cur?.mapPr = mapPath()
                "map_pm" -> cur?.mapPm = mapPath()
                "map_ao" -> cur?.mapAo = mapPath()
            }
        }
    }

    private fun rgb(p: List<String>): FloatArray {
        val r = p.getOrNull(1)?.toFloatOrNull() ?: 0f
        val g = p.getOrNull(2)?.toFloatOrNull() ?: r
        val b = p.getOrNull(3)?.toFloatOrNull() ?: r
        return floatArrayOf(r, g, b)
    }

    private fun fixZeroNormals(p: FloatArray, idx: IntArray, n: FloatArray) {
        var need = false
        for (i in 0 until n.size / 3) if (n[i * 3] == 0f && n[i * 3 + 1] == 0f && n[i * 3 + 2] == 0f) { need = true; break }
        if (!need) return
        val calc = computeFlatSafeNormals(p, idx)
        for (i in 0 until n.size / 3) if (n[i * 3] == 0f && n[i * 3 + 1] == 0f && n[i * 3 + 2] == 0f) {
            n[i * 3] = calc[i * 3]; n[i * 3 + 1] = calc[i * 3 + 1]; n[i * 3 + 2] = calc[i * 3 + 2]
        }
    }

    /** Normales suaves por vértice (promedio ponderado por área). */
    fun computeFlatSafeNormals(p: FloatArray, idx: IntArray): FloatArray {
        val n = FloatArray(p.size)
        var i = 0
        while (i + 2 < idx.size) {
            val a = idx[i] * 3; val b = idx[i + 1] * 3; val c = idx[i + 2] * 3
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (k in intArrayOf(a, b, c)) { n[k] += nx; n[k + 1] += ny; n[k + 2] += nz }
            i += 3
        }
        for (v in 0 until n.size / 3) {
            val l = kotlin.math.sqrt(n[v * 3] * n[v * 3] + n[v * 3 + 1] * n[v * 3 + 1] + n[v * 3 + 2] * n[v * 3 + 2])
            if (l > 0f) { n[v * 3] /= l; n[v * 3 + 1] /= l; n[v * 3 + 2] /= l } else { n[v * 3 + 1] = 1f }
        }
        return n
    }

    /** Lista de dependencias (mtllib + mapas) declaradas por un OBJ/MTL, sin cargar geometría. */
    fun listDependencies(objText: String, mtlTextProvider: (String) -> String?): List<String> {
        val deps = LinkedHashSet<String>()
        for (raw in objText.lineSequence()) {
            val l = raw.substringBefore('#').trim()
            if (l.startsWith("mtllib ")) {
                val lib = l.substring(7).trim()
                deps.add(lib)
                mtlTextProvider(lib)?.let { t ->
                    for (m in t.lineSequence()) {
                        val ml = m.substringBefore('#').trim()
                        val parts = ml.split(WS)
                        if (parts[0].lowercase().let { it.startsWith("map_") || it == "bump" || it == "norm" } && parts.size > 1) deps.add(parts.last())
                    }
                }
            }
        }
        return deps.toList()
    }
}

class FloatList(cap: Int = 1024) {
    private var a = FloatArray(cap)
    var size = 0; private set
    fun add(v: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun toArray() = a.copyOf(size)
}

class IntList(cap: Int = 1024) {
    private var a = IntArray(cap)
    var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun toArray() = a.copyOf(size)
}
