package app.universal3d.inspector.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import app.universal3d.core.math.Aabb
import app.universal3d.core.math.Mat4
import app.universal3d.core.model.*
import app.universal3d.core.view.AnimationEvaluator
import app.universal3d.core.view.OrbitCamera
import app.universal3d.core.view.RayPicker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

enum class WireMode { SOLID, WIRE, BOTH }

class NodeLabel(val nodeIndex: Int, val text: String, val x: Float, val y: Float)

class RenderStats(val meshes: Int, val triangles: Long, val visibleTriangles: Long, val gpuBytes: Long, val fps: Float?, val warnings: List<String>)

interface RendererCallbacks {
    fun onStats(stats: RenderStats)
    fun onError(message: String)
    fun onPicked(nodeIndex: Int)
    fun onLabels(labels: List<NodeLabel>)
}

/**
 * Renderizador OpenGL ES 3.0 ligero: PBR simplificado, esqueletos en GPU, animaciones por nodo, alámbrico y selección.
 * Toda la manipulación de GL ocurre en el hilo del GLSurfaceView; el estado compartido se protege con [lock].
 */
class ModelRenderer(private val view: GLSurfaceView, private val cb: RendererCallbacks) : GLSurfaceView.Renderer {
    private val lock = Any()

    // ---- estado compartido (UI -> GL) ----
    private var pendingScene: Scene3D? = null
    private var sceneDirty = false
    private var maxTexture = 2048
    val camera = OrbitCamera()
    private var wire = WireMode.SOLID
    private var bg = floatArrayOf(0.09f, 0.10f, 0.12f)
    private var visible = BooleanArray(0)
    private var selected = -1
    private var animIndex = -1
    private var playing = false
    private var animTime = 0f
    private var speed = 1f
    private var showLabels = false
    private var pendingPick: FloatArray? = null
    private var framePending = false

    // ---- estado GL ----
    private var scene: Scene3D? = null
    private var pbr = 0; private var lineProg = 0
    private var maxJoints = 64
    private var width = 1; private var height = 1
    private val gpuMeshes = ArrayList<Array<GpuPrim>>()
    private val texCache = HashMap<Int, Int>()
    private var whiteTex = 0
    private var gpuBytes = 0L
    private var warnings = ArrayList<String>()
    private var restWorld: Array<Mat4> = emptyArray()
    private var curWorld: Array<Mat4> = emptyArray()
    private var lastNanos = 0L
    private var fpsAccum = 0; private var fpsStart = 0L; private var fps: Float? = null
    private var lastLabelPush = 0L
    private var nodeCenters: Array<FloatArray?> = emptyArray()
    private var sceneBounds = Aabb()
    private var u = HashMap<String, Int>()
    private var ul = HashMap<String, Int>()

    private class GpuPrim(
        val vao: Int, val vbos: IntArray, val ibo: Int, val indexCount: Int, val indexType: Int, val material: Int,
        val hasNormals: Boolean, val hasColors: Boolean, val skinnable: Boolean, val prim: Primitive3D, var edgeIbo: Int = 0, var edgeCount: Int = 0, var edgeFailed: Boolean = false,
    )

    // ============ API de hilo UI ============
    fun setScene(s: Scene3D?, maxTextureSize: Int) = synchronized(lock) {
        pendingScene = s; sceneDirty = true; maxTexture = maxTextureSize; animIndex = if (s?.animations?.isNotEmpty() == true) 0 else -1
        animTime = 0f; playing = s?.animations?.isNotEmpty() == true; selected = -1
        s?.let { visible = BooleanArray(it.nodes.size) { true } }
        s?.let { camera.frame(it.bounds()) }
        requestRender()
    }
    fun setWireMode(m: WireMode) = synchronized(lock) { wire = m; requestRender() }
    fun setBackground(r: Float, g: Float, b: Float) = synchronized(lock) { bg = floatArrayOf(r, g, b); requestRender() }
    fun setNodeVisible(node: Int, v: Boolean) = synchronized(lock) { if (node in visible.indices) visible[node] = v; requestRender() }
    fun setAllVisible(v: Boolean) = synchronized(lock) { visible.fill(v); requestRender() }
    fun setSelected(node: Int) = synchronized(lock) { selected = node; requestRender() }
    fun setLabels(on: Boolean) = synchronized(lock) { showLabels = on; if (!on) cb.onLabels(emptyList()); requestRender() }
    fun setAnimation(index: Int, play: Boolean) = synchronized(lock) { if (index != animIndex) animTime = 0f; animIndex = index; playing = play && index >= 0; requestRender() }
    fun setPlaying(p: Boolean) = synchronized(lock) { playing = p && animIndex >= 0; lastNanos = 0; requestRender() }
    fun setSpeed(s: Float) = synchronized(lock) { speed = s }
    fun seek(t: Float) = synchronized(lock) { animTime = t; requestRender() }
    fun currentTime(): Float = synchronized(lock) { animTime }
    fun resetCamera() = synchronized(lock) { camera.reset(); requestRender() }
    fun rotate(dx: Float, dy: Float) = synchronized(lock) { camera.rotate(dx, dy); requestRender() }
    fun zoom(f: Float) = synchronized(lock) { camera.zoom(f); requestRender() }
    fun pan(dx: Float, dy: Float) = synchronized(lock) { camera.pan(dx, dy, height); requestRender() }
    fun pickAt(x: Float, y: Float) = synchronized(lock) { pendingPick = floatArrayOf(x, y); requestRender() }
    fun requestRender() { view.requestRender() }
    fun release() { view.queueEvent { releaseGpu(); scene = null } }

    // ============ GLSurfaceView.Renderer ============
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            val maxVec = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_VERTEX_UNIFORM_VECTORS, maxVec, 0)
            maxJoints = ((maxVec[0] - 48) / 4).coerceIn(16, 96)
            pbr = program(Shaders.pbrVertex(maxJoints), Shaders.PBR_FRAGMENT)
            lineProg = program(Shaders.lineVertex(maxJoints), Shaders.LINE_FRAGMENT)
            u.clear(); ul.clear()
            for (n in listOf("u_viewProj", "u_model", "u_normalMat", "u_skinned", "u_joints", "u_eye", "u_baseColor", "u_metallic", "u_rough", "u_emissive", "u_normalScale", "u_occStrength", "u_alphaCutoff", "u_alphaMode", "u_flags", "u_tint", "u_texBase", "u_texMR", "u_texNormal", "u_texOcc", "u_texEmis")) u[n] = GLES30.glGetUniformLocation(pbr, n)
            for (n in listOf("u_viewProj", "u_model", "u_skinned", "u_joints", "u_color")) ul[n] = GLES30.glGetUniformLocation(lineProg, n)
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0); whiteTex = t[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(byteArrayOf(-1, -1, -1, -1)))
            // los recursos previos se perdieron con el contexto
            gpuMeshes.clear(); texCache.clear(); gpuBytes = 0
            synchronized(lock) { if (scene != null && pendingScene == null) { pendingScene = scene; sceneDirty = true } }
            scene = null
        } catch (e: Exception) { cb.onError("No se pudo inicializar OpenGL ES 3.0: ${e.message}") }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { width = max(w, 1); height = max(h, 1); GLES30.glViewport(0, 0, width, height) }

    override fun onDrawFrame(gl: GL10?) {
        try { draw() } catch (e: OutOfMemoryError) { fail("Memoria insuficiente al renderizar el modelo.") } catch (e: Exception) { fail("Error de renderizado: ${e.message}") }
    }

    private fun fail(msg: String) { releaseGpu(); scene = null; cb.onError(msg) }

    private fun draw() {
        var newScene: Scene3D? = null; var take = false
        var wireMode: WireMode; var bgc: FloatArray; var vis: BooleanArray; var sel: Int; var ai: Int; var isPlaying: Boolean; var t0: Float; var labels: Boolean; var pick: FloatArray?
        val view: Mat4; val proj: Mat4; val eye: FloatArray
        synchronized(lock) {
            if (sceneDirty) { newScene = pendingScene; take = true; sceneDirty = false; pendingScene = null }
            wireMode = wire; bgc = bg; vis = visible; sel = selected; ai = animIndex; isPlaying = playing; labels = showLabels; pick = pendingPick; pendingPick = null
            val now = System.nanoTime()
            if (isPlaying && lastNanos != 0L) animTime += (now - lastNanos) / 1e9f * speed
            lastNanos = if (isPlaying) now else 0L
            val dur = scene?.animations?.getOrNull(ai)?.duration ?: 0f
            if (dur > 0f && animTime > dur) animTime %= dur
            t0 = animTime
            view = camera.view(); proj = camera.projection(width.toFloat() / height); eye = camera.eye()
        }
        if (take) { releaseGpu(); scene = newScene; newScene?.let { uploadScene(it) } }
        GLES30.glClearColor(bgc[0], bgc[1], bgc[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val sc = scene ?: return
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        val anim = sc.animations.getOrNull(ai)
        val world = if (anim != null) { curWorld = SceneEvalFor(sc, AnimationEvaluator.localMatrices(sc, anim, t0)); curWorld } else restWorld
        val vp = proj * view
        if (pick != null) doPick(sc, world, pick!!, vis, view, proj)

        // clasificación opaco / transparente
        val blend = ArrayList<Triple<Int, GpuPrim, Float>>()
        var visTris = 0L
        if (wireMode != WireMode.WIRE) {
            GLES30.glUseProgram(pbr)
            GLES30.glUniformMatrix4fv(u["u_viewProj"]!!, 1, false, vp.m, 0)
            GLES30.glUniform3f(u["u_eye"]!!, eye[0], eye[1], eye[2])
        }
        for ((ni, node) in sc.nodes.withIndex()) {
            if (node.meshIndex < 0 || !vis.getOrElse(ni) { true }) continue
            val prims = gpuMeshes.getOrNull(node.meshIndex) ?: continue
            for (p in prims) {
                visTris += p.indexCount / 3
                val mat = sc.materials.getOrNull(p.material)
                if (mat?.alphaMode == AlphaMode.BLEND) {
                    val c = world[ni].transformPoint(0f, 0f, 0f)
                    blend.add(Triple(ni, p, (c[0] - eye[0]).let { it * it } + (c[1] - eye[1]).let { it * it } + (c[2] - eye[2]).let { it * it }))
                } else if (wireMode != WireMode.WIRE) drawPrim(sc, ni, p, world, sel == ni, false)
            }
        }
        if (wireMode != WireMode.WIRE && blend.isNotEmpty()) {
            GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA); GLES30.glDepthMask(false)
            for ((ni, p, _) in blend.sortedByDescending { it.third }) drawPrim(sc, ni, p, world, sel == ni, true)
            GLES30.glDepthMask(true); GLES30.glDisable(GLES30.GL_BLEND)
        }
        if (wireMode != WireMode.SOLID || sel >= 0) drawWire(sc, world, vp, vis, wireMode, sel)
        if (labels) pushLabels(sc, world, vp, vis)
        reportStats(sc, visTris, isPlaying)
        val err = GLES30.glGetError()
        if (err == GLES30.GL_OUT_OF_MEMORY) fail("La GPU se quedó sin memoria. Probá reducir el tamaño máximo de textura en Ajustes.")
        if (isPlaying) glView().requestRender()
    }

    private fun glView() = this.view

    @Suppress("FunctionName")
    private fun SceneEvalFor(sc: Scene3D, local: Array<Mat4>): Array<Mat4> = app.universal3d.core.model.SceneEval.worldMatrices(sc, local)

    // ---------------- subida de recursos ----------------
    private fun uploadScene(sc: Scene3D) {
        warnings = ArrayList()
        restWorld = sc.worldMatrices(); curWorld = restWorld
        sceneBounds = sc.bounds()
        nodeCenters = Array(sc.nodes.size) { i ->
            val n = sc.nodes[i]
            if (n.meshIndex < 0) null else {
                val b = Aabb(); sc.meshes[n.meshIndex].primitives.forEach { b.add(it.bounds()) }
                if (b.isEmpty) null else floatArrayOf(b.centerX, b.centerY, b.centerZ)
            }
        }
        for (mesh in sc.meshes) {
            gpuMeshes.add(Array(mesh.primitives.size) { createPrim(sc, mesh.primitives[it]) })
        }
        // texturas: precarga con límite de tamaño
        for (img in sc.images.indices) if (sc.textures.any { it.imageIndex == img }) texFor(sc, img)
        val err = GLES30.glGetError()
        if (err == GLES30.GL_OUT_OF_MEMORY) throw OutOfMemoryError("GPU")
        if (sc.skins.any { it.joints.size > maxJoints }) warnings.add("Algún esqueleto tiene más de $maxJoints huesos: se muestra sin deformación.")
    }

    private fun vbo(data: FloatArray, loc: Int, comps: Int): Int {
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()); bb.asFloatBuffer().put(data)
        val id = IntArray(1); GLES30.glGenBuffers(1, id, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id[0]); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, bb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(loc); GLES30.glVertexAttribPointer(loc, comps, GLES30.GL_FLOAT, false, 0, 0)
        gpuBytes += data.size * 4L
        return id[0]
    }

    private fun createPrim(sc: Scene3D, p: Primitive3D): GpuPrim {
        val vao = IntArray(1); GLES30.glGenVertexArrays(1, vao, 0); GLES30.glBindVertexArray(vao[0])
        val vbos = ArrayList<Int>()
        vbos.add(vbo(p.positions, 0, 3))
        p.normals?.let { vbos.add(vbo(it, 1, 3)) }
        p.uv0?.let { vbos.add(vbo(it, 2, 2)) }
        p.uv1?.let { vbos.add(vbo(it, 3, 2)) }
        p.colors?.let { vbos.add(vbo(it, 4, 4)) }
        val joints = p.joints; val weights = p.weights
        val skin = joints != null && weights != null
        if (joints != null && weights != null) {
            val sb = ByteBuffer.allocateDirect(joints.size * 2).order(ByteOrder.nativeOrder())
            for (j in joints) sb.putShort(j.toShort()); sb.position(0)
            val id = IntArray(1); GLES30.glGenBuffers(1, id, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id[0]); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, joints.size * 2, sb, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(5); GLES30.glVertexAttribIPointer(5, 4, GLES30.GL_UNSIGNED_SHORT, 0, 0)
            vbos.add(id[0]); gpuBytes += joints.size * 2L
            vbos.add(vbo(weights, 6, 4))
        }
        val small = p.vertexCount < 65535
        val ib = ByteBuffer.allocateDirect(p.indices.size * if (small) 2 else 4).order(ByteOrder.nativeOrder())
        if (small) for (i in p.indices) ib.putShort(i.toShort()) else ib.asIntBuffer().put(p.indices)
        ib.position(0)
        val ibo = IntArray(1); GLES30.glGenBuffers(1, ibo, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, p.indices.size * if (small) 2 else 4, ib, GLES30.GL_STATIC_DRAW)
        gpuBytes += p.indices.size * if (small) 2L else 4L
        GLES30.glBindVertexArray(0)
        return GpuPrim(vao[0], vbos.toIntArray(), ibo[0], p.indices.size, if (small) GLES30.GL_UNSIGNED_SHORT else GLES30.GL_UNSIGNED_INT, p.materialIndex, p.normals != null, p.colors != null, skin, p)
    }

    private fun texFor(sc: Scene3D, imageIndex: Int): Int {
        texCache[imageIndex]?.let { return it }
        val img = sc.images.getOrNull(imageIndex) ?: return whiteTex
        var bmp: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size, bounds)
            if (bounds.outWidth <= 0) throw IllegalStateException("formato de imagen no soportado")
            val limit = minOf(maxTexture, 4096)
            var sample = 1
            while (bounds.outWidth / sample > limit || bounds.outHeight / sample > limit) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = false }
            bmp = BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size, opts) ?: throw IllegalStateException("no se pudo decodificar")
            val id = IntArray(1); GLES30.glGenTextures(1, id, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            gpuBytes += bmp.width.toLong() * bmp.height * 4 * 4 / 3
            if (sample > 1) warnings.add("Textura '${img.name}' reducida ${sample}× para ahorrar memoria.")
            texCache[imageIndex] = id[0]
            return id[0]
        } catch (e: Exception) {
            warnings.add("No se pudo cargar la textura '${img.name ?: imageIndex}': ${e.message}")
            texCache[imageIndex] = whiteTex
            return whiteTex
        } finally { bmp?.recycle() }
    }

    private fun releaseGpu() {
        for (arr in gpuMeshes) for (p in arr) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(p.vao), 0)
            GLES30.glDeleteBuffers(p.vbos.size, p.vbos, 0); GLES30.glDeleteBuffers(1, intArrayOf(p.ibo), 0)
            if (p.edgeIbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(p.edgeIbo), 0)
        }
        gpuMeshes.clear()
        val ids = texCache.values.filter { it != whiteTex }.distinct().toIntArray()
        if (ids.isNotEmpty()) GLES30.glDeleteTextures(ids.size, ids, 0)
        texCache.clear(); gpuBytes = 0
    }

    // ---------------- dibujo ----------------
    private fun bindTex(unit: Int, sc: Scene3D, ref: TextureRef?, loc: String): Boolean {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        var ok = false
        var id = whiteTex
        if (ref != null) {
            val tex = sc.textures.getOrNull(ref.textureIndex)
            if (tex != null && tex.imageIndex >= 0) { id = texFor(sc, tex.imageIndex); ok = id != whiteTex }
            if (ok && tex != null) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
                val s = tex.sampler
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap(s.wrapS)); GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap(s.wrapT))
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, if (s.magFilter == 9728) GLES30.GL_NEAREST else GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, when (s.minFilter) { 9728 -> GLES30.GL_NEAREST; 9729 -> GLES30.GL_LINEAR; 9984 -> GLES30.GL_NEAREST_MIPMAP_NEAREST; 9985 -> GLES30.GL_LINEAR_MIPMAP_NEAREST; 9986 -> GLES30.GL_NEAREST_MIPMAP_LINEAR; else -> GLES30.GL_LINEAR_MIPMAP_LINEAR })
            }
        }
        if (!ok) GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTex)
        GLES30.glUniform1i(u[loc]!!, unit)
        return ok
    }

    private fun wrap(w: Int) = when (w) { 33071 -> GLES30.GL_CLAMP_TO_EDGE; 33648 -> GLES30.GL_MIRRORED_REPEAT; else -> GLES30.GL_REPEAT }

    private val normalBuf = FloatArray(9)
    private fun drawPrim(sc: Scene3D, ni: Int, p: GpuPrim, world: Array<Mat4>, selected: Boolean, blendPass: Boolean) {
        val m = sc.materials.getOrNull(p.material)
        val w = world[ni]
        GLES30.glUniformMatrix4fv(u["u_model"]!!, 1, false, w.m, 0)
        val nm = w.inverse()?.transposed()?.m ?: Mat4.identityArray()
        normalBuf[0] = nm[0]; normalBuf[1] = nm[1]; normalBuf[2] = nm[2]; normalBuf[3] = nm[4]; normalBuf[4] = nm[5]; normalBuf[5] = nm[6]; normalBuf[6] = nm[8]; normalBuf[7] = nm[9]; normalBuf[8] = nm[10]
        GLES30.glUniformMatrix3fv(u["u_normalMat"]!!, 1, false, normalBuf, 0)
        GLES30.glFrontFace(if (w.determinant3() < 0f) GLES30.GL_CW else GLES30.GL_CCW)
        val doubleSided = m?.doubleSided ?: false
        if (doubleSided) GLES30.glDisable(GLES30.GL_CULL_FACE) else { GLES30.glEnable(GLES30.GL_CULL_FACE); GLES30.glCullFace(GLES30.GL_BACK) }
        // piel
        var skinned = false
        val node = sc.nodes[ni]
        if (p.skinnable && node.skinIndex >= 0) {
            val skin = sc.skins.getOrNull(node.skinIndex)
            if (skin != null && skin.joints.size <= maxJoints) {
                val jm = AnimationEvaluator.jointMatrices(sc, skin, world, ni)
                GLES30.glUniformMatrix4fv(u["u_joints"]!!, skin.joints.size, false, jm, 0); skinned = true
            }
        }
        GLES30.glUniform1i(u["u_skinned"]!!, if (skinned) 1 else 0)
        var flags = 0
        val bc = m?.baseColorFactor ?: floatArrayOf(0.8f, 0.8f, 0.8f, 1f)
        GLES30.glUniform4f(u["u_baseColor"]!!, bc[0], bc[1], bc[2], bc[3])
        GLES30.glUniform1f(u["u_metallic"]!!, m?.metallicFactor ?: 0f)
        GLES30.glUniform1f(u["u_rough"]!!, m?.roughnessFactor ?: 0.8f)
        val em = m?.emissiveFactor ?: floatArrayOf(0f, 0f, 0f)
        GLES30.glUniform3f(u["u_emissive"]!!, em[0], em[1], em[2])
        GLES30.glUniform1f(u["u_normalScale"]!!, m?.normalScale ?: 1f)
        GLES30.glUniform1f(u["u_occStrength"]!!, m?.occlusionStrength ?: 1f)
        GLES30.glUniform1f(u["u_alphaCutoff"]!!, m?.alphaCutoff ?: 0.5f)
        GLES30.glUniform1i(u["u_alphaMode"]!!, when (m?.alphaMode) { AlphaMode.MASK -> 1; AlphaMode.BLEND -> 2; else -> 0 })
        if (bindTex(0, sc, m?.baseColorTexture, "u_texBase") && p.prim.uv0 != null) { flags = flags or 1; if (m?.baseColorTexture?.texCoord == 1) flags = flags or 128 }
        if (bindTex(1, sc, m?.metallicRoughnessTexture, "u_texMR") && p.prim.uv0 != null) { flags = flags or 2; if (m?.metallicRoughnessTexture?.texCoord == 1) flags = flags or 256 }
        if (bindTex(2, sc, m?.normalTexture, "u_texNormal") && p.prim.uv0 != null) { flags = flags or 4; if (m?.normalTexture?.texCoord == 1) flags = flags or 512 }
        if (bindTex(3, sc, m?.occlusionTexture, "u_texOcc") && p.prim.uv0 != null) { flags = flags or 8; if (m?.occlusionTexture?.texCoord == 1) flags = flags or 1024 }
        if (bindTex(4, sc, m?.emissiveTexture, "u_texEmis") && p.prim.uv0 != null) { flags = flags or 16; if (m?.emissiveTexture?.texCoord == 1) flags = flags or 2048 }
        if (p.hasColors) flags = flags or 32
        if (p.hasNormals) flags = flags or 64
        GLES30.glUniform1i(u["u_flags"]!!, flags)
        val tint = if (selected) 0.10f else 0f
        GLES30.glUniform3f(u["u_tint"]!!, tint, tint * 0.8f, 0f)
        GLES30.glBindVertexArray(p.vao)
        // atributos ausentes: valores constantes seguros
        if (p.prim.colors == null) GLES30.glVertexAttrib4f(4, 1f, 1f, 1f, 1f)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, p.indexCount, p.indexType, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun buildEdges(p: GpuPrim): Boolean {
        if (p.edgeIbo != 0) return true
        if (p.edgeFailed) return false
        val tri = p.prim.triangleCount
        if (tri > 400_000) { p.edgeFailed = true; warnings.add("Alámbrico no disponible para mallas de más de 400 000 triángulos."); return false }
        val keys = LongArray(tri * 3); var n = 0
        val idx = p.prim.indices
        var i = 0
        while (i + 2 < idx.size) {
            for (e in 0..2) { val a = idx[i + e]; val b = idx[i + (e + 1) % 3]; keys[n++] = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong() }
            i += 3
        }
        keys.sort()
        var uniq = 0
        val out = IntArray(n * 2)
        for (k in 0 until n) { if (k > 0 && keys[k] == keys[k - 1]) continue; out[uniq * 2] = (keys[k] ushr 32).toInt(); out[uniq * 2 + 1] = (keys[k] and 0xFFFFFFFFL).toInt(); uniq++ }
        val small = p.prim.vertexCount < 65535
        val bb = ByteBuffer.allocateDirect(uniq * 2 * if (small) 2 else 4).order(ByteOrder.nativeOrder())
        for (k in 0 until uniq * 2) if (small) bb.putShort(out[k].toShort()) else bb.putInt(out[k])
        bb.position(0)
        val id = IntArray(1); GLES30.glGenBuffers(1, id, 0)
        GLES30.glBindVertexArray(p.vao)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, id[0]); GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, uniq * 2 * if (small) 2 else 4, bb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, p.ibo) // restaurar el VAO a sus índices de triángulos
        GLES30.glBindVertexArray(0)
        p.edgeIbo = id[0]; p.edgeCount = uniq * 2; gpuBytes += uniq * 2L * if (small) 2 else 4
        return true
    }

    private fun drawWire(sc: Scene3D, world: Array<Mat4>, vp: Mat4, vis: BooleanArray, mode: WireMode, sel: Int) {
        GLES30.glUseProgram(lineProg)
        GLES30.glUniformMatrix4fv(ul["u_viewProj"]!!, 1, false, vp.m, 0)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL); GLES30.glDisable(GLES30.GL_CULL_FACE)
        for ((ni, node) in sc.nodes.withIndex()) {
            if (node.meshIndex < 0 || !vis.getOrElse(ni) { true }) continue
            val isSel = ni == sel
            if (mode == WireMode.SOLID && !isSel) continue
            val prims = gpuMeshes.getOrNull(node.meshIndex) ?: continue
            for (p in prims) {
                if (!buildEdges(p)) continue
                GLES30.glUniformMatrix4fv(ul["u_model"]!!, 1, false, world[ni].m, 0)
                var skinned = false
                if (p.skinnable && node.skinIndex >= 0) {
                    val skin = sc.skins.getOrNull(node.skinIndex)
                    if (skin != null && skin.joints.size <= maxJoints) { GLES30.glUniformMatrix4fv(ul["u_joints"]!!, skin.joints.size, false, AnimationEvaluator.jointMatrices(sc, skin, world, ni), 0); skinned = true }
                }
                GLES30.glUniform1i(ul["u_skinned"]!!, if (skinned) 1 else 0)
                if (isSel) GLES30.glUniform4f(ul["u_color"]!!, 1f, 0.85f, 0.1f, 1f)
                else if (mode == WireMode.WIRE) GLES30.glUniform4f(ul["u_color"]!!, 0.75f, 0.9f, 1f, 1f) else GLES30.glUniform4f(ul["u_color"]!!, 0f, 0f, 0f, 0.55f)
                if (mode == WireMode.BOTH && !isSel) { GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA) }
                GLES30.glPolygonOffset(-1f, -1f)
                GLES30.glBindVertexArray(p.vao)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, p.edgeIbo)
                GLES30.glDrawElements(GLES30.GL_LINES, p.edgeCount, p.indexType, 0)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, p.ibo)
                GLES30.glBindVertexArray(0)
                GLES30.glDisable(GLES30.GL_BLEND)
            }
        }
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
    }

    private fun doPick(sc: Scene3D, world: Array<Mat4>, xy: FloatArray, vis: BooleanArray, view: Mat4, proj: Mat4) {
        val ray = RayPicker.screenRay(xy[0], xy[1], width, height, view, proj) ?: return
        val hit = RayPicker.pick(sc, world, ray.first, ray.second) { vis.getOrElse(it) { true } }
        glView().post { cb.onPicked(hit?.nodeIndex ?: -1) }
    }

    private fun pushLabels(sc: Scene3D, world: Array<Mat4>, vp: Mat4, vis: BooleanArray) {
        val now = System.nanoTime()
        if (now - lastLabelPush < 120_000_000L) return
        lastLabelPush = now
        val out = ArrayList<NodeLabel>()
        for ((i, c) in nodeCenters.withIndex()) {
            c ?: continue
            if (!vis.getOrElse(i) { true }) continue
            val wp = world[i].transformPoint(c[0], c[1], c[2])
            val clip = vp.transformPoint(wp[0], wp[1], wp[2])
            // transformPoint divide por w; descartar los que quedan detrás de la cámara
            val w = vp.m[3] * wp[0] + vp.m[7] * wp[1] + vp.m[11] * wp[2] + vp.m[15]
            if (w <= 0f || clip[0] < -1f || clip[0] > 1f || clip[1] < -1f || clip[1] > 1f) continue
            out.add(NodeLabel(i, sc.nodes[i].name ?: sc.meshes[sc.nodes[i].meshIndex].name ?: "Nodo $i", (clip[0] * 0.5f + 0.5f) * width, (1f - (clip[1] * 0.5f + 0.5f)) * height))
            if (out.size >= 40) break
        }
        glView().post { cb.onLabels(out) }
    }

    private fun reportStats(sc: Scene3D, visTris: Long, continuous: Boolean) {
        val now = System.nanoTime()
        if (continuous) {
            if (fpsStart == 0L) { fpsStart = now; fpsAccum = 0 }
            fpsAccum++
            if (now - fpsStart >= 1_000_000_000L) { fps = fpsAccum * 1e9f / (now - fpsStart); fpsStart = now; fpsAccum = 0; pushStats(sc, visTris) }
        } else { fpsStart = 0L; if (fps != null) { fps = null; pushStats(sc, visTris) } }
        if (statsDirty) { statsDirty = false; pushStats(sc, visTris) }
    }

    private var statsDirty = true
    private fun pushStats(sc: Scene3D, visTris: Long) {
        val total = sc.meshes.sumOf { m -> m.primitives.sumOf { it.triangleCount.toLong() } }
        val s = RenderStats(sc.meshes.size, total, visTris, gpuBytes, fps, warnings.toList())
        glView().post { cb.onStats(s) }
    }

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { val log = GLES30.glGetShaderInfoLog(s); GLES30.glDeleteShader(s); throw IllegalStateException("shader: $log") }
            return s
        }
        val p = GLES30.glCreateProgram()
        val v = sh(GLES30.GL_VERTEX_SHADER, vs); val f = sh(GLES30.GL_FRAGMENT_SHADER, fs)
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("enlace: ${GLES30.glGetProgramInfoLog(p)}")
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        return p
    }
}
