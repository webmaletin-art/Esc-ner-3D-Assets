package app.universal3d.core.io

import app.universal3d.core.math.Mat4
import app.universal3d.core.model.*
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Importador de glTF 2.0 (.gltf con recursos externos / embebidos y .glb). */
object GltfReader {
    private const val GLB_MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    private val SUPPORTED_REQUIRED = setOf(
        "KHR_materials_emissive_strength", "KHR_mesh_quantization", "KHR_texture_transform",
        "KHR_materials_pbrSpecularGlossiness", "KHR_materials_unlit", "EXT_texture_webp", "KHR_lights_punctual",
    )

    const val MAX_BYTES = 512L * 1024 * 1024

    fun readGlb(bytes: ByteArray, name: String, bundle: FileBundle? = null, mainPath: String? = null): Scene3D {
        if (bytes.size < 20) throw ModelImportException(ModelImportException.Code.MALFORMED, "El archivo GLB es demasiado pequeño para ser válido.")
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != GLB_MAGIC) throw ModelImportException(ModelImportException.Code.MALFORMED, "La cabecera no corresponde a un GLB (falta la firma 'glTF').")
        val version = bb.getInt(4)
        if (version != 2) throw UnsupportedFormatException("Versión GLB $version no soportada (solo glTF 2.0).")
        val total = bb.getInt(8)
        val limit = if (total in 20..bytes.size) total else bytes.size
        var off = 12
        var json: String? = null
        var bin: ByteArray? = null
        while (off + 8 <= limit) {
            val len = bb.getInt(off)
            val type = bb.getInt(off + 4)
            val start = off + 8
            if (len < 0 || start + len > bytes.size) throw ModelImportException(ModelImportException.Code.MALFORMED, "GLB truncado: un chunk excede el tamaño del archivo.")
            when (type) {
                CHUNK_JSON -> if (json == null) json = String(bytes, start, len, Charsets.UTF_8)
                CHUNK_BIN -> if (bin == null) bin = bytes.copyOfRange(start, start + len)
            }
            off = start + ((len + 3) and 3.inv())
        }
        val j = json ?: throw ModelImportException(ModelImportException.Code.MALFORMED, "GLB sin chunk JSON.")
        return Parser(j, bin, name, ModelFormat.GLB, bundle, mainPath, bytes.size.toLong()).parse()
    }

    fun readGltf(json: String, name: String, bundle: FileBundle?, mainPath: String?, size: Long = json.length.toLong()): Scene3D =
        Parser(json, null, name, ModelFormat.GLTF, bundle, mainPath, size).parse()

    /** Lista las dependencias externas (uris) de un .gltf sin cargarlas. */
    fun listExternalUris(json: String): List<String> {
        val root = try { Json.parseToJsonElement(json).jsonObject } catch (e: Exception) { return emptyList() }
        val out = ArrayList<String>()
        for (k in listOf("buffers", "images")) {
            root[k]?.jsonArray?.forEach { e ->
                val uri = e.jsonObject["uri"]?.jsonPrimitive?.contentOrNull
                if (uri != null && !uri.startsWith("data:")) out.add(uri)
            }
        }
        return out
    }

    private class Parser(
        val jsonText: String, val glbBin: ByteArray?, val name: String, val format: ModelFormat,
        val bundle: FileBundle?, val mainPath: String?, val size: Long,
    ) {
        val notices = ArrayList<Notice>()
        val missing = ArrayList<String>()
        lateinit var root: JsonObject
        val buffers = ArrayList<ByteArray?>()

        fun parse(): Scene3D {
            root = try {
                Json.parseToJsonElement(jsonText).jsonObject
            } catch (e: Exception) {
                throw ModelImportException(ModelImportException.Code.MALFORMED, "El JSON de glTF es inválido: ${e.message?.take(120)}", e)
            }
            val asset = root["asset"]?.jsonObject ?: throw ModelImportException(ModelImportException.Code.MALFORMED, "Falta el objeto 'asset' de glTF.")
            val ver = asset["version"]?.jsonPrimitive?.contentOrNull ?: ""
            if (!ver.startsWith("2")) throw UnsupportedFormatException("Versión de glTF '$ver' no soportada (solo 2.x).")
            checkExtensions()
            loadBuffers()
            val images = loadImages()
            val textures = loadTextures(images.size)
            val materials = loadMaterials()
            val meshes = loadMeshes(materials.size)
            val nodes = loadNodes()
            val skins = loadSkins()
            val animations = loadAnimations()
            val roots = pickRoots(nodes.size)
            if (root["cameras"] != null) notices.add(Notice(Notice.Severity.INFO, "cameras", "Las cámaras definidas en el archivo se omiten (el visor usa su propia cámara)."))
            if (root["extensions"]?.jsonObject?.containsKey("KHR_lights_punctual") == true) notices.add(Notice(Notice.Severity.INFO, "lights", "Las luces del archivo se omiten; el visor usa iluminación propia."))
            if (meshes.isEmpty()) throw ModelImportException(ModelImportException.Code.EMPTY, "El archivo no contiene mallas.")
            return Scene3D(
                name = name, sourceFormat = format, nodes = nodes, rootNodes = roots, meshes = meshes, materials = materials,
                textures = textures, images = images, skins = skins, animations = animations,
                importNotices = notices, missingDependencies = missing.distinct(), sourceSizeBytes = size,
            )
        }

        fun checkExtensions() {
            val req = root["extensionsRequired"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val unsupported = req.filter { it !in SUPPORTED_REQUIRED }
            if (unsupported.isNotEmpty()) {
                val hint = when {
                    unsupported.any { it == "KHR_draco_mesh_compression" } -> " (compresión Draco: el motor actual no incluye decodificador Draco)"
                    unsupported.any { it == "EXT_meshopt_compression" } -> " (compresión meshopt no soportada)"
                    unsupported.any { it == "KHR_texture_basisu" } -> " (texturas KTX2/Basis no soportadas)"
                    else -> ""
                }
                throw UnsupportedFormatException("El modelo requiere extensiones glTF no soportadas por el motor actual: ${unsupported.joinToString()}$hint.")
            }
            val used = root["extensionsUsed"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            for (u in used) {
                when (u) {
                    "KHR_texture_transform" -> notices.add(Notice(Notice.Severity.WARNING, u, "KHR_texture_transform se ignora: las UV se muestran sin transformación de textura."))
                    "KHR_materials_unlit" -> notices.add(Notice(Notice.Severity.WARNING, u, "KHR_materials_unlit no se aplica: el material se sombreará con iluminación estándar."))
                    "KHR_draco_mesh_compression", "EXT_meshopt_compression" ->
                        notices.add(Notice(Notice.Severity.WARNING, u, "Se declara $u como extensión opcional; se usan los datos sin comprimir si existen."))
                    "KHR_materials_clearcoat", "KHR_materials_transmission", "KHR_materials_volume", "KHR_materials_sheen", "KHR_materials_specular",
                    "KHR_materials_ior", "KHR_materials_iridescence", "KHR_materials_anisotropy" ->
                        notices.add(Notice(Notice.Severity.LOSS, u, "La extensión de material $u no está soportada y se pierde (se conserva el material PBR base)."))
                }
            }
        }

        fun loadBuffers() {
            val arr = root["buffers"]?.jsonArray ?: JsonArray(emptyList())
            for ((i, b) in arr.withIndex()) {
                val o = b.jsonObject
                val uri = o["uri"]?.jsonPrimitive?.contentOrNull
                val declared = o["byteLength"]?.jsonPrimitive?.longOrNull ?: -1
                val data: ByteArray? = when {
                    uri == null -> if (i == 0 && glbBin != null) glbBin else {
                        notices.add(Notice(Notice.Severity.WARNING, "buffer", "El buffer $i no tiene datos."))
                        null
                    }
                    uri.startsWith("data:") -> decodeDataUri(uri)
                    else -> {
                        val f = bundle?.find(uri, mainPath)
                        if (f == null) { missing.add(uri); null } else f.read()
                    }
                }
                if (data != null && declared >= 0 && data.size < declared) {
                    throw ModelImportException(ModelImportException.Code.INCOMPLETE, "El buffer '${uri ?: i}' está incompleto (${data.size} de $declared bytes).")
                }
                buffers.add(data)
            }
        }

        fun decodeDataUri(uri: String): ByteArray? {
            val comma = uri.indexOf(',')
            if (comma < 0) return null
            val meta = uri.substring(5, comma)
            val payload = uri.substring(comma + 1)
            return try {
                if (meta.endsWith(";base64")) Base64.getMimeDecoder().decode(payload)
                else java.net.URLDecoder.decode(payload, "ISO-8859-1").toByteArray(Charsets.ISO_8859_1)
            } catch (_: Exception) { null }
        }

        fun bufferViewBytes(index: Int): Triple<ByteArray, Int, Int>? {
            val bv = root["bufferViews"]?.jsonArray?.getOrNull(index)?.jsonObject ?: return null
            val buf = bv["buffer"]?.jsonPrimitive?.intOrNull ?: return null
            val data = buffers.getOrNull(buf) ?: return null
            val off = bv["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0
            val len = bv["byteLength"]?.jsonPrimitive?.intOrNull ?: 0
            if (off < 0 || len < 0 || off + len > data.size) throw ModelImportException(ModelImportException.Code.INCOMPLETE, "bufferView $index excede el buffer (datos truncados).")
            return Triple(data, off, len)
        }

        fun loadImages(): List<Image3D> {
            val arr = root["images"]?.jsonArray ?: return emptyList()
            return arr.mapIndexed { i, e ->
                val o = e.jsonObject
                val uri = o["uri"]?.jsonPrimitive?.contentOrNull
                val iname = o["name"]?.jsonPrimitive?.contentOrNull
                var mime = o["mimeType"]?.jsonPrimitive?.contentOrNull
                var bytes: ByteArray? = null
                if (uri != null) {
                    if (uri.startsWith("data:")) {
                        bytes = decodeDataUri(uri)
                        mime = mime ?: uri.substring(5).substringBefore(';').substringBefore(',')
                    } else {
                        val f = bundle?.find(uri, mainPath)
                        if (f == null) missing.add(uri) else bytes = f.read()
                        mime = mime ?: ImageHeader.mimeForName(uri)
                    }
                } else {
                    val bvIdx = o["bufferView"]?.jsonPrimitive?.intOrNull
                    if (bvIdx != null) {
                        bufferViewBytes(bvIdx)?.let { (d, off, len) -> bytes = d.copyOfRange(off, off + len) }
                    }
                }
                val b = bytes
                if (b == null) {
                    // Imagen faltante: se reemplaza por un PNG 1x1 gris para mantener los índices.
                    Image3D(iname ?: "missing_$i", "image/png", PLACEHOLDER_PNG, uri)
                } else {
                    Image3D(iname ?: uri?.substringAfterLast('/'), ImageHeader.sniffMime(b) ?: mime ?: "image/png", b, uri?.takeIf { !it.startsWith("data:") })
                }
            }
        }

        fun loadTextures(imageCount: Int): List<Texture3D> {
            val samplers = root["samplers"]?.jsonArray
            val arr = root["textures"]?.jsonArray ?: return emptyList()
            return arr.map { e ->
                val o = e.jsonObject
                val ext = o["extensions"]?.jsonObject
                val src = o["source"]?.jsonPrimitive?.intOrNull
                    ?: ext?.get("EXT_texture_webp")?.jsonObject?.get("source")?.jsonPrimitive?.intOrNull ?: -1
                val s = o["sampler"]?.jsonPrimitive?.intOrNull?.let { samplers?.getOrNull(it)?.jsonObject }
                val sampler = if (s == null) Sampler3D() else Sampler3D(
                    s["magFilter"]?.jsonPrimitive?.intOrNull, s["minFilter"]?.jsonPrimitive?.intOrNull,
                    s["wrapS"]?.jsonPrimitive?.intOrNull ?: 10497, s["wrapT"]?.jsonPrimitive?.intOrNull ?: 10497,
                )
                Texture3D(o["name"]?.jsonPrimitive?.contentOrNull, if (src in 0 until imageCount) src else -1, sampler)
            }
        }

        fun texRef(o: JsonObject?): TextureRef? {
            o ?: return null
            val idx = o["index"]?.jsonPrimitive?.intOrNull ?: return null
            return TextureRef(idx, o["texCoord"]?.jsonPrimitive?.intOrNull ?: 0)
        }

        fun floats(a: JsonElement?, n: Int, def: FloatArray): FloatArray {
            val arr = a as? JsonArray ?: return def
            if (arr.size < n) return def
            return FloatArray(n) { arr[it].jsonPrimitive.float }
        }

        fun loadMaterials(): List<Material3D> {
            val arr = root["materials"]?.jsonArray ?: return emptyList()
            return arr.map { e ->
                val o = e.jsonObject
                val pbr = o["pbrMetallicRoughness"]?.jsonObject
                val ext = o["extensions"]?.jsonObject
                val sg = ext?.get("KHR_materials_pbrSpecularGlossiness")?.jsonObject
                var base = floats(pbr?.get("baseColorFactor"), 4, floatArrayOf(1f, 1f, 1f, 1f))
                var baseTex = texRef(pbr?.get("baseColorTexture")?.jsonObject)
                var metallic = pbr?.get("metallicFactor")?.jsonPrimitive?.floatOrNull ?: 1f
                var rough = pbr?.get("roughnessFactor")?.jsonPrimitive?.floatOrNull ?: 1f
                if (sg != null && pbr == null) {
                    base = floats(sg["diffuseFactor"], 4, base)
                    baseTex = texRef(sg["diffuseTexture"]?.jsonObject)
                    metallic = 0f
                    rough = 1f - (sg["glossinessFactor"]?.jsonPrimitive?.floatOrNull ?: 1f)
                    notices.add(Notice(Notice.Severity.WARNING, "pbrSpecularGlossiness", "Material '${o["name"]?.jsonPrimitive?.contentOrNull}' usa specular-glossiness: se aproximó a metal-rugosidad."))
                }
                var emissive = floats(o["emissiveFactor"], 3, floatArrayOf(0f, 0f, 0f))
                val strength = ext?.get("KHR_materials_emissive_strength")?.jsonObject?.get("emissiveStrength")?.jsonPrimitive?.floatOrNull
                if (strength != null) emissive = floatArrayOf(emissive[0] * strength, emissive[1] * strength, emissive[2] * strength)
                val normalObj = o["normalTexture"]?.jsonObject
                val occObj = o["occlusionTexture"]?.jsonObject
                Material3D(
                    name = o["name"]?.jsonPrimitive?.contentOrNull,
                    baseColorFactor = base, baseColorTexture = baseTex,
                    metallicFactor = metallic, roughnessFactor = rough,
                    metallicRoughnessTexture = texRef(pbr?.get("metallicRoughnessTexture")?.jsonObject),
                    normalTexture = texRef(normalObj), normalScale = normalObj?.get("scale")?.jsonPrimitive?.floatOrNull ?: 1f,
                    occlusionTexture = texRef(occObj), occlusionStrength = occObj?.get("strength")?.jsonPrimitive?.floatOrNull ?: 1f,
                    emissiveFactor = emissive, emissiveTexture = texRef(o["emissiveTexture"]?.jsonObject),
                    alphaMode = when (o["alphaMode"]?.jsonPrimitive?.contentOrNull) { "MASK" -> AlphaMode.MASK; "BLEND" -> AlphaMode.BLEND; else -> AlphaMode.OPAQUE },
                    alphaCutoff = o["alphaCutoff"]?.jsonPrimitive?.floatOrNull ?: 0.5f,
                    doubleSided = o["doubleSided"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
        }

        // ---- Accesores ----
        class Acc(val count: Int, val comps: Int, val data: FloatArray)

        private fun compCount(type: String) = when (type) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT2" -> 4; "MAT3" -> 9; "MAT4" -> 16; else -> 0 }
        private fun compSize(ct: Int) = when (ct) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> 0 }

        /** Lee un accesor como doubles-en-floats; los enteros sin normalizar se devuelven como valor entero en float (usar [readInts] para exactitud). */
        fun readAccessor(index: Int): Acc? {
            val a = root["accessors"]?.jsonArray?.getOrNull(index)?.jsonObject ?: return null
            val ct = a["componentType"]?.jsonPrimitive?.intOrNull ?: return null
            val count = a["count"]?.jsonPrimitive?.intOrNull ?: return null
            val comps = compCount(a["type"]?.jsonPrimitive?.contentOrNull ?: "")
            val cs = compSize(ct)
            if (comps == 0 || cs == 0) return null
            val normalized = a["normalized"]?.jsonPrimitive?.booleanOrNull ?: false
            val out = FloatArray(count * comps)
            val bvIdx = a["bufferView"]?.jsonPrimitive?.intOrNull
            if (bvIdx != null) {
                val (data, bvOff, bvLen) = bufferViewBytes(bvIdx) ?: run {
                    return null
                }
                val stride0 = root["bufferViews"]!!.jsonArray[bvIdx].jsonObject["byteStride"]?.jsonPrimitive?.intOrNull ?: 0
                val elemSize = comps * cs
                val stride = if (stride0 > 0) stride0 else elemSize
                val accOff = a["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0
                if (count > 0 && accOff + (count - 1).toLong() * stride + elemSize > bvLen) {
                    throw ModelImportException(ModelImportException.Code.INCOMPLETE, "El accesor $index excede su bufferView (datos truncados).")
                }
                val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) {
                    var p = bvOff + accOff + i * stride
                    for (c in 0 until comps) {
                        // matrices con relleno de columnas (MAT2 byte, MAT3 byte/short) no se tratan: caso raro
                        out[i * comps + c] = readComp(bb, p, ct, normalized)
                        p += cs
                    }
                }
            }
            a["sparse"]?.jsonObject?.let { applySparse(it, out, comps, normalized, ct) }
            return Acc(count, comps, out)
        }

        fun readComp(bb: ByteBuffer, p: Int, ct: Int, normalized: Boolean): Float = when (ct) {
            5126 -> bb.getFloat(p)
            5121 -> (bb.get(p).toInt() and 0xFF).let { if (normalized) it / 255f else it.toFloat() }
            5120 -> bb.get(p).toInt().let { if (normalized) maxOf(it / 127f, -1f) else it.toFloat() }
            5123 -> (bb.getShort(p).toInt() and 0xFFFF).let { if (normalized) it / 65535f else it.toFloat() }
            5122 -> bb.getShort(p).toInt().let { if (normalized) maxOf(it / 32767f, -1f) else it.toFloat() }
            5125 -> (bb.getInt(p).toLong() and 0xFFFFFFFFL).toFloat()
            else -> 0f
        }

        fun applySparse(s: JsonObject, out: FloatArray, comps: Int, normalized: Boolean, ct: Int) {
            val count = s["count"]?.jsonPrimitive?.intOrNull ?: return
            val ind = s["indices"]?.jsonObject ?: return
            val vals = s["values"]?.jsonObject ?: return
            val (idata, ioff, _) = bufferViewBytes(ind["bufferView"]!!.jsonPrimitive.int) ?: return
            val (vdata, voff, _) = bufferViewBytes(vals["bufferView"]!!.jsonPrimitive.int) ?: return
            val ictype = ind["componentType"]!!.jsonPrimitive.int
            val ib = ByteBuffer.wrap(idata).order(ByteOrder.LITTLE_ENDIAN)
            val vb = ByteBuffer.wrap(vdata).order(ByteOrder.LITTLE_ENDIAN)
            val io = ioff + (ind["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0)
            val vo = voff + (vals["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0)
            val isz = compSize(ictype); val vsz = compSize(ct)
            for (k in 0 until count) {
                val idx = readComp(ib, io + k * isz, ictype, false).toLong().toInt()
                if (idx < 0 || (idx + 1) * comps > out.size) continue
                for (c in 0 until comps) out[idx * comps + c] = readComp(vb, vo + (k * comps + c) * vsz, ct, normalized)
            }
        }

        fun readInts(index: Int): IntArray? {
            val acc = readAccessor(index) ?: return null
            return IntArray(acc.data.size) { acc.data[it].toLong().toInt() }
        }

        /** Índices uint32 sin pérdida de precisión (float no representa >2^24 exactamente). */
        fun readIndices(index: Int): IntArray? {
            val a = root["accessors"]?.jsonArray?.getOrNull(index)?.jsonObject ?: return null
            val ct = a["componentType"]?.jsonPrimitive?.intOrNull ?: return null
            if (ct != 5125 || a["sparse"] != null) return readInts(index)
            val count = a["count"]?.jsonPrimitive?.intOrNull ?: return null
            val bvIdx = a["bufferView"]?.jsonPrimitive?.intOrNull ?: return IntArray(count)
            val (data, bvOff, bvLen) = bufferViewBytes(bvIdx) ?: return null
            val stride0 = root["bufferViews"]!!.jsonArray[bvIdx].jsonObject["byteStride"]?.jsonPrimitive?.intOrNull ?: 0
            val stride = if (stride0 > 0) stride0 else 4
            val accOff = a["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0
            if (count > 0 && accOff + (count - 1).toLong() * stride + 4 > bvLen) throw ModelImportException(ModelImportException.Code.INCOMPLETE, "Índices truncados.")
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            return IntArray(count) { bb.getInt(bvOff + accOff + it * stride) }
        }

        // ---- Mallas ----
        fun loadMeshes(materialCount: Int): List<Mesh3D> {
            val arr = root["meshes"]?.jsonArray ?: return emptyList()
            return arr.mapIndexed { mi, e ->
                val o = e.jsonObject
                val prims = ArrayList<Primitive3D>()
                for ((pi, pe) in (o["primitives"]?.jsonArray ?: JsonArray(emptyList())).withIndex()) {
                    val p = pe.jsonObject
                    val attrs = p["attributes"]?.jsonObject ?: continue
                    val mode = p["mode"]?.jsonPrimitive?.intOrNull ?: 4
                    if (mode < 4) {
                        notices.add(Notice(Notice.Severity.LOSS, "mode", "La primitiva $pi de la malla '${o["name"]?.jsonPrimitive?.contentOrNull ?: mi}' usa puntos/líneas (modo $mode): no se importa."))
                        continue
                    }
                    if (p["extensions"]?.jsonObject?.containsKey("KHR_draco_mesh_compression") == true && attrs["POSITION"] == null) continue
                    val posIdx = attrs["POSITION"]?.jsonPrimitive?.intOrNull ?: continue
                    val pos = readAccessor(posIdx) ?: run {
                        notices.add(Notice(Notice.Severity.WARNING, "pos", "No se pudieron leer los vértices de la primitiva $pi (malla $mi)."))
                        null
                    } ?: continue
                    val vc = pos.count
                    fun attr(name: String, comps: Int): FloatArray? {
                        val idx = attrs[name]?.jsonPrimitive?.intOrNull ?: return null
                        val acc = readAccessor(idx) ?: return null
                        if (acc.count != vc) return null
                        return when {
                            acc.comps == comps -> acc.data
                            name.startsWith("COLOR") && acc.comps == 3 -> FloatArray(vc * 4) { k -> if (k % 4 == 3) 1f else acc.data[(k / 4) * 3 + k % 4] }
                            else -> null
                        }
                    }
                    val raw = if (p["indices"] != null) readIndices(p["indices"]!!.jsonPrimitive.int) else IntArray(vc) { it }
                    if (raw == null) {
                        notices.add(Notice(Notice.Severity.WARNING, "idx", "Índices ilegibles en la primitiva $pi (malla $mi); se omite."))
                        continue
                    }
                    val tri = toTriangles(raw, mode)
                    val joints = attrs["JOINTS_0"]?.jsonPrimitive?.intOrNull?.let { readInts(it) }?.takeIf { it.size == vc * 4 }
                    val weights = attr("WEIGHTS_0", 4)
                    if (p["targets"] != null) notices.add(Notice(Notice.Severity.LOSS, "morph", "La malla '${o["name"]?.jsonPrimitive?.contentOrNull ?: mi}' tiene morph targets: no soportados, se importa la forma base."))
                    var normals = attr("NORMAL", 3)
                    val prim = Primitive3D(
                        positions = pos.data, indices = tri, normals = normals, uv0 = attr("TEXCOORD_0", 2), uv1 = attr("TEXCOORD_1", 2),
                        colors = attr("COLOR_0", 4), joints = if (weights != null) joints else null, weights = if (joints != null) weights else null,
                        materialIndex = (p["material"]?.jsonPrimitive?.intOrNull ?: -1).let { if (it in 0 until materialCount) it else -1 },
                        name = null,
                    )
                    prim.validate()?.let { err ->
                        throw ModelImportException(ModelImportException.Code.MALFORMED, "Primitiva $pi de la malla $mi inválida: $err")
                    }
                    prims.add(prim)
                }
                Mesh3D(o["name"]?.jsonPrimitive?.contentOrNull, prims)
            }
        }

        fun toTriangles(idx: IntArray, mode: Int): IntArray = when (mode) {
            4 -> if (idx.size % 3 == 0) idx else idx.copyOf(idx.size - idx.size % 3)
            5 -> {
                val out = ArrayList<Int>()
                for (i in 0 until idx.size - 2) {
                    if (i % 2 == 0) { out.add(idx[i]); out.add(idx[i + 1]); out.add(idx[i + 2]) } else { out.add(idx[i + 1]); out.add(idx[i]); out.add(idx[i + 2]) }
                }
                out.toIntArray()
            }
            else -> {
                val out = ArrayList<Int>()
                for (i in 1 until idx.size - 1) { out.add(idx[0]); out.add(idx[i]); out.add(idx[i + 1]) }
                out.toIntArray()
            }
        }

        fun loadNodes(): List<Node3D> {
            val arr = root["nodes"]?.jsonArray ?: return emptyList()
            return arr.map { e ->
                val o = e.jsonObject
                var t = floats(o["translation"], 3, floatArrayOf(0f, 0f, 0f))
                var r = floats(o["rotation"], 4, floatArrayOf(0f, 0f, 0f, 1f))
                var s = floats(o["scale"], 3, floatArrayOf(1f, 1f, 1f))
                val mat = o["matrix"] as? JsonArray
                if (mat != null && mat.size == 16) {
                    val trs = Mat4(FloatArray(16) { mat[it].jsonPrimitive.float }).decompose()
                    t = trs.t; r = trs.q; s = trs.s
                }
                Node3D(
                    name = o["name"]?.jsonPrimitive?.contentOrNull,
                    children = (o["children"] as? JsonArray)?.map { it.jsonPrimitive.int } ?: emptyList(),
                    meshIndex = o["mesh"]?.jsonPrimitive?.intOrNull ?: -1,
                    skinIndex = o["skin"]?.jsonPrimitive?.intOrNull ?: -1,
                    translation = t, rotation = r, scale = s,
                )
            }
        }

        fun loadSkins(): List<Skin3D> {
            val arr = root["skins"]?.jsonArray ?: return emptyList()
            return arr.map { e ->
                val o = e.jsonObject
                val joints = o["joints"]!!.jsonArray.map { it.jsonPrimitive.int }
                val ibm = o["inverseBindMatrices"]?.jsonPrimitive?.intOrNull?.let { readAccessor(it)?.data }
                Skin3D(o["name"]?.jsonPrimitive?.contentOrNull, joints, ibm, o["skeleton"]?.jsonPrimitive?.intOrNull ?: -1)
            }
        }

        fun loadAnimations(): List<Animation3D> {
            val arr = root["animations"]?.jsonArray ?: return emptyList()
            return arr.mapIndexed { ai, e ->
                val o = e.jsonObject
                val samplers = o["samplers"]!!.jsonArray.map { s ->
                    val so = s.jsonObject
                    val times = readAccessor(so["input"]!!.jsonPrimitive.int)?.data ?: FloatArray(0)
                    val values = readAccessor(so["output"]!!.jsonPrimitive.int)?.data ?: FloatArray(0)
                    val interp = when (so["interpolation"]?.jsonPrimitive?.contentOrNull) { "STEP" -> Interpolation.STEP; "CUBICSPLINE" -> Interpolation.CUBICSPLINE; else -> Interpolation.LINEAR }
                    AnimSampler(times, values, interp)
                }
                val channels = ArrayList<AnimChannel>()
                for (c in o["channels"]!!.jsonArray) {
                    val co = c.jsonObject
                    val target = co["target"]!!.jsonObject
                    val node = target["node"]?.jsonPrimitive?.intOrNull ?: continue
                    val path = when (target["path"]?.jsonPrimitive?.contentOrNull) {
                        "translation" -> AnimPath.TRANSLATION; "rotation" -> AnimPath.ROTATION; "scale" -> AnimPath.SCALE; "weights" -> AnimPath.WEIGHTS
                        else -> continue
                    }
                    if (path == AnimPath.WEIGHTS) {
                        notices.add(Notice(Notice.Severity.LOSS, "weights", "Canal de animación de morph weights omitido (morph targets no soportados)."))
                        continue
                    }
                    val sampler = samplers.getOrNull(co["sampler"]!!.jsonPrimitive.int) ?: continue
                    if (sampler.times.isEmpty() || sampler.values.isEmpty()) continue
                    channels.add(AnimChannel(node, path, sampler))
                }
                Animation3D(o["name"]?.jsonPrimitive?.contentOrNull ?: "Animación ${ai + 1}", channels)
            }.filter { it.channels.isNotEmpty() }
        }

        fun pickRoots(nodeCount: Int): List<Int> {
            val scenes = root["scenes"]?.jsonArray
            val sceneIdx = root["scene"]?.jsonPrimitive?.intOrNull ?: 0
            val fromScene = scenes?.getOrNull(sceneIdx)?.jsonObject?.get("nodes")?.jsonArray?.map { it.jsonPrimitive.int }
            if (!fromScene.isNullOrEmpty()) return fromScene.filter { it in 0 until nodeCount }
            val children = HashSet<Int>()
            root["nodes"]?.jsonArray?.forEach { n -> n.jsonObject["children"]?.jsonArray?.forEach { children.add(it.jsonPrimitive.int) } }
            return (0 until nodeCount).filter { it !in children }
        }
    }

    /** PNG 1x1 gris usado cuando falta una textura externa. */
    val PLACEHOLDER_PNG: ByteArray by lazy { Png.solid(1, 1, 128, 128, 128) }
}
