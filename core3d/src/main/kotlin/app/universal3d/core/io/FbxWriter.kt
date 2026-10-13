package app.universal3d.core.io

import app.universal3d.core.math.Mat4
import app.universal3d.core.math.Quat
import app.universal3d.core.model.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exportador FBX binario 7.4 (geometría con múltiples materiales, jerarquía, normales, UV, colores,
 * materiales Phong aproximados y texturas embebidas). No exporta esqueletos ni animaciones (se informa).
 */
object FbxWriter {
    class Result(val bytes: ByteArray, val notices: List<Notice>)

    private var idCounter = 1_000_000L

    fun write(scene: Scene3D, embedTextures: Boolean = true): Result {
        val notices = ArrayList<Notice>()
        var nextId = 1_000_000L
        fun id() = nextId++
        if (scene.skins.isNotEmpty()) notices.add(Notice(Notice.Severity.LOSS, "skin", "FBX: esqueletos y pesos de piel no se exportan en esta versión (se exporta la malla en pose de reposo)."))
        if (scene.animations.isNotEmpty()) notices.add(Notice(Notice.Severity.LOSS, "anim", "FBX: ${scene.animations.size} animación(es) no se exportan en esta versión."))
        notices.add(Notice(Notice.Severity.LOSS, "pbr", "FBX: los materiales PBR (metal/rugosidad) se aproximan con Phong; se conservan color base, normal y emisivo."))

        val objects = ArrayList<FbxNode>()
        val conns = ArrayList<Triple<String, Long, Pair<Long, String?>>>() // tipo, hijo, (padre, prop)
        fun connect(child: Long, parent: Long, prop: String? = null) { conns.add(Triple(if (prop == null) "OO" else "OP", child, parent to prop)) }

        // ---- Materiales y texturas ----
        val matIds = LongArray(scene.materials.size) { id() }
        val imageVideoId = HashMap<Int, Long>()
        val writtenImageName = HashMap<Int, String>()
        val usedNames = HashSet<String>()
        fun imageName(i: Int): String = writtenImageName.getOrPut(i) {
            val im = scene.images[i]
            val ext = when (im.mimeType) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }
            val stem = SafeNames.fileStem(im.name?.substringBeforeLast('.') ?: "texture_$i")
            var c = "$stem.$ext"; var k = 1
            while (!usedNames.add(c.lowercase())) c = "${stem}_${k++}.$ext"
            c
        }
        var texCount = 0
        fun addTexture(ref: TextureRef?, matId: Long, slot: String) {
            ref ?: return
            val tex = scene.textures.getOrNull(ref.textureIndex) ?: return
            val img = scene.images.getOrNull(tex.imageIndex) ?: return
            val name = imageName(tex.imageIndex)
            val tId = id()
            texCount++
            val vId = imageVideoId.getOrPut(tex.imageIndex) {
                val v = id()
                val video = node("Video", v, "$name\u0000\u0001Video", "Clip") {
                    add(leaf("Type", "Clip"))
                    add(props70(listOf(listOf("Path", "KString", "XRefUrl", "", "textures/$name"))))
                    add(leaf("UseMipMap", 0))
                    add(leaf("Filename", "textures/$name"))
                    add(leaf("RelativeFilename", "textures/$name"))
                    if (embedTextures) add(FbxNode("Content", mutableListOf<Any>(img.bytes)))
                }
                objects.add(video)
                v
            }
            objects.add(node("Texture", tId, "$name\u0000\u0001Texture", "") {
                add(leaf("Type", "TextureVideoClip"))
                add(leaf("Version", 202))
                add(leaf("TextureName", "$name\u0000\u0001Texture"))
                add(props70(listOf(listOf("UseMaterial", "bool", "", "", 1))))
                add(leaf("Media", "$name\u0000\u0001Video"))
                add(leaf("FileName", "textures/$name"))
                add(leaf("RelativeFilename", "textures/$name"))
                add(FbxNode("ModelUVTranslation", mutableListOf<Any>(0.0, 0.0)))
                add(FbxNode("ModelUVScaling", mutableListOf<Any>(1.0, 1.0)))
                add(leaf("Texture_Alpha_Source", "None"))
                add(FbxNode("Cropping", mutableListOf<Any>(0, 0, 0, 0)))
            })
            connect(vId, tId)
            connect(tId, matId, slot)
        }
        scene.materials.forEachIndexed { i, m ->
            val c = m.baseColorFactor
            val shin = ((1f - m.roughnessFactor).coerceIn(0f, 1f)).let { it * it * 1000.0 }
            val name = m.name ?: "Material_$i"
            objects.add(node("Material", matIds[i], "$name\u0000\u0001Material", "") {
                add(leaf("Version", 102))
                add(leaf("ShadingModel", "phong"))
                add(leaf("MultiLayer", 0))
                add(props70(listOf(
                    listOf("DiffuseColor", "Color", "", "A", c[0].toDouble(), c[1].toDouble(), c[2].toDouble()),
                    listOf("DiffuseFactor", "Number", "", "A", 1.0),
                    listOf("EmissiveColor", "Color", "", "A", m.emissiveFactor[0].toDouble(), m.emissiveFactor[1].toDouble(), m.emissiveFactor[2].toDouble()),
                    listOf("EmissiveFactor", "Number", "", "A", 1.0),
                    listOf("SpecularColor", "Color", "", "A", 0.2 + 0.6 * m.metallicFactor.toDouble(), 0.2 + 0.6 * m.metallicFactor.toDouble(), 0.2 + 0.6 * m.metallicFactor.toDouble()),
                    listOf("Shininess", "Number", "", "A", shin),
                    listOf("Opacity", "double", "Number", "", c[3].toDouble()),
                    listOf("Roughness", "Number", "", "A", m.roughnessFactor.toDouble()),
                    listOf("Metallic", "Number", "", "A", m.metallicFactor.toDouble()),
                )))
            })
            addTexture(m.baseColorTexture, matIds[i], "DiffuseColor")
            addTexture(m.normalTexture, matIds[i], "NormalMap")
            addTexture(m.emissiveTexture, matIds[i], "EmissiveColor")
            if (m.metallicRoughnessTexture != null) notices.add(Notice(Notice.Severity.LOSS, "mr", "FBX: la textura metal/rugosidad de '$name' no se exporta (sin ranura equivalente)."))
            if (m.occlusionTexture != null) notices.add(Notice(Notice.Severity.LOSS, "ao", "FBX: la textura de oclusión de '$name' no se exporta (sin ranura equivalente)."))
        }

        // ---- Nodos ----
        val nodeIds = LongArray(scene.nodes.size) { id() }
        val hasParent = BooleanArray(scene.nodes.size)
        for (n in scene.nodes) for (c in n.children) if (c in hasParent.indices) hasParent[c] = true
        var geomCount = 0
        scene.nodes.forEachIndexed { i, n ->
            val name = n.name ?: "Node_$i"
            val e = Quat.toEulerXyzDeg(n.rotation)
            objects.add(node("Model", nodeIds[i], "$name\u0000\u0001Model", if (n.meshIndex >= 0) "Mesh" else "Null") {
                add(leaf("Version", 232))
                add(props70(listOf(
                    listOf("RotationActive", "bool", "", "", 1),
                    listOf("InheritType", "enum", "", "", 1),
                    listOf("ScalingMax", "Vector3D", "Vector", "", 0.0, 0.0, 0.0),
                    listOf("DefaultAttributeIndex", "int", "Integer", "", 0),
                    listOf("Lcl Translation", "Lcl Translation", "", "A", n.translation[0].toDouble(), n.translation[1].toDouble(), n.translation[2].toDouble()),
                    listOf("Lcl Rotation", "Lcl Rotation", "", "A", e[0].toDouble(), e[1].toDouble(), e[2].toDouble()),
                    listOf("Lcl Scaling", "Lcl Scaling", "", "A", n.scale[0].toDouble(), n.scale[1].toDouble(), n.scale[2].toDouble()),
                )))
                add(leaf("Shading", true))
                add(leaf("Culling", "CullingOff"))
            })
            if (!hasParent[i]) connect(nodeIds[i], 0) 
            for (c in n.children) if (c in nodeIds.indices) connect(nodeIds[c], nodeIds[i])
            if (n.meshIndex >= 0) {
                val mesh = scene.meshes[n.meshIndex]
                val gId = id(); geomCount++
                objects.add(buildGeometry(gId, mesh, n.name ?: mesh.name ?: "Mesh_$i"))
                connect(gId, nodeIds[i])
                // materiales por ranura (en orden de aparición)
                val slots = mesh.primitives.map { it.materialIndex }.distinct()
                for (mi in slots) if (mi in matIds.indices) connect(matIds[mi], nodeIds[i])
            }
        }

        // ---- Definiciones ----
        val defs = FbxNode("Definitions").apply {
            add(leaf("Version", 100))
            add(leaf("Count", 1 + scene.nodes.size + geomCount + scene.materials.size + texCount + imageVideoId.size))
            fun ot(n: String, c: Int) { if (c > 0) add(FbxNode("ObjectType", mutableListOf<Any>(n)).apply { add(leaf("Count", c)) }) }
            ot("GlobalSettings", 1); ot("Model", scene.nodes.size); ot("Geometry", geomCount)
            ot("Material", scene.materials.size); ot("Texture", texCount); ot("Video", imageVideoId.size)
        }
        val header = FbxNode("FBXHeaderExtension").apply {
            add(leaf("FBXHeaderVersion", 1003)); add(leaf("FBXVersion", 7400))
            add(FbxNode("CreationTimeStamp").apply {
                add(leaf("Version", 1000)); add(leaf("Year", 2025)); add(leaf("Month", 1)); add(leaf("Day", 1))
                add(leaf("Hour", 0)); add(leaf("Minute", 0)); add(leaf("Second", 0)); add(leaf("Millisecond", 0))
            })
            add(leaf("Creator", "Universal 3D Asset Inspector"))
        }
        val globals = FbxNode("GlobalSettings").apply {
            add(leaf("Version", 1000))
            add(props70(listOf(
                listOf("UpAxis", "int", "Integer", "", 1), listOf("UpAxisSign", "int", "Integer", "", 1),
                listOf("FrontAxis", "int", "Integer", "", 2), listOf("FrontAxisSign", "int", "Integer", "", 1),
                listOf("CoordAxis", "int", "Integer", "", 0), listOf("CoordAxisSign", "int", "Integer", "", 1),
                listOf("OriginalUpAxis", "int", "Integer", "", -1), listOf("OriginalUpAxisSign", "int", "Integer", "", 1),
                listOf("UnitScaleFactor", "double", "Number", "", 100.0), listOf("OriginalUnitScaleFactor", "double", "Number", "", 100.0),
            )))
        }
        val connections = FbxNode("Connections").apply {
            for ((t, child, parent) in conns) {
                val c = FbxNode("C", mutableListOf<Any>(t, child, parent.first))
                parent.second?.let { c.props.add(it) }
                add(c)
            }
        }
        val top = listOf(header, FbxNode("FileId", mutableListOf<Any>(ByteArray(16) { (it * 7 + 3).toByte() })), leaf("CreationTime", "2025-01-01 00:00:00:000"),
            leaf("Creator", "Universal 3D Asset Inspector"), globals, defs, FbxNode("Objects", children = objects), connections, FbxNode("Takes").apply { add(leaf("Current", "")) })
        return Result(serialize(top), notices)
    }

    // ------------ construcción de nodos ------------
    private fun FbxNode.add(n: FbxNode) { children.add(n) }
    private fun leaf(name: String, v: Any) = FbxNode(name, mutableListOf(v))
    private fun node(name: String, id: Long, nm: String, cls: String, body: FbxNode.() -> Unit): FbxNode {
        val n = FbxNode(name, mutableListOf<Any>(id, nm, cls)); n.body(); return n
    }
    private fun props70(rows: List<List<Any>>): FbxNode {
        val n = FbxNode("Properties70")
        for (r in rows) n.children.add(FbxNode("P", r.map { if (it is Float) it.toDouble() else it }.toMutableList()))
        return n
    }

    private fun buildGeometry(gId: Long, mesh: Mesh3D, name: String): FbxNode {
        val prims = mesh.primitives
        val total = prims.sumOf { it.vertexCount }
        val pos = DoubleArray(total * 3)
        val hasN = prims.any { it.normals != null }
        val hasT = prims.any { it.uv0 != null }
        val hasC = prims.any { it.colors != null }
        val nrm = if (hasN) DoubleArray(total * 3) else null
        val uv = if (hasT) DoubleArray(total * 2) else null
        val col = if (hasC) DoubleArray(total * 4) else null
        val polyIdx = ArrayList<Int>()
        val polyMat = ArrayList<Int>()
        val slots = prims.map { it.materialIndex }.distinct()
        var vo = 0
        for (p in prims) {
            for (v in 0 until p.vertexCount) {
                pos[(vo + v) * 3] = p.positions[v * 3].toDouble(); pos[(vo + v) * 3 + 1] = p.positions[v * 3 + 1].toDouble(); pos[(vo + v) * 3 + 2] = p.positions[v * 3 + 2].toDouble()
                if (nrm != null) { p.normals?.let { nrm[(vo + v) * 3] = it[v * 3].toDouble(); nrm[(vo + v) * 3 + 1] = it[v * 3 + 1].toDouble(); nrm[(vo + v) * 3 + 2] = it[v * 3 + 2].toDouble() } ?: run { nrm[(vo + v) * 3 + 1] = 1.0 } }
                if (uv != null) p.uv0?.let { uv[(vo + v) * 2] = it[v * 2].toDouble(); uv[(vo + v) * 2 + 1] = 1.0 - it[v * 2 + 1].toDouble() }
                if (col != null) { val c = p.colors; for (q in 0 until 4) col[(vo + v) * 4 + q] = c?.get(v * 4 + q)?.toDouble() ?: 1.0 }
            }
            val slot = slots.indexOf(p.materialIndex).coerceAtLeast(0)
            var i = 0
            while (i + 2 < p.indices.size) {
                polyIdx.add(vo + p.indices[i]); polyIdx.add(vo + p.indices[i + 1]); polyIdx.add((vo + p.indices[i + 2]).inv())
                polyMat.add(slot); i += 3
            }
            vo += p.vertexCount
        }
        val pvi = polyIdx.toIntArray()
        val g = node("Geometry", gId, "$name\u0000\u0001Geometry", "Mesh") {
            add(props70(emptyList()))
            add(leaf("GeometryVersion", 124))
            add(FbxNode("Vertices", mutableListOf<Any>(pos)))
            add(FbxNode("PolygonVertexIndex", mutableListOf<Any>(pvi)))
            val layerEls = ArrayList<String>()
            if (nrm != null) {
                add(FbxNode("LayerElementNormal", mutableListOf<Any>(0)).apply {
                    add(leaf("Version", 101)); add(leaf("Name", "")); add(leaf("MappingInformationType", "ByPolygonVertex"))
                    add(leaf("ReferenceInformationType", "IndexToDirect")); add(FbxNode("Normals", mutableListOf<Any>(nrm)))
                    add(FbxNode("NormalsIndex", mutableListOf<Any>(IntArray(pvi.size) { val x = pvi[it]; if (x < 0) x.inv() else x })))
                }); layerEls.add("LayerElementNormal")
            }
            if (uv != null) {
                add(FbxNode("LayerElementUV", mutableListOf<Any>(0)).apply {
                    add(leaf("Version", 101)); add(leaf("Name", "UVMap")); add(leaf("MappingInformationType", "ByPolygonVertex"))
                    add(leaf("ReferenceInformationType", "IndexToDirect")); add(FbxNode("UV", mutableListOf<Any>(uv)))
                    add(FbxNode("UVIndex", mutableListOf<Any>(IntArray(pvi.size) { val x = pvi[it]; if (x < 0) x.inv() else x })))
                }); layerEls.add("LayerElementUV")
            }
            if (col != null) {
                add(FbxNode("LayerElementColor", mutableListOf<Any>(0)).apply {
                    add(leaf("Version", 101)); add(leaf("Name", "Col")); add(leaf("MappingInformationType", "ByPolygonVertex"))
                    add(leaf("ReferenceInformationType", "IndexToDirect")); add(FbxNode("Colors", mutableListOf<Any>(col)))
                    add(FbxNode("ColorIndex", mutableListOf<Any>(IntArray(pvi.size) { val x = pvi[it]; if (x < 0) x.inv() else x })))
                }); layerEls.add("LayerElementColor")
            }
            add(FbxNode("LayerElementMaterial", mutableListOf<Any>(0)).apply {
                add(leaf("Version", 101)); add(leaf("Name", "")); add(leaf("MappingInformationType", "ByPolygon"))
                add(leaf("ReferenceInformationType", "IndexToDirect")); add(FbxNode("Materials", mutableListOf<Any>(polyMat.toIntArray())))
            }); layerEls.add("LayerElementMaterial")
            add(FbxNode("Layer", mutableListOf<Any>(0)).apply {
                add(leaf("Version", 100))
                for (e in layerEls) add(FbxNode("LayerElement").apply { add(leaf("Type", e)); add(leaf("TypedIndex", 0)) })
            })
        }
        return g
    }

    // ------------ serialización binaria ------------
    private fun serialize(top: List<FbxNode>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("Kaydara FBX Binary  ".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0, 0x1A, 0))
        out.write(le32(7400))
        for (n in top) writeNode(out, n)
        out.write(ByteArray(13)) // registro nulo final
        // pie de archivo estándar
        out.write(byteArrayOf(0xfa.toByte(), 0xbc.toByte(), 0xab.toByte(), 0x09, 0xd0.toByte(), 0xc8.toByte(), 0xd4.toByte(), 0x66, 0xb1.toByte(), 0x76, 0xfb.toByte(), 0x83.toByte(), 0x1c, 0xf7.toByte(), 0x26, 0x7e))
        while (out.size() % 16 != 0) out.write(0)
        out.write(ByteArray(4))
        out.write(le32(7400))
        out.write(ByteArray(120))
        out.write(byteArrayOf(0xf8.toByte(), 0x5a, 0x8c.toByte(), 0x6a, 0xde.toByte(), 0xf5.toByte(), 0xd9.toByte(), 0x7e, 0xec.toByte(), 0xe9.toByte(), 0x0c, 0xe3.toByte(), 0x75, 0x8f.toByte(), 0x29, 0x0b))
        return out.toByteArray()
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    /** Escribe un nodo; los offsets absolutos se calculan con el tamaño ya escrito. */
    private fun writeNode(out: ByteArrayOutputStream, n: FbxNode) {
        // los offsets deben ser absolutos: serializamos a un buffer temporal conociendo la posición base
        val base = out.size()
        val body = ByteArrayOutputStream()
        val propBytes = ByteArrayOutputStream()
        for (p in n.props) writeProp(propBytes, p)
        val nameB = n.name.toByteArray(Charsets.UTF_8)
        val headerSize = 13 + nameB.size
        val propsStart = base + headerSize
        val childrenStart = propsStart + propBytes.size()
        // hijos: se escriben directamente a un stream con posición absoluta conocida
        val childStream = PositionedStream(childrenStart)
        for (c in n.children) writeNodeAt(childStream, c)
        if (n.children.isNotEmpty()) childStream.write(ByteArray(13))
        val end = childrenStart + childStream.size()
        out.write(le32(end)); out.write(le32(n.props.size)); out.write(le32(propBytes.size()))
        out.write(nameB.size); out.write(nameB)
        out.write(propBytes.toByteArray())
        out.write(childStream.toByteArray())
    }

    private class PositionedStream(val start: Int) {
        val bos = ByteArrayOutputStream()
        val position get() = start + bos.size()
        fun size() = bos.size()
        fun write(b: ByteArray) = bos.write(b)
        fun toByteArray(): ByteArray = bos.toByteArray()
    }

    private fun writeNodeAt(ps: PositionedStream, n: FbxNode) {
        val base = ps.position
        val propBytes = ByteArrayOutputStream()
        for (p in n.props) writeProp(propBytes, p)
        val nameB = n.name.toByteArray(Charsets.UTF_8)
        val propsStart = base + 13 + nameB.size
        val childrenStart = propsStart + propBytes.size()
        val child = PositionedStream(childrenStart)
        for (c in n.children) writeNodeAt(child, c)
        if (n.children.isNotEmpty()) child.write(ByteArray(13))
        val end = childrenStart + child.size()
        ps.write(le32(end)); ps.write(le32(n.props.size)); ps.write(le32(propBytes.size()))
        ps.write(byteArrayOf(nameB.size.toByte())); ps.write(nameB)
        ps.write(propBytes.toByteArray()); ps.write(child.toByteArray())
    }

    private fun writeProp(o: ByteArrayOutputStream, p: Any) {
        fun w(b: ByteBuffer) = o.write(b.array())
        when (p) {
            is Int -> { o.write('I'.code); w(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(p)) }
            is Long -> { o.write('L'.code); w(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(p)) }
            is Double -> { o.write('D'.code); w(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(p)) }
            is Float -> { o.write('D'.code); w(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(p.toDouble())) }
            is Boolean -> { o.write('C'.code); o.write(if (p) 1 else 0) }
            is String -> { val b = p.toByteArray(Charsets.UTF_8); o.write('S'.code); o.write(le32(b.size)); o.write(b) }
            is ByteArray -> { o.write('R'.code); o.write(le32(p.size)); o.write(p) }
            is DoubleArray -> {
                val bb = ByteBuffer.allocate(p.size * 8).order(ByteOrder.LITTLE_ENDIAN); bb.asDoubleBuffer().put(p)
                writeArray(o, 'd', p.size, bb.array())
            }
            is IntArray -> {
                val bb = ByteBuffer.allocate(p.size * 4).order(ByteOrder.LITTLE_ENDIAN); bb.asIntBuffer().put(p)
                writeArray(o, 'i', p.size, bb.array())
            }
            else -> throw IllegalArgumentException("tipo de propiedad FBX no soportado: ${p::class}")
        }
    }

    private fun writeArray(o: ByteArrayOutputStream, t: Char, count: Int, raw: ByteArray) {
        val def = java.util.zip.Deflater(6)
        def.setInput(raw); def.finish()
        val comp = ByteArrayOutputStream(); val buf = ByteArray(65536)
        while (!def.finished()) { val n = def.deflate(buf); comp.write(buf, 0, n) }
        def.end()
        val useComp = comp.size() < raw.size
        o.write(t.code); o.write(le32(count)); o.write(le32(if (useComp) 1 else 0))
        if (useComp) { o.write(le32(comp.size())); o.write(comp.toByteArray()) } else { o.write(le32(raw.size)); o.write(raw) }
    }
}
