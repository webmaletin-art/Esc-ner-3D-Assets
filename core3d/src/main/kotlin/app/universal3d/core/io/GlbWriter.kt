package app.universal3d.core.io

import app.universal3d.core.model.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Exportador a GLB autocontenido (geometría, materiales, texturas, esqueletos y animaciones). */
object GlbWriter {
    class Result(val bytes: ByteArray, val notices: List<Notice>)

    private class BinBuilder {
        val out = ByteArrayOutputStream()
        fun align() { while (out.size() % 4 != 0) out.write(0) }
        val size get() = out.size()
    }

    fun write(scene: Scene3D, generator: String = "Universal 3D Asset Inspector"): Result {
        val notices = ArrayList<Notice>()
        val bin = BinBuilder()
        val bufferViews = ArrayList<JsonElement>()
        val accessors = ArrayList<JsonElement>()
        val extUsed = LinkedHashSet<String>()

        fun addView(data: ByteArray, target: Int?, stride: Int? = null): Int {
            bin.align()
            val off = bin.size
            bin.out.write(data)
            bufferViews.add(buildJsonObject {
                put("buffer", 0); put("byteOffset", off); put("byteLength", data.size)
                if (stride != null) put("byteStride", stride)
                if (target != null) put("target", target)
            })
            return bufferViews.size - 1
        }

        fun floatBytes(a: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(a)
            return bb.array()
        }

        fun addAccessor(view: Int, ct: Int, count: Int, type: String, min: FloatArray? = null, max: FloatArray? = null): Int {
            accessors.add(buildJsonObject {
                put("bufferView", view); put("componentType", ct); put("count", count); put("type", type)
                if (min != null) put("min", JsonArray(min.map { JsonPrimitive(it) }))
                if (max != null) put("max", JsonArray(max.map { JsonPrimitive(it) }))
            })
            return accessors.size - 1
        }

        fun floatAccessor(data: FloatArray, comps: Int, type: String, target: Int?, bounds: Boolean = false): Int {
            val count = data.size / comps
            var mn: FloatArray? = null; var mx: FloatArray? = null
            if (bounds && count > 0) {
                mn = FloatArray(comps) { Float.POSITIVE_INFINITY }; mx = FloatArray(comps) { Float.NEGATIVE_INFINITY }
                for (i in 0 until count) for (c in 0 until comps) { val v = data[i * comps + c]; if (v < mn[c]) mn[c] = v; if (v > mx[c]) mx[c] = v }
            }
            return addAccessor(addView(floatBytes(data), target), 5126, count, type, mn, mx)
        }

        // ---- Imágenes (deduplicadas por contenido) ----
        val imageMap = IntArray(scene.images.size) { -1 }
        val imagesJson = ArrayList<JsonElement>()
        val hashToIdx = HashMap<String, Int>()
        val md = MessageDigest.getInstance("SHA-256")
        scene.images.forEachIndexed { i, im ->
            val h = md.digest(im.bytes).joinToString("") { "%02x".format(it) }
            val existing = hashToIdx[h]
            if (existing != null) { imageMap[i] = existing; return@forEachIndexed }
            val mime = if (im.mimeType == "image/png" || im.mimeType == "image/jpeg" || im.mimeType == "image/webp") im.mimeType
            else (ImageHeader.sniffMime(im.bytes) ?: "image/png")
            if (mime == "image/webp") extUsed.add("EXT_texture_webp")
            val view = addView(im.bytes, null)
            imagesJson.add(buildJsonObject {
                im.name?.let { put("name", it) }
                put("mimeType", mime); put("bufferView", view)
            })
            imageMap[i] = imagesJson.size - 1
            hashToIdx[h] = imagesJson.size - 1
        }
        val webpUsed = "EXT_texture_webp" in extUsed

        val samplersJson = ArrayList<JsonElement>()
        val samplerKey = HashMap<String, Int>()
        val texturesJson = ArrayList<JsonElement>()
        val texMap = IntArray(scene.textures.size) { -1 }
        scene.textures.forEachIndexed { i, t ->
            if (t.imageIndex !in scene.images.indices) { notices.add(Notice(Notice.Severity.LOSS, "tex", "La textura $i no tiene imagen y se omite.")); return@forEachIndexed }
            val s = t.sampler
            val key = "${s.magFilter}/${s.minFilter}/${s.wrapS}/${s.wrapT}"
            val sIdx = samplerKey.getOrPut(key) {
                samplersJson.add(buildJsonObject {
                    s.magFilter?.let { put("magFilter", it) }; s.minFilter?.let { put("minFilter", it) }
                    put("wrapS", s.wrapS); put("wrapT", s.wrapT)
                })
                samplersJson.size - 1
            }
            val src = imageMap[t.imageIndex]
            val isWebp = scene.images[t.imageIndex].mimeType == "image/webp"
            texturesJson.add(buildJsonObject {
                t.name?.let { put("name", it) }
                put("sampler", sIdx)
                if (isWebp) putJsonObject("extensions") { putJsonObject("EXT_texture_webp") { put("source", src) } } else put("source", src)
            })
            texMap[i] = texturesJson.size - 1
        }
        if (webpUsed) extUsed.add("EXT_texture_webp")

        fun texInfo(ref: TextureRef?, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject? {
            ref ?: return null
            val idx = texMap.getOrNull(ref.textureIndex) ?: return null
            if (idx < 0) return null
            return buildJsonObject { put("index", idx); if (ref.texCoord != 0) put("texCoord", ref.texCoord); extra() }
        }

        // ---- Materiales ----
        val materialsJson = scene.materials.map { m ->
            buildJsonObject {
                m.name?.let { put("name", it) }
                putJsonObject("pbrMetallicRoughness") {
                    put("baseColorFactor", JsonArray(m.baseColorFactor.map { JsonPrimitive(it) }))
                    put("metallicFactor", m.metallicFactor); put("roughnessFactor", m.roughnessFactor)
                    texInfo(m.baseColorTexture)?.let { put("baseColorTexture", it) }
                    texInfo(m.metallicRoughnessTexture)?.let { put("metallicRoughnessTexture", it) }
                }
                texInfo(m.normalTexture) { if (m.normalScale != 1f) put("scale", m.normalScale) }?.let { put("normalTexture", it) }
                texInfo(m.occlusionTexture) { if (m.occlusionStrength != 1f) put("strength", m.occlusionStrength) }?.let { put("occlusionTexture", it) }
                texInfo(m.emissiveTexture)?.let { put("emissiveTexture", it) }
                val maxE = m.emissiveFactor.max()
                if (maxE > 0f || m.emissiveTexture != null) {
                    if (maxE > 1f) {
                        put("emissiveFactor", JsonArray(m.emissiveFactor.map { JsonPrimitive(it / maxE) }))
                        putJsonObject("extensions") { putJsonObject("KHR_materials_emissive_strength") { put("emissiveStrength", maxE) } }
                        extUsed.add("KHR_materials_emissive_strength")
                    } else put("emissiveFactor", JsonArray(m.emissiveFactor.map { JsonPrimitive(it) }))
                }
                if (m.alphaMode != AlphaMode.OPAQUE) put("alphaMode", m.alphaMode.name)
                if (m.alphaMode == AlphaMode.MASK) put("alphaCutoff", m.alphaCutoff)
                if (m.doubleSided) put("doubleSided", true)
            }
        }

        // ---- Mallas ----
        val meshesJson = scene.meshes.map { mesh ->
            buildJsonObject {
                mesh.name?.let { put("name", it) }
                putJsonArray("primitives") {
                    for (p in mesh.primitives) {
                        add(buildJsonObject {
                            putJsonObject("attributes") {
                                put("POSITION", floatAccessor(p.positions, 3, "VEC3", 34962, bounds = true))
                                p.normals?.let { put("NORMAL", floatAccessor(it, 3, "VEC3", 34962)) }
                                p.uv0?.let { put("TEXCOORD_0", floatAccessor(it, 2, "VEC2", 34962)) }
                                p.uv1?.let { put("TEXCOORD_1", floatAccessor(it, 2, "VEC2", 34962)) }
                                p.colors?.let { put("COLOR_0", floatAccessor(it, 4, "VEC4", 34962)) }
                                if (p.joints != null && p.weights != null) {
                                    val jb = ByteBuffer.allocate(p.joints.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                                    for (j in p.joints) jb.putShort(j.toShort())
                                    put("JOINTS_0", addAccessor(addView(jb.array(), 34962), 5123, p.joints.size / 4, "VEC4"))
                                    put("WEIGHTS_0", floatAccessor(p.weights, 4, "VEC4", 34962))
                                }
                            }
                            // Índices
                            val small = p.vertexCount < 65535
                            val ib = ByteBuffer.allocate(p.indices.size * if (small) 2 else 4).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in p.indices) if (small) ib.putShort(i.toShort()) else ib.putInt(i)
                            put("indices", addAccessor(addView(ib.array(), 34963), if (small) 5123 else 5125, p.indices.size, "SCALAR"))
                            if (p.materialIndex in scene.materials.indices) put("material", p.materialIndex)
                            put("mode", 4)
                        })
                    }
                }
            }
        }

        // ---- Nodos ----
        val nodesJson = scene.nodes.map { n ->
            buildJsonObject {
                n.name?.let { put("name", it) }
                if (n.children.isNotEmpty()) put("children", JsonArray(n.children.map { JsonPrimitive(it) }))
                if (n.meshIndex >= 0) put("mesh", n.meshIndex)
                if (n.skinIndex >= 0) put("skin", n.skinIndex)
                if (n.translation.any { it != 0f }) put("translation", JsonArray(n.translation.map { JsonPrimitive(it) }))
                if (n.rotation[0] != 0f || n.rotation[1] != 0f || n.rotation[2] != 0f || n.rotation[3] != 1f) put("rotation", JsonArray(n.rotation.map { JsonPrimitive(it) }))
                if (n.scale.any { it != 1f }) put("scale", JsonArray(n.scale.map { JsonPrimitive(it) }))
            }
        }

        val skinsJson = scene.skins.map { s ->
            buildJsonObject {
                s.name?.let { put("name", it) }
                put("joints", JsonArray(s.joints.map { JsonPrimitive(it) }))
                s.inverseBindMatrices?.takeIf { it.size == s.joints.size * 16 }?.let { put("inverseBindMatrices", floatAccessor(it, 16, "MAT4", null)) }
                if (s.skeletonRoot >= 0) put("skeleton", s.skeletonRoot)
            }
        }

        val animsJson = scene.animations.map { a ->
            buildJsonObject {
                a.name?.let { put("name", it) }
                val samplerList = ArrayList<JsonElement>()
                val channelList = ArrayList<JsonElement>()
                val sCache = HashMap<AnimSampler, Int>()
                for (c in a.channels) {
                    val sIdx = sCache.getOrPut(c.sampler) {
                        val comps = when (c.path) { AnimPath.ROTATION -> 4; else -> 3 }
                        val tIn = floatAccessor(c.sampler.times, 1, "SCALAR", null, bounds = true)
                        val vOut = floatAccessor(c.sampler.values, comps, if (comps == 4) "VEC4" else "VEC3", null)
                        samplerList.add(buildJsonObject { put("input", tIn); put("output", vOut); put("interpolation", c.sampler.interpolation.name) })
                        samplerList.size - 1
                    }
                    channelList.add(buildJsonObject {
                        put("sampler", sIdx)
                        putJsonObject("target") {
                            put("node", c.nodeIndex)
                            put("path", when (c.path) { AnimPath.TRANSLATION -> "translation"; AnimPath.ROTATION -> "rotation"; AnimPath.SCALE -> "scale"; AnimPath.WEIGHTS -> "weights" })
                        }
                    })
                }
                put("samplers", JsonArray(samplerList)); put("channels", JsonArray(channelList))
            }
        }

        bin.align()
        val root = buildJsonObject {
            putJsonObject("asset") { put("version", "2.0"); put("generator", generator) }
            if (extUsed.isNotEmpty()) put("extensionsUsed", JsonArray(extUsed.map { JsonPrimitive(it) }))
            put("scene", 0)
            putJsonArray("scenes") { add(buildJsonObject { put("name", scene.name); put("nodes", JsonArray(scene.rootNodes.map { JsonPrimitive(it) })) }) }
            if (nodesJson.isNotEmpty()) put("nodes", JsonArray(nodesJson))
            if (meshesJson.isNotEmpty()) put("meshes", JsonArray(meshesJson))
            if (materialsJson.isNotEmpty()) put("materials", JsonArray(materialsJson))
            if (texturesJson.isNotEmpty()) put("textures", JsonArray(texturesJson))
            if (imagesJson.isNotEmpty()) put("images", JsonArray(imagesJson))
            if (samplersJson.isNotEmpty()) put("samplers", JsonArray(samplersJson))
            if (skinsJson.isNotEmpty()) put("skins", JsonArray(skinsJson))
            if (animsJson.isNotEmpty()) put("animations", JsonArray(animsJson))
            put("accessors", JsonArray(accessors))
            put("bufferViews", JsonArray(bufferViews))
            putJsonArray("buffers") { add(buildJsonObject { put("byteLength", bin.size) }) }
        }
        val jsonBytes = pad(root.toString().toByteArray(Charsets.UTF_8), 0x20)
        val binBytes = bin.out.toByteArray()
        val total = 12 + 8 + jsonBytes.size + 8 + binBytes.size
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x46546C67); bb.putInt(2); bb.putInt(total)
        bb.putInt(jsonBytes.size); bb.putInt(0x4E4F534A); bb.put(jsonBytes)
        bb.putInt(binBytes.size); bb.putInt(0x004E4942); bb.put(binBytes)
        return Result(bb.array(), notices)
    }

    private fun pad(b: ByteArray, fill: Int): ByteArray {
        val n = (4 - b.size % 4) % 4
        if (n == 0) return b
        val r = b.copyOf(b.size + n)
        for (i in b.size until r.size) r[i] = fill.toByte()
        return r
    }
}
