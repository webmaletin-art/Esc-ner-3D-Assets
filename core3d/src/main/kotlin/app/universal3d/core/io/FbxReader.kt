package app.universal3d.core.io

import app.universal3d.core.math.Mat4
import app.universal3d.core.math.Quat
import app.universal3d.core.model.*

/** Importador FBX (binario 7.x y ASCII 7.x): geometría, jerarquía, materiales y texturas. */
object FbxReader {
    private class Conn(val type: String, val child: Long, val parent: Long, val prop: String?)

    private data class VKey(val a: Int, val b: Int, val c: Int, val d: Int)

    fun fbxName(raw: String?): String? {
        raw ?: return null
        val n = if (raw.contains("\u0000\u0001")) raw.substringBefore("\u0000\u0001") else if (raw.contains("::")) raw.substringAfter("::") else raw
        return n.ifEmpty { null }
    }

    fun read(bytes: ByteArray, name: String, bundle: FileBundle?, mainPath: String?): Scene3D {
        val (version, roots) = FbxParser.parse(bytes)
        if (version < 7000) throw UnsupportedFormatException("FBX versión $version (anterior a 7.0) no soportado por el motor actual.")
        val notices = ArrayList<Notice>()
        val missing = ArrayList<String>()

        val objectsNode = roots.firstOrNull { it.name == "Objects" } ?: throw ModelImportException(ModelImportException.Code.MALFORMED, "FBX sin sección 'Objects'.")
        val objects = HashMap<Long, FbxNode>()
        for (o in objectsNode.children) o.long(0)?.let { objects[it] = o }
        val conns = ArrayList<Conn>()
        roots.firstOrNull { it.name == "Connections" }?.children?.forEach { c ->
            if (c.name == "C" || c.name == "Connect") {
                val t = c.string(0) ?: return@forEach
                val ch = c.long(1) ?: return@forEach
                val pa = c.long(2) ?: return@forEach
                conns.add(Conn(t, ch, pa, c.string(3)))
            }
        }

        // Ajustes globales
        val gs = roots.firstOrNull { it.name == "GlobalSettings" }
        val upAxis = gs?.p70Int("UpAxis") ?: 1
        val upSign = gs?.p70Int("UpAxisSign") ?: 1
        val unitScale = gs?.p70Double("UnitScaleFactor") ?: 1.0

        val parentsOf = HashMap<Long, MutableList<Conn>>()   // por hijo
        val childrenOf = HashMap<Long, MutableList<Conn>>()  // por padre
        for (c in conns) { parentsOf.getOrPut(c.child) { ArrayList() }.add(c); childrenOf.getOrPut(c.parent) { ArrayList() }.add(c) }

        // ---- Imágenes / texturas ----
        val images = ArrayList<Image3D>()
        val imageKeyToIdx = HashMap<String, Int>()
        val textures = ArrayList<Texture3D>()
        val texIdForFbx = HashMap<Long, Int>()
        fun textureFor(texId: Long): Int {
            texIdForFbx[texId]?.let { return it }
            val tex = objects[texId] ?: return -1
            val fileName = tex.child("FileName")?.string(0)?.takeIf { it.isNotBlank() }
            val relName = tex.child("RelativeFilename")?.string(0)?.takeIf { it.isNotBlank() }
            var content: ByteArray? = null
            var vName: String? = null
            for (c in childrenOf[texId].orEmpty()) {
                val v = objects[c.child] ?: continue
                if (v.name == "Video") {
                    content = v.child("Content")?.bytes(0)?.takeIf { it.isNotEmpty() }
                    vName = v.child("RelativeFilename")?.string(0) ?: v.child("FileName")?.string(0)
                }
            }
            val ref = (relName ?: fileName ?: vName)?.replace('\\', '/')
            val display = ref?.substringAfterLast('/') ?: fbxName(tex.string(1)) ?: "texture"
            var data = content
            if (data == null && ref != null) {
                val f = bundle?.find(ref, mainPath) ?: fileName?.let { bundle?.find(it.replace('\\', '/'), mainPath) }
                if (f != null) data = f.read() else missing.add(display)
            }
            if (data == null) { texIdForFbx[texId] = -1; return -1 }
            val key = (ref ?: display) + "#" + data.size
            val imgIdx = imageKeyToIdx.getOrPut(key) {
                images.add(Image3D(display, ImageHeader.sniffMime(data) ?: ImageHeader.mimeForName(display) ?: "image/png", data, ref))
                images.size - 1
            }
            textures.add(Texture3D(display, imgIdx))
            texIdForFbx[texId] = textures.size - 1
            return textures.size - 1
        }

        // ---- Materiales ----
        val materials = ArrayList<Material3D>()
        val matIdxForFbx = HashMap<Long, Int>()
        fun materialFor(matId: Long): Int {
            matIdxForFbx[matId]?.let { return it }
            val m = objects[matId] ?: return -1
            val diffuse = m.p70Doubles("DiffuseColor", 3) ?: doubleArrayOf(0.8, 0.8, 0.8)
            val dFactor = m.p70Double("DiffuseFactor") ?: 1.0
            val emis = m.p70Doubles("EmissiveColor", 3) ?: doubleArrayOf(0.0, 0.0, 0.0)
            val eFactor = m.p70Double("EmissiveFactor") ?: 1.0
            val opacity = m.p70Double("Opacity") ?: m.p70Double("TransparencyFactor")?.let { 1.0 - it } ?: 1.0
            val shin = m.p70Double("Shininess") ?: m.p70Double("ShininessExponent")
            var base: TextureRef? = null; var normal: TextureRef? = null; var emissive: TextureRef? = null; var occl: TextureRef? = null
            var mr: TextureRef? = null
            for (c in childrenOf[matId].orEmpty()) {
                val tex = objects[c.child] ?: continue
                if (tex.name != "Texture" || c.type != "OP") continue
                val prop = (c.prop ?: "").lowercase()
                val ti = textureFor(c.child)
                if (ti < 0) continue
                val ref = TextureRef(ti)
                when {
                    prop.contains("diffuse") || prop.contains("base_color") || prop.contains("basecolor") -> if (base == null) base = ref
                    prop.contains("normal") -> normal = ref
                    prop.contains("bump") -> if (normal == null) { normal = ref; notices.add(Notice(Notice.Severity.INFO, "bump", "Material '${fbxName(m.string(1))}': el mapa Bump se usa como mapa normal.")) }
                    prop.contains("emissive") || prop.contains("emit") -> emissive = ref
                    prop.contains("ambientocclusion") || prop == "ao" || prop.contains("occlusion") -> occl = ref
                    prop.contains("metal") || prop.contains("rough") ->
                        notices.add(Notice(Notice.Severity.LOSS, "pbrmap", "Material '${fbxName(m.string(1))}': el mapa '${c.prop}' (metal/rugosidad separados) no se puede combinar; se conserva solo el valor escalar."))
                    else -> notices.add(Notice(Notice.Severity.LOSS, "texslot", "Material '${fbxName(m.string(1))}': textura en ranura '${c.prop}' sin equivalente PBR; se omite."))
                }
            }
            val alpha = opacity.coerceIn(0.0, 1.0).toFloat()
            val mat = Material3D(
                name = fbxName(m.string(1)),
                baseColorFactor = floatArrayOf((diffuse[0] * dFactor).toFloat().coerceIn(0f, 1f), (diffuse[1] * dFactor).toFloat().coerceIn(0f, 1f), (diffuse[2] * dFactor).toFloat().coerceIn(0f, 1f), alpha),
                baseColorTexture = base, metallicFactor = 0f,
                roughnessFactor = shin?.let { (1f - kotlin.math.sqrt((it.toFloat() / 1000f).coerceIn(0f, 1f))).coerceIn(0.04f, 1f) } ?: 0.8f,
                metallicRoughnessTexture = mr, normalTexture = normal, occlusionTexture = occl,
                emissiveFactor = floatArrayOf((emis[0] * eFactor).toFloat(), (emis[1] * eFactor).toFloat(), (emis[2] * eFactor).toFloat()),
                emissiveTexture = emissive,
                alphaMode = if (alpha < 1f) AlphaMode.BLEND else AlphaMode.OPAQUE, doubleSided = true,
            )
            materials.add(mat)
            matIdxForFbx[matId] = materials.size - 1
            return materials.size - 1
        }

        // ---- Modelos (nodos) ----
        val modelIds = objectsNode.children.filter { it.name == "Model" }.mapNotNull { it.long(0) }
        val nodeIndexOfModel = HashMap<Long, Int>()
        modelIds.forEachIndexed { i, id -> nodeIndexOfModel[id] = i }
        val meshes = ArrayList<Mesh3D>()
        val nodeMeshIdx = IntArray(modelIds.size) { -1 }
        val meshCache = HashMap<String, Int>()
        val childLists = Array(modelIds.size) { ArrayList<Int>() }
        val rootList = ArrayList<Int>()
        val nodeDefs = ArrayList<Node3D?>(modelIds.size).also { l -> repeat(modelIds.size) { l.add(null) } }

        for ((i, id) in modelIds.withIndex()) {
            val parentModel = parentsOf[id].orEmpty().firstOrNull { it.type == "OO" && nodeIndexOfModel.containsKey(it.parent) }
            if (parentModel != null) childLists[nodeIndexOfModel[parentModel.parent]!!].add(i) else rootList.add(i)
        }

        for ((i, id) in modelIds.withIndex()) {
            val model = objects[id]!!
            val t = model.p70Doubles("Lcl Translation", 3) ?: doubleArrayOf(0.0, 0.0, 0.0)
            val r = model.p70Doubles("Lcl Rotation", 3) ?: doubleArrayOf(0.0, 0.0, 0.0)
            val s = model.p70Doubles("Lcl Scaling", 3) ?: doubleArrayOf(1.0, 1.0, 1.0)
            val pre = model.p70Doubles("PreRotation", 3)
            val order = model.p70Int("RotationOrder") ?: 0
            var q = eulerQuat(r, order)
            if (pre != null && model.p70Int("RotationActive") != 0) q = Quat.normalize(Quat.multiply(eulerQuat(pre, 0), q))
            val rp = model.p70Doubles("RotationPivot", 3)
            if (rp != null && rp.any { it != 0.0 }) notices.add(Notice(Notice.Severity.WARNING, "pivot", "El nodo '${fbxName(model.string(1))}' usa pivotes de rotación; no se aplican."))

            // geometría
            var meshIdx = -1
            val geomConn = childrenOf[id].orEmpty().firstOrNull { c -> objects[c.child]?.name == "Geometry" }
            if (geomConn != null) {
                val geom = objects[geomConn.child]!!
                val matIds = childrenOf[id].orEmpty().filter { objects[it.child]?.name == "Material" }.map { it.child }
                val geoT = model.p70Doubles("GeometricTranslation", 3)
                val geoR = model.p70Doubles("GeometricRotation", 3)
                val geoS = model.p70Doubles("GeometricScaling", 3)
                val gm = if (geoT == null && geoR == null && geoS == null) null else Mat4.fromTrs(
                    (geoT ?: doubleArrayOf(0.0, 0.0, 0.0)).map { it.toFloat() }.toFloatArray(),
                    eulerQuat(geoR ?: doubleArrayOf(0.0, 0.0, 0.0), 0),
                    (geoS ?: doubleArrayOf(1.0, 1.0, 1.0)).map { it.toFloat() }.toFloatArray(),
                )
                val key = "${geomConn.child}|${matIds.joinToString(",")}|${gm?.m?.joinToString(",")}"
                meshIdx = meshCache.getOrPut(key) {
                    val matIdx = matIds.map { materialFor(it) }
                    val prims = buildPrimitives(geom, matIdx, gm, notices)
                    meshes.add(Mesh3D(fbxName(geom.string(1)) ?: fbxName(model.string(1)), prims))
                    meshes.size - 1
                }
                if (meshes[meshIdx].primitives.isEmpty()) meshIdx = -1
            }
            nodeMeshIdx[i] = meshIdx
            nodeDefs[i] = Node3D(
                name = fbxName(model.string(1)), children = childLists[i].toList(), meshIndex = meshIdx,
                translation = floatArrayOf(t[0].toFloat(), t[1].toFloat(), t[2].toFloat()), rotation = q,
                scale = floatArrayOf(s[0].toFloat(), s[1].toFloat(), s[2].toFloat()),
            )
        }

        if (objectsNode.children.any { it.name == "Deformer" && it.string(2) == "Skin" })
            notices.add(Notice(Notice.Severity.LOSS, "fbx-skin", "El FBX contiene esqueleto/pesos de piel: no se importan (se muestra la pose de reposo)."))
        if (objectsNode.children.any { it.name == "AnimationStack" })
            notices.add(Notice(Notice.Severity.LOSS, "fbx-anim", "El FBX contiene animaciones: no se importan en esta versión."))
        if (objectsNode.children.any { it.name == "Deformer" && it.string(2) == "BlendShape" })
            notices.add(Notice(Notice.Severity.LOSS, "fbx-blend", "El FBX contiene blend shapes: no soportados."))

        val nodes = ArrayList<Node3D>(nodeDefs.map { it!! })
        var sceneRoots: List<Int> = rootList.toList()

        // Unidades y eje vertical -> metros, Y arriba
        val needsRoot = upAxis != 1 || kotlin.math.abs(unitScale - 100.0) > 1e-6
        if (needsRoot && nodes.isNotEmpty()) {
            val s = (unitScale * 0.01).toFloat()
            val q = when {
                upAxis == 2 -> Quat.fromAxisAngle(1f, 0f, 0f, (-Math.PI / 2).toFloat())
                upAxis == 0 -> Quat.fromAxisAngle(0f, 0f, 1f, (Math.PI / 2).toFloat())
                else -> Quat.IDENTITY
            }
            nodes.add(Node3D("FBX_Root", children = sceneRoots, rotation = q, scale = floatArrayOf(s, s, s)))
            sceneRoots = listOf(nodes.size - 1)
            notices.add(Notice(Notice.Severity.INFO, "fbx-units", "Se aplicó conversión de unidades (factor ${"%.4g".format(unitScale)} cm→m = ${"%.4g".format(s)}) y eje vertical ${if (upAxis == 2) "Z→Y" else if (upAxis == 0) "X→Y" else "Y"}."))
            if (upSign < 0) notices.add(Notice(Notice.Severity.WARNING, "fbx-upsign", "El signo del eje vertical es negativo; la orientación puede requerir ajuste manual."))
        }
        if (meshes.isEmpty() || meshes.all { it.primitives.isEmpty() }) throw ModelImportException(ModelImportException.Code.EMPTY, "El FBX no contiene mallas de polígonos legibles.")
        return Scene3D(
            name = name, sourceFormat = ModelFormat.FBX, nodes = nodes, rootNodes = sceneRoots, meshes = meshes, materials = materials,
            textures = textures, images = images, importNotices = notices, missingDependencies = missing.distinct(), sourceSizeBytes = bytes.size.toLong(),
        )
    }

    /** Orden FBX: 0 XYZ, 1 XZY, 2 YZX, 3 YXZ, 4 ZXY, 5 ZYX (rotación intrínseca; R = Rc·Rb·Ra). */
    fun eulerQuat(deg: DoubleArray, order: Int): FloatArray {
        val d = Math.PI / 180.0
        val q = arrayOf(
            Quat.fromAxisAngle(1f, 0f, 0f, (deg[0] * d).toFloat()),
            Quat.fromAxisAngle(0f, 1f, 0f, (deg[1] * d).toFloat()),
            Quat.fromAxisAngle(0f, 0f, 1f, (deg[2] * d).toFloat()),
        )
        val seq = when (order) { 1 -> intArrayOf(0, 2, 1); 2 -> intArrayOf(1, 2, 0); 3 -> intArrayOf(1, 0, 2); 4 -> intArrayOf(2, 0, 1); 5 -> intArrayOf(2, 1, 0); else -> intArrayOf(0, 1, 2) }
        // aplica seq[0] primero: R = q[seq2] * q[seq1] * q[seq0]
        return Quat.normalize(Quat.multiply(q[seq[2]], Quat.multiply(q[seq[1]], q[seq[0]])))
    }

    private class Layer(val mapping: String, val reference: String, val data: DoubleArray, val comps: Int, val index: IntArray?) {
        fun elementFor(k: Int, cp: Int, poly: Int): Int {
            val base = when (mapping) { "ByPolygonVertex" -> k; "ByVertice", "ByVertex", "ByControlPoint" -> cp; "ByPolygon" -> poly; "AllSame" -> 0; else -> k }
            val e = if (reference.startsWith("Index") && index != null) index.getOrElse(base) { -1 } else base
            return if (e < 0 || (e + 1) * comps > data.size) -1 else e
        }
    }

    private fun layer(geom: FbxNode, layerName: String, dataName: String, indexName: String, comps: Int): Layer? {
        val l = geom.child(layerName) ?: return null
        val data = l.child(dataName)?.doubles() ?: return null
        return Layer(
            l.child("MappingInformationType")?.string(0) ?: "ByPolygonVertex",
            l.child("ReferenceInformationType")?.string(0) ?: "Direct", data, comps, l.child(indexName)?.ints(),
        )
    }

    private fun buildPrimitives(geom: FbxNode, matIdx: List<Int>, geometric: Mat4?, notices: MutableList<Notice>): List<Primitive3D> {
        val verts = geom.child("Vertices")?.doubles() ?: return emptyList()
        val pvi = geom.child("PolygonVertexIndex")?.ints() ?: return emptyList()
        val normals = layer(geom, "LayerElementNormal", "Normals", "NormalsIndex", 3)
        val uvs = layer(geom, "LayerElementUV", "UV", "UVIndex", 2)
        val colors = layer(geom, "LayerElementColor", "Colors", "ColorIndex", 4)
        val matLayer = geom.child("LayerElementMaterial")
        val matMapping = matLayer?.child("MappingInformationType")?.string(0) ?: "AllSame"
        val matSlots = matLayer?.child("Materials")?.ints()
        val cpCount = verts.size / 3
        // Posiciones (con transformación geométrica)
        val pos = FloatArray(cpCount * 3)
        val tmp = FloatArray(3)
        for (i in 0 until cpCount) {
            if (geometric != null) { geometric.transformPoint(verts[i * 3].toFloat(), verts[i * 3 + 1].toFloat(), verts[i * 3 + 2].toFloat(), tmp); pos[i * 3] = tmp[0]; pos[i * 3 + 1] = tmp[1]; pos[i * 3 + 2] = tmp[2] }
            else { pos[i * 3] = verts[i * 3].toFloat(); pos[i * 3 + 1] = verts[i * 3 + 1].toFloat(); pos[i * 3 + 2] = verts[i * 3 + 2].toFloat() }
        }
        val nmat = geometric?.inverse()?.transposed()

        class Bucket {
            val map = HashMap<VKey, Int>()
            val p = FloatList(); val n = FloatList(); val t = FloatList(); val c = FloatList(); val idx = IntList()
        }
        val buckets = LinkedHashMap<Int, Bucket>()
        var polyIdx = 0
        var start = 0
        var k = 0
        var badIndex = 0
        while (k < pvi.size) {
            if (pvi[k] < 0) {
                val n = k - start + 1
                if (n >= 3) {
                    val slot = when {
                        matSlots == null -> 0
                        matMapping == "ByPolygon" -> matSlots.getOrElse(polyIdx) { 0 }
                        else -> matSlots.getOrElse(0) { 0 }
                    }
                    val mi = matIdx.getOrElse(slot) { -1 }
                    val b = buckets.getOrPut(mi) { Bucket() }
                    fun vertex(pk: Int): Int {
                        val raw = pvi[pk]
                        val cp = if (raw < 0) raw.inv() else raw
                        if (cp >= cpCount) { badIndex++; return -1 }
                        val ne = normals?.elementFor(pk, cp, polyIdx) ?: -1
                        val ue = uvs?.elementFor(pk, cp, polyIdx) ?: -1
                        val ce = colors?.elementFor(pk, cp, polyIdx) ?: -1
                        return b.map.getOrPut(VKey(cp, ne, ue, ce)) {
                            b.p.add(pos[cp * 3]); b.p.add(pos[cp * 3 + 1]); b.p.add(pos[cp * 3 + 2])
                            if (normals != null) {
                                if (ne >= 0) {
                                    var nx = normals.data[ne * 3].toFloat(); var ny = normals.data[ne * 3 + 1].toFloat(); var nz = normals.data[ne * 3 + 2].toFloat()
                                    if (nmat != null) { nmat.transformDirection(nx, ny, nz, tmp); val l = kotlin.math.sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1] + tmp[2] * tmp[2]).let { if (it == 0f) 1f else it }; nx = tmp[0] / l; ny = tmp[1] / l; nz = tmp[2] / l }
                                    b.n.add(nx); b.n.add(ny); b.n.add(nz)
                                } else { b.n.add(0f); b.n.add(0f); b.n.add(0f) }
                            }
                            if (uvs != null) {
                                if (ue >= 0) { b.t.add(uvs.data[ue * 2].toFloat()); b.t.add(1f - uvs.data[ue * 2 + 1].toFloat()) } else { b.t.add(0f); b.t.add(0f) }
                            }
                            if (colors != null) {
                                if (ce >= 0) { for (q in 0 until 4) b.c.add(colors.data[ce * 4 + q].toFloat()) } else { b.c.add(1f); b.c.add(1f); b.c.add(1f); b.c.add(1f) }
                            }
                            b.p.size / 3 - 1
                        }
                    }
                    for (j in 1 until n - 1) {
                        val a = vertex(start); val bb = vertex(start + j); val c = vertex(start + j + 1)
                        if (a < 0 || bb < 0 || c < 0) continue
                        b.idx.add(a); b.idx.add(bb); b.idx.add(c)
                    }
                }
                polyIdx++
                start = k + 1
            }
            k++
        }
        if (badIndex > 0) notices.add(Notice(Notice.Severity.WARNING, "fbx-idx", "$badIndex índices de vértice fuera de rango ignorados en una malla FBX."))
        val out = ArrayList<Primitive3D>()
        for ((mi, b) in buckets) {
            if (b.idx.size == 0) continue
            val p = b.p.toArray(); val idx = b.idx.toArray()
            var nr = if (normals != null) b.n.toArray() else null
            if (nr == null) nr = ObjReader.computeFlatSafeNormals(p, idx)
            else {
                var zero = false
                for (i in 0 until nr.size / 3) if (nr[i * 3] == 0f && nr[i * 3 + 1] == 0f && nr[i * 3 + 2] == 0f) { zero = true; break }
                if (zero) { val c = ObjReader.computeFlatSafeNormals(p, idx); for (i in 0 until nr.size / 3) if (nr[i * 3] == 0f && nr[i * 3 + 1] == 0f && nr[i * 3 + 2] == 0f) { nr[i * 3] = c[i * 3]; nr[i * 3 + 1] = c[i * 3 + 1]; nr[i * 3 + 2] = c[i * 3 + 2] } }
            }
            out.add(Primitive3D(positions = p, indices = idx, normals = nr, uv0 = if (uvs != null) b.t.toArray() else null,
                colors = if (colors != null) b.c.toArray() else null, materialIndex = mi))
        }
        return out
    }
}
