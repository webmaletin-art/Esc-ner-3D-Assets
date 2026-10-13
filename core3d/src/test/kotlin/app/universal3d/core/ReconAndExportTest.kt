package app.universal3d.core

import app.universal3d.core.export.ModelExporter
import app.universal3d.core.export.ExportVerifier
import app.universal3d.core.inspect.*
import app.universal3d.core.io.*
import app.universal3d.core.model.*
import app.universal3d.core.recon.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ReconAndExportTest {
    /** Silueta ortográfica de un prisma (hx × hz de semiancho) girado por el ángulo dado; altura y en [-hy, hy]. Misma convención que el reconstructor. */
    private fun boxView(angle: Float, hx: Float, hy: Float, hz: Float, size: Int = 96, half: Float = 1.0f): SilhouetteView {
        val mask = BooleanArray(size * size); val rgb = ByteArray(size * size * 3)
        val c = cos(angle); val s = sin(angle)
        // una vista debe contener el prisma; se samplea el rayo en profundidad
        for (py in 0 until size) for (px in 0 until size) {
            val u = (px - size / 2f) / (size / 2f) * half; val y = -(py - size / 2f) / (size / 2f) * half
            if (abs(y) > hy) continue
            var hit = false
            var w = -1.5f
            while (w <= 1.5f && !hit) { // w a lo largo de la dirección de cámara
                // x = u*cos + w*sin ; z = -u*sin + w*cos   (inversa de u = x cos - z sin, w = x sin + z cos)
                val x = u * c + w * s; val z = -u * s + w * c
                if (abs(x) <= hx && abs(z) <= hz) hit = true
                w += 0.01f
            }
            if (hit) { mask[py * size + px] = true; rgb[(py * size + px) * 3] = 200.toByte(); rgb[(py * size + px) * 3 + 1] = 50; rgb[(py * size + px) * 3 + 2] = 50 }
        }
        return SilhouetteView(size, size, mask, rgb, angle)
    }

    @Test fun visualHullRecoversBoxProportionsAndReportsProgress() = runBlocking {
        val views = (0 until 12).map { boxView((it * PI / 6).toFloat(), 0.6f, 0.4f, 0.3f) }
        val progress = ArrayList<Int>()
        val res = VisualHullReconstructor.reconstruct(views, HullConfig(resolution = 48)) { d, t, phase -> if (phase == "Tallando voxels") progress.add(d) }
        val b = res.scene.bounds()
        // dimensiones relativas: x ≈ 1.2, y ≈ 0.8, z ≈ 0.6 (en unidades arbitrarias; se comparan proporciones)
        val k = b.sizeX / 1.2f
        assertEquals(0.8f * k, b.sizeY, 0.12f * k + 0.05f)
        assertEquals(0.6f * k, b.sizeZ, 0.12f * k + 0.05f)
        assertTrue(progress.zipWithNext().all { (a, c) -> c > a }); assertEquals(48, progress.last())
        val p = res.scene.meshes[0].primitives[0]
        assertNull(p.validate()); assertNotNull(p.colors)
        assertTrue(res.scene.importNotices.any { it.message.contains("APROXIMADA") })
        // malla cerrada: cada arista compartida por 2 triángulos
        val edges = HashMap<Long, Int>()
        for (i in 0 until p.indices.size step 3) for (e in 0..2) { val a = p.indices[i + e]; val c = p.indices[i + (e + 1) % 3]; val key = minOf(a, c).toLong() shl 32 or maxOf(a, c).toLong(); edges.merge(key, 1, Int::plus) }
        assertTrue("aristas no manifold: ${edges.values.groupBy { it }.mapValues { it.value.size }}", edges.values.all { it == 2 })
        // orientación hacia fuera: volumen con signo positivo
        var vol = 0.0
        for (i in 0 until p.indices.size step 3) {
            val a = p.indices[i] * 3; val bb = p.indices[i + 1] * 3; val c = p.indices[i + 2] * 3
            val pos = p.positions
            vol += (pos[a] * (pos[bb + 1] * pos[c + 2] - pos[bb + 2] * pos[c + 1]) - pos[a + 1] * (pos[bb] * pos[c + 2] - pos[bb + 2] * pos[c]) + pos[a + 2] * (pos[bb] * pos[c + 1] - pos[bb + 1] * pos[c])) / 6.0
        }
        assertTrue("volumen con signo $vol", vol > 0)
    }

    @Test fun visualHullRejectsTooFewViewsAndEmptySilhouettes() = runBlocking {
        val few = (0 until 3).map { boxView(it * 1f, 0.5f, 0.5f, 0.5f, 32) }
        assertTrue(runCatching { VisualHullReconstructor.reconstruct(few) }.exceptionOrNull() is HullException)
        val empty = (0 until 8).map { SilhouetteView(8, 8, BooleanArray(64), null, it * 0.5f) }
        assertTrue(runCatching { VisualHullReconstructor.reconstruct(empty) }.exceptionOrNull() is HullException)
    }

    @Test fun visualHullCanBeCancelled() = runBlocking {
        val views = (0 until 8).map { boxView((it * PI / 4).toFloat(), 0.6f, 0.4f, 0.3f, 64) }
        val job = launch(Dispatchers.Default) { VisualHullReconstructor.reconstruct(views, HullConfig(resolution = 160)) }
        delay(50); job.cancelAndJoin(); assertTrue(job.isCancelled)
    }

    @Test fun silhouetteExtractorUsesBackgroundColorAndAlpha() {
        val w = 8; val h = 8
        val px = ByteArray(w * h * 4) { 255.toByte() } // fondo blanco opaco
        for (y in 2..5) for (x in 2..5) { val o = (y * w + x) * 4; px[o] = 10; px[o + 1] = 10; px[o + 2] = 10 }
        val v = SilhouetteExtractor.fromRgba(w, h, px, 0f)
        assertTrue(v.mask[3 * w + 3]); assertFalse(v.mask[0]); assertArrayEquals(intArrayOf(2, 2, 5, 5), v.bbox)
        val a = ByteArray(w * h * 4); for (y in 1..3) for (x in 1..3) a[(y * w + x) * 4 + 3] = 255.toByte()
        val v2 = SilhouetteExtractor.fromRgba(w, h, a, 0f)
        assertTrue(v2.mask[2 * w + 2]); assertFalse(v2.mask[7 * w + 7])
    }

    // ---------------- Inspector y exportación ----------------
    @Test fun inspectorReportsOnlyKnownValues() {
        val r = ModelInspector.inspect(TestAssets.texturedCube())
        assertEquals(12L, r.triangleCount); assertEquals(24L, r.vertexCount); assertEquals(1, r.materialCount); assertEquals(2, r.textureCount)
        assertTrue(r.hasUv); assertFalse(r.hasSkeleton); assertEquals(0, r.boneCount); assertTrue(r.animations.isEmpty())
        assertEquals("8×8", r.textures[0].resolution); assertTrue(r.textures[0].usedAs.contains("baseColor"))
        assertNull(r.sizeBytes.value); assertEquals("No disponible", r.sizeBytes.text({ "$it B" }))
        val sk = ModelInspector.inspect(TestAssets.skinnedAnimated())
        assertTrue(sk.hasSkeleton); assertEquals(2, sk.boneCount); assertEquals(1, sk.animations.size); assertEquals(2f, sk.animations[0].durationSeconds)
    }

    @Test fun exportPlannerReflectsRealLosses() {
        val skinned = ExportPlanner.plan(TestAssets.skinnedAnimated())
        assertTrue(skinned.all { it.enabled })
        val glb = skinned.first { it.format == ExportFormat.GLB }
        assertTrue(glb.expectedLosses.none { it.severity == Notice.Severity.LOSS })
        assertTrue(skinned.first { it.format == ExportFormat.OBJ }.expectedLosses.any { it.code == "anim" })
        assertTrue(skinned.first { it.format == ExportFormat.FBX }.expectedLosses.any { it.code == "skin" })
        val empty = Scene3D("e", ModelFormat.GLB, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val plan = ExportPlanner.plan(empty)
        assertTrue(plan.none { it.enabled }); assertNotNull(plan[0].reason)
    }

    @Test fun exportsAreVerifiedByReopening() {
        val scene = TestAssets.texturedCube()
        for (f in ExportFormat.values()) {
            val a = ModelExporter.export(scene, f, "Mi cubo")
            assertTrue("${f.name}: ${a.verification.details}", a.verification.ok)
            assertTrue(a.bytes.isNotEmpty())
            assertTrue(a.fileName, a.fileName.startsWith("Mi_cubo"))
        }
        val full = ModelExporter.export(scene, ExportFormat.FULL_ZIP)
        val names = ModelImporter.unzip(full.bytes).files.map { it.path }
        assertTrue(names.any { it.startsWith("glb/") && it.endsWith(".glb") }); assertTrue(names.any { it.startsWith("fbx/") })
        assertTrue(names.any { it.startsWith("obj/") && it.endsWith(".mtl") }); assertTrue(names.any { it.startsWith("obj/textures/") }); assertTrue(names.contains("INFORME.txt"))
    }

    @Test fun glbExportOfAnimatedModelProvesAnimationSurvives() {
        val a = ModelExporter.export(TestAssets.skinnedAnimated(), ExportFormat.GLB)
        assertTrue(a.verification.details.joinToString(), a.verification.ok)
        assertTrue(a.verification.details.any { it.contains("animación") })
        val back = ModelImporter.importBytes("x.glb", a.bytes)
        assertEquals(1, back.animations.size); assertEquals(1, back.skins.size)
    }

    @Test fun verifierDetectsCorruptedExport() {
        val scene = TestAssets.texturedCube()
        val bytes = GlbWriter.write(scene).bytes
        assertFalse(ExportVerifier.verify(scene, ExportFormat.GLB, bytes.copyOf(bytes.size - 200)).ok)
        assertFalse(ExportVerifier.verify(scene, ExportFormat.GLB, ByteArray(0)).ok)
    }
}
