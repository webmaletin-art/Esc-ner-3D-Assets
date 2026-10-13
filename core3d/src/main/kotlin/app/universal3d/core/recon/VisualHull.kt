package app.universal3d.core.recon

import app.universal3d.core.io.ObjReader
import app.universal3d.core.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.*

/**
 * Vista para reconstrucción por casco visual (silueta) con cámara ortográfica y mesa giratoria:
 * [angleRad] es el giro del objeto respecto de la cámara. [rgb] opcional (3 bytes/píxel) para el color de vértices.
 */
class SilhouetteView(val width: Int, val height: Int, val mask: BooleanArray, val rgb: ByteArray?, val angleRad: Float) {
    init { require(mask.size == width * height) { "máscara de tamaño incorrecto" } }
    val bbox: IntArray? by lazy {
        var x0 = width; var y0 = height; var x1 = -1; var y1 = -1
        for (y in 0 until height) for (x in 0 until width) if (mask[y * width + x]) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        if (x1 < 0) null else intArrayOf(x0, y0, x1, y1)
    }
}

object SilhouetteExtractor {
    /** Máscara por canal alfa si hay transparencia; si no, por distancia de color al fondo estimado en los bordes. */
    fun fromRgba(width: Int, height: Int, rgba: ByteArray, angleRad: Float, tolerance: Int = 40): SilhouetteView {
        val n = width * height
        var transparent = 0
        for (i in 0 until n) if ((rgba[i * 4 + 3].toInt() and 0xFF) < 250) transparent++
        val mask = BooleanArray(n)
        val rgb = ByteArray(n * 3)
        for (i in 0 until n) { rgb[i * 3] = rgba[i * 4]; rgb[i * 3 + 1] = rgba[i * 4 + 1]; rgb[i * 3 + 2] = rgba[i * 4 + 2] }
        if (transparent > n / 50) {
            for (i in 0 until n) mask[i] = (rgba[i * 4 + 3].toInt() and 0xFF) > 127
        } else {
            // fondo = mediana de los píxeles del borde
            val border = ArrayList<IntArray>()
            for (x in 0 until width) { border.add(px(rgba, x)); border.add(px(rgba, (height - 1) * width + x)) }
            for (y in 0 until height) { border.add(px(rgba, y * width)); border.add(px(rgba, y * width + width - 1)) }
            val bg = IntArray(3) { c -> border.map { it[c] }.sorted()[border.size / 2] }
            val tol2 = tolerance * tolerance * 3
            for (i in 0 until n) {
                val dr = (rgba[i * 4].toInt() and 0xFF) - bg[0]; val dg = (rgba[i * 4 + 1].toInt() and 0xFF) - bg[1]; val db = (rgba[i * 4 + 2].toInt() and 0xFF) - bg[2]
                mask[i] = dr * dr + dg * dg + db * db > tol2
            }
        }
        return SilhouetteView(width, height, mask, rgb, angleRad)
    }

    private fun px(a: ByteArray, i: Int) = intArrayOf(a[i * 4].toInt() and 0xFF, a[i * 4 + 1].toInt() and 0xFF, a[i * 4 + 2].toInt() and 0xFF)
}

class HullConfig(val resolution: Int = 64, val smoothingIterations: Int = 2, val minViews: Int = 6, val maxResolution: Int = 160)

class HullResult(val scene: Scene3D, val voxelCount: Int, val occupancy: Float, val notes: List<String>)

class HullException(message: String) : Exception(message)

/**
 * Reconstrucción aproximada por tallado de voxels (visual hull) + superficie por "surface nets".
 * Limitaciones (se informan al usuario): sólo recupera la envolvente convexa por silueta (no concavidades), exige vistas
 * a intervalos de giro conocidos, fondo uniforme/transparente y cámara ortográfica aproximada; escala normalizada.
 */
object VisualHullReconstructor {
    suspend fun reconstruct(views: List<SilhouetteView>, config: HullConfig = HullConfig(), onProgress: (done: Int, total: Int, phase: String) -> Unit = { _, _, _ -> }): HullResult {
        if (views.size < config.minViews) throw HullException("Se necesitan al menos ${config.minViews} vistas (hay ${views.size}). Con menos ángulos la forma queda demasiado indefinida.")
        if (views.any { it.bbox == null }) throw HullException("Alguna vista no contiene objeto detectable (silueta vacía). Revisá el fondo/contraste.")
        val n = config.resolution.coerceIn(16, config.maxResolution)
        // Escala común: extensión máxima del objeto en píxeles; centro: media de centros de bbox
        var maxExtent = 1f; var cx = 0f; var cy = 0f
        for (v in views) { val b = v.bbox!!; maxExtent = max(maxExtent, max(b[2] - b[0] + 1, b[3] - b[1] + 1).toFloat()); cx += (b[0] + b[2]) / 2f / views.size; cy += (b[1] + b[3]) / 2f / views.size }
        val ref = views[0]
        val half = maxExtent / 2f * 1.08f // margen
        val total = n
        val occ = BooleanArray(n * n * n) { true }
        val cosA = FloatArray(views.size) { cos(views[it].angleRad) }; val sinA = FloatArray(views.size) { sin(views[it].angleRad) }
        // mundo: [-1,1]^3, un voxel = 2/n; px = cx + u*half ; py = cy - y*half
        val ctx = currentCoroutineContext()
        for (iy in 0 until n) {
            ctx.ensureActive()
            val y = -1f + (iy + 0.5f) * 2f / n
            for (iz in 0 until n) {
                val z = -1f + (iz + 0.5f) * 2f / n
                for (ix in 0 until n) {
                    val x = -1f + (ix + 0.5f) * 2f / n
                    val o = (iy * n + iz) * n + ix
                    for ((vi, v) in views.withIndex()) {
                        val u = x * cosA[vi] - z * sinA[vi]
                        val px = (cx + u * half).roundToInt(); val py = (cy - y * half).roundToInt()
                        if (px < 0 || py < 0 || px >= v.width || py >= v.height || !v.mask[py * v.width + px]) { occ[o] = false; break }
                    }
                }
            }
            onProgress(iy + 1, total, "Tallando voxels")
        }
        val filled = occ.count { it }
        if (filled < 8) throw HullException("Las siluetas no son consistentes entre sí (el volumen resultante es vacío). Verificá que las fotos sean de una mesa giratoria con ángulos uniformes.")
        onProgress(0, 1, "Generando superficie")
        val mesh = surfaceNets(occ, n, config.smoothingIterations)
        // color por vértice
        var colors: FloatArray? = null
        if (views.any { it.rgb != null }) {
            val pos = mesh.first; val nrm = ObjReader.computeFlatSafeNormals(pos, mesh.second)
            colors = FloatArray(pos.size / 3 * 4)
            for (vtx in 0 until pos.size / 3) {
                var best = -2f; var bi = 0
                for ((vi, v) in views.withIndex()) {
                    if (v.rgb == null) continue
                    // dirección hacia la cámara en el marco del objeto
                    val cdx = sinA[vi]; val cdz = cosA[vi]
                    val d = nrm[vtx * 3] * cdx + nrm[vtx * 3 + 2] * cdz
                    if (d > best) { best = d; bi = vi }
                }
                val v = views[bi]
                val u = pos[vtx * 3] * cosA[bi] - pos[vtx * 3 + 2] * sinA[bi]
                val px = (cx + u * half).roundToInt().coerceIn(0, v.width - 1); val py = (cy - pos[vtx * 3 + 1] * half).roundToInt().coerceIn(0, v.height - 1)
                val o = (py * v.width + px) * 3
                colors[vtx * 4] = srgbToLinear((v.rgb!![o].toInt() and 0xFF) / 255f); colors[vtx * 4 + 1] = srgbToLinear((v.rgb[o + 1].toInt() and 0xFF) / 255f)
                colors[vtx * 4 + 2] = srgbToLinear((v.rgb[o + 2].toInt() and 0xFF) / 255f); colors[vtx * 4 + 3] = 1f
            }
        }
        val prim = Primitive3D(mesh.first, mesh.second, ObjReader.computeFlatSafeNormals(mesh.first, mesh.second), colors = colors, materialIndex = 0)
        val scene = Scene3D(
            "reconstruccion_visual", ModelFormat.UNKNOWN, listOf(Node3D("Reconstrucción visual", meshIndex = 0)), listOf(0), listOf(Mesh3D("hull", listOf(prim))),
            listOf(Material3D("Color de vértices", metallicFactor = 0f, roughnessFactor = 0.85f, doubleSided = true)), emptyList(), emptyList(),
            importNotices = listOf(
                Notice(Notice.Severity.WARNING, "hull", "RECONSTRUCCIÓN APROXIMADA por siluetas (${views.size} vistas, ${n}³ voxels): NO es el modelo original; no recupera concavidades ni detalles finos."),
                Notice(Notice.Severity.INFO, "hull-scale", "Escala normalizada a ±1; sin textura UV (color por vértice proyectado desde las fotos)."),
            ),
        )
        return HullResult(scene, filled, filled.toFloat() / occ.size, scene.importNotices.map { it.message })
    }

    private fun srgbToLinear(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** Surface nets sobre una rejilla binaria; devuelve (posiciones, índices). */
    fun surfaceNets(occ: BooleanArray, n: Int, smooth: Int): Pair<FloatArray, IntArray> {
        fun inside(x: Int, y: Int, z: Int) = x in 0 until n && y in 0 until n && z in 0 until n && occ[(y * n + z) * n + x]
        val cellVertex = IntArray((n + 1) * (n + 1) * (n + 1)) { -1 }
        fun cid(x: Int, y: Int, z: Int) = (y * (n + 1) + z) * (n + 1) + x
        val pos = ArrayList<Float>()
        val s = 2f / n
        // celda (x,y,z) cubre los voxels (x-1..x, y-1..y, z-1..z); vértice en la media de las aristas con cambio
        for (y in 0..n) for (z in 0..n) for (x in 0..n) {
            var cnt = 0
            for (dy in 0..1) for (dz in 0..1) for (dx in 0..1) if (inside(x - 1 + dx, y - 1 + dy, z - 1 + dz)) cnt++
            if (cnt == 0 || cnt == 8) continue
            var sx = 0f; var sy = 0f; var sz = 0f; var m = 0
            // 12 aristas del cubo
            val edges = arrayOf(intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(4, 5), intArrayOf(6, 7), intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(4, 6), intArrayOf(5, 7), intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7))
            fun corner(i: Int) = intArrayOf(i and 1, (i shr 2) and 1, (i shr 1) and 1) // dx, dy, dz
            for (e in edges) {
                val a = corner(e[0]); val b = corner(e[1])
                val ia = inside(x - 1 + a[0], y - 1 + a[1], z - 1 + a[2]); val ib = inside(x - 1 + b[0], y - 1 + b[1], z - 1 + b[2])
                if (ia != ib) { sx += (a[0] + b[0]) / 2f; sy += (a[1] + b[1]) / 2f; sz += (a[2] + b[2]) / 2f; m++ }
            }
            val cx = sx / m; val cy = sy / m; val cz = sz / m
            // coordenadas de mundo del centro del voxel (x-1+cx...) ; el voxel i tiene centro -1+(i+0.5)s
            pos.add(-1f + (x - 1 + cx + 0.5f) * s); pos.add(-1f + (y - 1 + cy + 0.5f) * s); pos.add(-1f + (z - 1 + cz + 0.5f) * s)
            cellVertex[cid(x, y, z)] = pos.size / 3 - 1
        }
        val idx = ArrayList<Int>()
        fun quad(a: Int, b: Int, c: Int, d: Int, flip: Boolean) {
            if (a < 0 || b < 0 || c < 0 || d < 0) return
            if (!flip) { idx.add(a); idx.add(b); idx.add(c); idx.add(a); idx.add(c); idx.add(d) } else { idx.add(a); idx.add(c); idx.add(b); idx.add(a); idx.add(d); idx.add(c) }
        }
        // para cada arista de voxel con cambio de ocupación, un quad entre las 4 celdas adyacentes
        for (y in 0 until n) for (z in 0 until n) for (x in 0 until n) {
            val cur = inside(x, y, z)
            // eje X: arista entre (x,y,z) y (x+1,y,z)
            if (cur != inside(x + 1, y, z) && y > 0 && z > 0) {
                val a = cellVertex[cid(x + 1, y, z)]; val b = cellVertex[cid(x + 1, y + 1, z)]; val c = cellVertex[cid(x + 1, y + 1, z + 1)]; val d = cellVertex[cid(x + 1, y, z + 1)]
                quad(a, b, c, d, !cur)
            } else if (cur != inside(x + 1, y, z)) {
                val a = cellVertex[cid(x + 1, y, z)]; val b = cellVertex[cid(x + 1, y + 1, z)]; val c = cellVertex[cid(x + 1, y + 1, z + 1)]; val d = cellVertex[cid(x + 1, y, z + 1)]
                quad(a, b, c, d, !cur)
            }
            if (cur != inside(x, y + 1, z)) {
                val a = cellVertex[cid(x, y + 1, z)]; val b = cellVertex[cid(x, y + 1, z + 1)]; val c = cellVertex[cid(x + 1, y + 1, z + 1)]; val d = cellVertex[cid(x + 1, y + 1, z)]
                quad(a, b, c, d, !cur)
            }
            if (cur != inside(x, y, z + 1)) {
                val a = cellVertex[cid(x, y, z + 1)]; val b = cellVertex[cid(x + 1, y, z + 1)]; val c = cellVertex[cid(x + 1, y + 1, z + 1)]; val d = cellVertex[cid(x, y + 1, z + 1)]
                quad(a, b, c, d, !cur)
            }
        }
        var p = pos.toFloatArray()
        val ix = idx.toIntArray()
        if (smooth > 0) p = laplacian(p, ix, smooth)
        return p to ix
    }

    private fun laplacian(p: FloatArray, idx: IntArray, iterations: Int): FloatArray {
        val nv = p.size / 3
        var cur = p
        repeat(iterations) {
            val sum = FloatArray(p.size); val cnt = IntArray(nv)
            var i = 0
            while (i + 2 < idx.size) {
                for (k in 0..2) { val a = idx[i + k]; val b = idx[i + (k + 1) % 3]
                    sum[a * 3] += cur[b * 3]; sum[a * 3 + 1] += cur[b * 3 + 1]; sum[a * 3 + 2] += cur[b * 3 + 2]; cnt[a]++
                    sum[b * 3] += cur[a * 3]; sum[b * 3 + 1] += cur[a * 3 + 1]; sum[b * 3 + 2] += cur[a * 3 + 2]; cnt[b]++ }
                i += 3
            }
            val nxt = cur.copyOf()
            for (v in 0 until nv) if (cnt[v] > 0) for (c in 0..2) nxt[v * 3 + c] = cur[v * 3 + c] * 0.5f + (sum[v * 3 + c] / cnt[v]) * 0.5f
            cur = nxt
        }
        return cur
    }
}
