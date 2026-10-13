package app.universal3d.core.view

import app.universal3d.core.math.*
import app.universal3d.core.model.*
import kotlin.math.*

/** Evalúa animaciones glTF y calcula matrices de huesos. Independiente de OpenGL. */
object AnimationEvaluator {
    /** Matrices locales de todos los nodos en el instante [t] (segundos) de [anim]; null = pose de reposo. */
    fun localMatrices(scene: Scene3D, anim: Animation3D?, t: Float): Array<Mat4> {
        val n = scene.nodes.size
        val tr = Array(n) { scene.nodes[it].translation.copyOf() }
        val rot = Array(n) { scene.nodes[it].rotation.copyOf() }
        val sc = Array(n) { scene.nodes[it].scale.copyOf() }
        if (anim != null) {
            for (c in anim.channels) {
                if (c.nodeIndex !in 0 until n) continue
                val v = sample(c.sampler, c.path, t) ?: continue
                when (c.path) {
                    AnimPath.TRANSLATION -> tr[c.nodeIndex] = v
                    AnimPath.ROTATION -> rot[c.nodeIndex] = Quat.normalize(v)
                    AnimPath.SCALE -> sc[c.nodeIndex] = v
                    AnimPath.WEIGHTS -> {}
                }
            }
        }
        return Array(n) { Mat4.fromTrs(tr[it], rot[it], sc[it]) }
    }

    fun sample(s: AnimSampler, path: AnimPath, t: Float): FloatArray? {
        val times = s.times
        if (times.isEmpty()) return null
        val comps = if (path == AnimPath.ROTATION) 4 else 3
        val stride = if (s.interpolation == Interpolation.CUBICSPLINE) comps * 3 else comps
        fun value(i: Int): FloatArray {
            val base = if (s.interpolation == Interpolation.CUBICSPLINE) i * stride + comps else i * stride
            if (base + comps > s.values.size) return FloatArray(comps)
            return s.values.copyOfRange(base, base + comps)
        }
        if (t <= times[0]) return value(0)
        if (t >= times[times.size - 1]) return value(times.size - 1)
        var hi = 1
        while (hi < times.size && times[hi] < t) hi++
        val lo = hi - 1
        val dt = times[hi] - times[lo]
        val f = if (dt > 0f) (t - times[lo]) / dt else 0f
        val a = value(lo); val b = value(hi)
        return when (s.interpolation) {
            Interpolation.STEP -> a
            Interpolation.LINEAR -> if (path == AnimPath.ROTATION) Quat.slerp(a, b, f) else FloatArray(comps) { a[it] + (b[it] - a[it]) * f }
            Interpolation.CUBICSPLINE -> {
                val outT = FloatArray(comps) { s.values[lo * stride + comps * 2 + it] }
                val inT = FloatArray(comps) { s.values[hi * stride + it] }
                val f2 = f * f; val f3 = f2 * f
                val r = FloatArray(comps) { c ->
                    (2 * f3 - 3 * f2 + 1) * a[c] + (f3 - 2 * f2 + f) * dt * outT[c] + (-2 * f3 + 3 * f2) * b[c] + (f3 - f2) * dt * inT[c]
                }
                if (path == AnimPath.ROTATION) Quat.normalize(r) else r
            }
        }
    }

    /** Matrices de articulación (joint matrices) de [skin] para el nodo de malla [meshNode]. */
    fun jointMatrices(scene: Scene3D, skin: Skin3D, world: Array<Mat4>, meshNode: Int): FloatArray {
        val inv = world.getOrNull(meshNode)?.inverse() ?: Mat4.identity()
        val out = FloatArray(skin.joints.size * 16)
        for ((j, nodeIdx) in skin.joints.withIndex()) {
            val w = world.getOrNull(nodeIdx) ?: Mat4.identity()
            val ibm = skin.inverseBindMatrices?.takeIf { it.size >= (j + 1) * 16 }?.let { Mat4(it.copyOfRange(j * 16, j * 16 + 16)) } ?: Mat4.identity()
            val m = inv * w * ibm
            System.arraycopy(m.m, 0, out, j * 16, 16)
        }
        return out
    }
}

/** Cámara orbital con zoom, rotación y desplazamiento. Sin dependencias de Android. */
class OrbitCamera {
    var yaw = 0.6f
    var pitch = 0.3f
    var distance = 3f
    val target = floatArrayOf(0f, 0f, 0f)
    var fovY = (45.0 * PI / 180.0).toFloat()
    var near = 0.01f
    var far = 100f
    private var homeTarget = floatArrayOf(0f, 0f, 0f)
    private var homeDistance = 3f
    private var radius = 1f

    /** Encuadra automáticamente el volumen indicado. */
    fun frame(b: Aabb) {
        if (b.isEmpty) return
        radius = max(b.radius, 1e-4f)
        target[0] = b.centerX; target[1] = b.centerY; target[2] = b.centerZ
        distance = radius / sin(fovY / 2f) * 1.1f
        near = max(distance - radius * 4f, distance * 0.01f).coerceAtLeast(1e-4f)
        far = distance + radius * 6f
        homeTarget = target.copyOf(); homeDistance = distance
    }

    fun reset() {
        yaw = 0.6f; pitch = 0.3f; distance = homeDistance
        target[0] = homeTarget[0]; target[1] = homeTarget[1]; target[2] = homeTarget[2]
        updateClip()
    }

    private fun updateClip() { near = max(distance - radius * 4f, distance * 0.01f).coerceAtLeast(1e-4f); far = distance + radius * 6f }

    fun rotate(dx: Float, dy: Float) {
        yaw -= dx * 0.01f
        pitch = (pitch + dy * 0.01f).coerceIn(-1.5f, 1.5f)
    }

    fun zoom(scale: Float) {
        distance = (distance / scale.coerceIn(0.2f, 5f)).coerceIn(radius * 0.05f, radius * 40f)
        updateClip()
    }

    /** Desplazamiento en píxeles con la altura de pantalla dada. */
    fun pan(dxPx: Float, dyPx: Float, viewportHeight: Int) {
        val worldPerPx = 2f * distance * tan(fovY / 2f) / max(viewportHeight, 1)
        val right = floatArrayOf(cos(yaw), 0f, -sin(yaw))
        val up = floatArrayOf(-sin(pitch) * sin(yaw), cos(pitch), -sin(pitch) * cos(yaw))
        for (i in 0..2) target[i] += (-dxPx * right[i] + dyPx * up[i]) * worldPerPx
    }

    fun eye(): FloatArray = floatArrayOf(
        target[0] + distance * cos(pitch) * sin(yaw),
        target[1] + distance * sin(pitch),
        target[2] + distance * cos(pitch) * cos(yaw),
    )

    fun view(): Mat4 = Mat4.lookAt(eye(), target, floatArrayOf(0f, 1f, 0f))
    fun projection(aspect: Float): Mat4 = Mat4.perspective(fovY, aspect, near, far)
}

/** Selección de partes por rayo. */
object RayPicker {
    class Hit(val nodeIndex: Int, val distance: Float)

    /** Rayo (origen+dirección en mundo) contra cada nodo con malla (AABB y luego triángulos). */
    fun pick(scene: Scene3D, world: Array<Mat4>, origin: FloatArray, dir: FloatArray, visible: (Int) -> Boolean = { true }): Hit? {
        var best: Hit? = null
        for ((i, n) in scene.nodes.withIndex()) {
            if (n.meshIndex < 0 || !visible(i)) continue
            val inv = world[i].inverse() ?: continue
            val o = inv.transformPoint(origin[0], origin[1], origin[2])
            val d = inv.transformDirection(dir[0], dir[1], dir[2])
            for (p in scene.meshes[n.meshIndex].primitives) {
                val b = p.bounds()
                if (!rayAabb(o, d, b)) continue
                val t = rayMesh(o, d, p) ?: continue
                // distancia en mundo: reescala por la longitud de la dirección transformada
                val wp = world[i].transformPoint(o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t)
                val dist = sqrt((wp[0] - origin[0]).pow(2) + (wp[1] - origin[1]).pow(2) + (wp[2] - origin[2]).pow(2))
                if (best == null || dist < best.distance) best = Hit(i, dist)
            }
        }
        return best
    }

    fun rayAabb(o: FloatArray, d: FloatArray, b: Aabb): Boolean {
        if (b.isEmpty) return false
        var tmin = Float.NEGATIVE_INFINITY; var tmax = Float.POSITIVE_INFINITY
        val mn = floatArrayOf(b.minX, b.minY, b.minZ); val mx = floatArrayOf(b.maxX, b.maxY, b.maxZ)
        for (a in 0..2) {
            if (abs(d[a]) < 1e-12f) { if (o[a] < mn[a] || o[a] > mx[a]) return false } else {
                var t1 = (mn[a] - o[a]) / d[a]; var t2 = (mx[a] - o[a]) / d[a]
                if (t1 > t2) { val s = t1; t1 = t2; t2 = s }
                tmin = max(tmin, t1); tmax = min(tmax, t2)
                if (tmin > tmax) return false
            }
        }
        return tmax >= 0
    }

    /** Möller–Trumbore. Devuelve el menor t > 0. */
    fun rayMesh(o: FloatArray, d: FloatArray, p: Primitive3D): Float? {
        var best = Float.POSITIVE_INFINITY
        val pos = p.positions; val idx = p.indices
        var i = 0
        while (i + 2 < idx.size) {
            val a = idx[i] * 3; val b = idx[i + 1] * 3; val c = idx[i + 2] * 3
            i += 3
            val e1x = pos[b] - pos[a]; val e1y = pos[b + 1] - pos[a + 1]; val e1z = pos[b + 2] - pos[a + 2]
            val e2x = pos[c] - pos[a]; val e2y = pos[c + 1] - pos[a + 1]; val e2z = pos[c + 2] - pos[a + 2]
            val px = d[1] * e2z - d[2] * e2y; val py = d[2] * e2x - d[0] * e2z; val pz = d[0] * e2y - d[1] * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) < 1e-12f) continue
            val inv = 1f / det
            val tx = o[0] - pos[a]; val ty = o[1] - pos[a + 1]; val tz = o[2] - pos[a + 2]
            val u = (tx * px + ty * py + tz * pz) * inv
            if (u < 0f || u > 1f) continue
            val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
            val v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv
            if (v < 0f || u + v > 1f) continue
            val t = (e2x * qx + e2y * qy + e2z * qz) * inv
            if (t > 1e-6f && t < best) best = t
        }
        return if (best.isFinite()) best else null
    }

    /** Rayo de pantalla (píxeles) en espacio mundo. */
    fun screenRay(x: Float, y: Float, width: Int, height: Int, view: Mat4, proj: Mat4): Pair<FloatArray, FloatArray>? {
        val inv = (proj * view).inverse() ?: return null
        val nx = 2f * x / width - 1f; val ny = 1f - 2f * y / height
        val p0 = inv.transformPoint(nx, ny, -1f); val p1 = inv.transformPoint(nx, ny, 1f)
        val d = floatArrayOf(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2])
        val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).let { if (it == 0f) 1f else it }
        return p0 to floatArrayOf(d[0] / l, d[1] / l, d[2] / l)
    }
}
