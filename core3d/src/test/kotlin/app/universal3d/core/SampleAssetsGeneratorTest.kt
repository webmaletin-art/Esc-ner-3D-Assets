package app.universal3d.core

import app.universal3d.core.io.*
import app.universal3d.core.model.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.*

/**
 * Genera los modelos de muestra incluidos en la app (originales del proyecto, CC0). Sólo escribe si GENERATE_SAMPLES=1.
 * Uso: GENERATE_SAMPLES=1 ./gradlew :core3d:test --tests '*SampleAssetsGenerator*'
 */
class SampleAssetsGeneratorTest {
    private fun armScene(): Scene3D {
        val seg = 20; val rings = 12; val len = 2f; val r0 = 0.28f
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val uv = ArrayList<Float>(); val j = ArrayList<Int>(); val w = ArrayList<Float>(); val idx = ArrayList<Int>()
        for (ri in 0..rings) {
            val y = len * ri / rings
            val radius = r0 * (1f - 0.18f * (y / len)) * (1f + 0.12f * exp(-((y - 1f) * (y - 1f)) / 0.02f).toFloat()) // leve abultamiento en la articulación
            for (si in 0..seg) {
                val a = (2 * PI * si / seg).toFloat()
                val nx = cos(a); val nz = sin(a)
                pos.add(nx * radius); pos.add(y); pos.add(nz * radius); nrm.add(nx); nrm.add(0f); nrm.add(nz); uv.add(si.toFloat() / seg); uv.add(ri.toFloat() / rings)
                val t = ((y - 0.7f) / 0.6f).coerceIn(0f, 1f)
                j.addAll(listOf(0, 1, 0, 0)); w.addAll(listOf(1f - t, t, 0f, 0f))
            }
        }
        for (ri in 0 until rings) for (si in 0 until seg) {
            val a = ri * (seg + 1) + si; val b = a + seg + 1
            idx.addAll(listOf(a, a + 1, b, a + 1, b + 1, b))
        }
        val prim = Primitive3D(pos.toFloatArray(), idx.toIntArray(), nrm.toFloatArray(), uv.toFloatArray(), joints = j.toIntArray(), weights = w.toFloatArray(), materialIndex = 0)
        val nodes = listOf(Node3D("Brazo", meshIndex = 0, skinIndex = 0), Node3D("Hombro", children = listOf(2)), Node3D("Codo", translation = floatArrayOf(0f, 1f, 0f)))
        val ibm = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, -1f, 0f, 1f)
        fun q(deg: Float) = floatArrayOf(0f, 0f, sin(deg * PI.toFloat() / 360f), cos(deg * PI.toFloat() / 360f))
        val keys = floatArrayOf(0f, 0.75f, 1.5f, 2.25f, 3f)
        val vals = ArrayList<Float>().also { l -> listOf(0f, 55f, 0f, -55f, 0f).forEach { l.addAll(q(it).toList()) } }.toFloatArray()
        val shoulderVals = ArrayList<Float>().also { l -> listOf(0f, -15f, 0f, 15f, 0f).forEach { l.addAll(q(it).toList()) } }.toFloatArray()
        val anim = Animation3D("Flexionar", listOf(
            AnimChannel(2, AnimPath.ROTATION, AnimSampler(keys, vals, Interpolation.LINEAR)),
            AnimChannel(1, AnimPath.ROTATION, AnimSampler(keys, shoulderVals, Interpolation.LINEAR)),
        ))
        val tex = TestAssets.checkerPng(16)
        val mat = Material3D("Piel", baseColorFactor = floatArrayOf(0.95f, 0.75f, 0.6f, 1f), baseColorTexture = TextureRef(0), metallicFactor = 0f, roughnessFactor = 0.55f)
        return Scene3D("brazo_animado", ModelFormat.GLB, nodes, listOf(0, 1), listOf(Mesh3D("Cilindro", listOf(prim))), listOf(mat), listOf(Texture3D("tablero", 0)), listOf(Image3D("tablero.png", "image/png", tex)),
            skins = listOf(Skin3D("Esqueleto", listOf(1, 2), ibm, 1)), animations = listOf(anim))
    }

    @Test fun generate() {
        assumeTrue(System.getenv("GENERATE_SAMPLES") == "1")
        val out = File("../app/src/main/assets/samples").apply { mkdirs() }
        val cube = TestAssets.texturedCube().retag(name = "cubo_texturado")
        File(out, "cubo_texturado.glb").writeBytes(GlbWriter.write(cube).bytes)
        File(out, "cubo_obj.zip").writeBytes(ObjWriter.write(cube, "cubo").zip)
        File(out, "brazo_animado.glb").writeBytes(GlbWriter.write(armScene()).bytes)
        File(out, "LICENCIA.txt").writeText("Modelos de muestra generados por código del proyecto Universal 3D Asset Inspector. Dedicados al dominio público (CC0 1.0).\n")
    }
}
