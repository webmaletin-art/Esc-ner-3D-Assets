package app.universal3d.core

import app.universal3d.core.adapters.*
import app.universal3d.core.detect.*
import app.universal3d.core.io.*
import app.universal3d.core.math.*
import app.universal3d.core.model.*
import app.universal3d.core.replicate.*
import app.universal3d.core.resources.*
import app.universal3d.core.security.*
import app.universal3d.core.tasks.*
import app.universal3d.core.view.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RuntimeTest {
    // ---------------- TaskManager ----------------
    @Test fun taskLifecycleSuccessFailureCancelRetry() = runBlocking {
        val tm = TaskManager(CoroutineScope(Dispatchers.Default + SupervisorJob()), maxConcurrent = 2)
        val ok = tm.submit("ok", ModelFormat.GLB, 100) { r -> r.progress(50, 100); TaskOutcome("/x/ok.glb") }
        val bad = tm.submit("bad") { throw IllegalStateException("boom") }
        withTimeout(5000) { while (tm.tasks.value.any { !it.isFinished }) delay(10) }
        val okI = tm.tasks.value.first { it.id == ok }; val badI = tm.tasks.value.first { it.id == bad }
        assertEquals(TaskState.SUCCEEDED, okI.state); assertEquals("/x/ok.glb", okI.location); assertEquals(1f, okI.progress)
        assertEquals(TaskState.FAILED, badI.state); assertEquals("boom", badI.error)
        // reintento
        var attempts = 0
        val flaky = tm.submit("flaky") { attempts++; if (attempts < 2) error("1er intento falla"); TaskOutcome("ok") }
        withTimeout(5000) { while (tm.tasks.value.first { it.id == flaky }.state != TaskState.FAILED) delay(10) }
        assertTrue(tm.retry(flaky))
        withTimeout(5000) { while (tm.tasks.value.first { it.id == flaky }.state != TaskState.SUCCEEDED) delay(10) }
        assertEquals(2, attempts)
        assertFalse(tm.retry(ok)) // las exitosas no se reintentan
        // cancelación
        val gate = CompletableDeferred<Unit>()
        val long = tm.submit("larga") { r -> r.progress(1, 10); gate.await(); TaskOutcome() }
        withTimeout(5000) { while (tm.tasks.value.first { it.id == long }.state != TaskState.RUNNING) delay(10) }
        tm.cancel(long)
        withTimeout(5000) { while (tm.tasks.value.first { it.id == long }.state != TaskState.CANCELLED) delay(10) }
        assertEquals(TaskState.CANCELLED, tm.tasks.value.first { it.id == long }.state)
        assertTrue(tm.retry(long))
        tm.cancel(long)
    }

    @Test fun taskConcurrencyIsLimited() = runBlocking {
        val tm = TaskManager(CoroutineScope(Dispatchers.Default + SupervisorJob()), maxConcurrent = 2)
        val running = java.util.concurrent.atomic.AtomicInteger(0); val maxSeen = java.util.concurrent.atomic.AtomicInteger(0)
        repeat(6) { tm.submit("t$it") { val n = running.incrementAndGet(); maxSeen.accumulateAndGet(n) { a, b -> maxOf(a, b) }; delay(40); running.decrementAndGet(); TaskOutcome() } }
        withTimeout(10000) { while (tm.tasks.value.any { !it.isFinished }) delay(10) }
        assertTrue("máx simultáneas = ${maxSeen.get()}", maxSeen.get() <= 2)
        assertTrue(tm.tasks.value.all { it.state == TaskState.SUCCEEDED })
    }

    @Test fun unknownTotalGivesNullProgress() {
        val t = TaskInfo(1, "x", null, null, 500, TaskState.RUNNING)
        assertNull(t.progress)
        assertEquals(0.5f, TaskInfo(1, "x", null, 1000, 500, TaskState.RUNNING).progress)
    }

    // ---------------- SafeFetcher ----------------
    private fun server(block: (RecordedRequest) -> MockResponse): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = block(request) }
        start()
    }

    @Test fun fetcherBlocksPrivateHostsByDefault() = runBlocking {
        val s = server { MockResponse().setBody("hi") }
        try {
            val strict = SafeFetcher(allowHttp = true)
            val e = runCatching { strict.download(s.url("/a.glb").toString(), 1000) }.exceptionOrNull()
            assertTrue(e is FetchException); assertEquals(FetchException.Kind.POLICY, (e as FetchException).kind)
        } finally { s.shutdown() }
    }

    @Test fun fetcherDownloadsWithProgressLimitsAndRedirectValidation() = runBlocking {
        val data = ByteArray(100_000) { it.toByte() }
        val s = server { r ->
            when (r.path) {
                "/a.bin" -> MockResponse().setBody(Buffer().write(data))
                "/redir" -> MockResponse().setResponseCode(302).setHeader("Location", "file:///etc/passwd")
                "/loop" -> MockResponse().setResponseCode(302).setHeader("Location", "/loop")
                "/auth" -> MockResponse().setResponseCode(403)
                "/missing" -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(500)
            }
        }
        try {
            val f = SafeFetcher(allowHttp = true, blockPrivate = false)
            val progress = ArrayList<Pair<Long, Long?>>()
            val got = f.download(s.url("/a.bin").toString(), 1_000_000) { d, t -> progress.add(d to t) }
            assertArrayEquals(data, got)
            assertEquals(100_000L, progress.last().first); assertEquals(100_000L, progress.last().second)
            assertTrue(progress.map { it.first }.zipWithNext().all { (a, b) -> b >= a })
            assertEquals(FetchException.Kind.TOO_LARGE, (runCatching { f.download(s.url("/a.bin").toString(), 1000) }.exceptionOrNull() as FetchException).kind)
            assertEquals(FetchException.Kind.POLICY, (runCatching { f.download(s.url("/redir").toString(), 1000) }.exceptionOrNull() as FetchException).kind)
            assertEquals(FetchException.Kind.REDIRECT, (runCatching { f.download(s.url("/loop").toString(), 1000) }.exceptionOrNull() as FetchException).kind)
            assertEquals(FetchException.Kind.AUTH, (runCatching { f.download(s.url("/auth").toString(), 1000) }.exceptionOrNull() as FetchException).kind)
            assertEquals(FetchException.Kind.NOT_FOUND, (runCatching { f.download(s.url("/missing").toString(), 1000) }.exceptionOrNull() as FetchException).kind)
            val p = f.probe(s.url("/a.bin").toString(), 16)
            assertEquals(16, p.head.size)
        } finally { s.shutdown() }
    }

    @Test fun downloadCanBeCancelled() = runBlocking {
        val s = server { MockResponse().setBody(Buffer().write(ByteArray(2_000_000))).throttleBody(50_000, 100, java.util.concurrent.TimeUnit.MILLISECONDS) }
        try {
            val f = SafeFetcher(allowHttp = true, blockPrivate = false)
            val job = launch(Dispatchers.Default) { f.download(s.url("/x").toString(), 10_000_000) }
            delay(300); val t0 = System.currentTimeMillis()
            job.cancelAndJoin()
            assertTrue("cancelación lenta: ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 3000)
            assertTrue(job.isCancelled)
        } finally { s.shutdown() }
    }

    // ---------------- DetectionEngine ----------------
    @Test fun engineVerifiesCandidateAndConfirmsModel() = runBlocking {
        val glb = GlbWriter.write(TestAssets.texturedCube()).bytes
        val s = server { r -> if (r.path == "/m/cube.glb") MockResponse().setBody(Buffer().write(glb)) else if (r.path == "/m/fake.glb") MockResponse().setBody("<html>no</html>") else MockResponse().setResponseCode(404) }
        try {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val eng = DetectionEngine(scope, AdapterRegistry.default(), SafeFetcher(allowHttp = true, blockPrivate = false), minRecomputeIntervalMs = 0)
            val page = s.url("/page").toString()
            eng.onPageStarted(page)
            eng.onNetworkUrl(s.url("/m/fake.glb").toString())
            eng.onNetworkUrl(s.url("/m/cube.glb").toString())
            withTimeout(8000) { while (eng.report.value.state != DetectionState.MODEL_CONFIRMED || eng.report.value.resources.any { it.access == AccessState.UNVERIFIED } || eng.report.value.resources.size < 2) delay(20) }
            val rep = eng.report.value
            val good = rep.resources.first { it.url.endsWith("cube.glb") }
            assertTrue(good.contentVerified); assertEquals(glb.size.toLong(), good.sizeBytes)
            val fake = rep.resources.first { it.url.endsWith("fake.glb") }
            assertEquals(AccessState.CONTENT_MISMATCH, fake.access)
            assertEquals(good.url, rep.primaryModel!!.url)
            // navegación nueva limpia el estado
            eng.onPageStarted("https://other.example/")
            assertEquals(DetectionState.NONE, eng.report.value.state)
            scope.cancel()
        } finally { s.shutdown() }
    }

    // ---------------- ReplicationEngine ----------------
    private fun reportFor(page: String, vararg urls: String, state: DetectionState = DetectionState.FILE_DETECTED, gl: Boolean = false): DetectionReport =
        DetectionReport(page, state, urls.mapNotNull { ResourceClassifier.classify(it) }, emptyList(), gl, false, emptyList(), null)

    @Test fun replicationDownloadsGltfWithDedupedDependencies() = runBlocking {
        val glb = GlbWriter.write(TestAssets.texturedCube()).bytes
        val bb = java.nio.ByteBuffer.wrap(glb).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val jl = bb.getInt(12); val json = String(glb, 20, jl).trim(); val binLen = bb.getInt(20 + jl)
        val bin = glb.copyOfRange(20 + jl + 8, 20 + jl + 8 + binLen)
        val gltf = json.replace("\"buffers\":[{", "\"buffers\":[{\"uri\":\"d.bin\",")
        val s = server { r -> when (r.path) { "/x/m.gltf" -> MockResponse().setBody(gltf); "/x/d.bin" -> MockResponse().setBody(Buffer().write(bin)); else -> MockResponse().setResponseCode(404) } }
        try {
            val page = s.url("/p").toString()
            val rep = reportFor(page, s.url("/x/m.gltf").toString())
            val events = ReplicationEngine().run(ReplicationInput(rep, SafeFetcher(allowHttp = true, blockPrivate = false), AdapterRegistry.default())).toList()
            val fin = events.last() as ReplicationEvent.Finished
            assertTrue(fin.result.failureReason, fin.result.success)
            assertEquals(FidelityLevel.EXACT_ORIGINAL, fin.result.fidelity!!.level)
            assertEquals(12, fin.result.scene!!.meshes[0].primitives[0].triangleCount)
            assertTrue(events.any { it is ReplicationEvent.Progress && it.total != null })
            assertTrue(events.first() is ReplicationEvent.Phase)
        } finally { s.shutdown() }
    }

    @Test fun replicationFallsBackToWebGlCapture() = runBlocking {
        val cap = CaptureFixtures.cubeCaptureJson()
        val rep = reportFor("https://example.com/p", state = DetectionState.VISIBLE_NO_GEOMETRY, gl = true)
        val events = ReplicationEngine().run(ReplicationInput(rep, SafeFetcher(), AdapterRegistry.default(), capture = CaptureProvider { cap })).toList()
        val fin = (events.last() as ReplicationEvent.Finished).result
        assertTrue(fin.failureReason, fin.success)
        assertEquals(FidelityLevel.PARTIAL_CAPTURE, fin.fidelity!!.level)
        assertTrue(fin.fidelity!!.missing.any { it.contains("Esqueleto") })
        assertTrue(events.filterIsInstance<ReplicationEvent.Phase>().map { it.strategy }.containsAll(listOf("original", "official", "capture")))
    }

    @Test fun replicationReportsWhatIsMissingWhenNothingWorks() = runBlocking {
        val rep = reportFor("https://example.com/p", state = DetectionState.VIEWER_PROBABLE)
        val events = ReplicationEngine().run(ReplicationInput(rep, SafeFetcher(), AdapterRegistry.default())).toList()
        val fin = (events.last() as ReplicationEvent.Finished).result
        assertFalse(fin.success); assertNull(fin.scene)
        assertTrue(fin.failureReason!!.contains("No se pudo obtener geometría"))
        assertTrue(fin.offerVisualReconstruction)
        assertTrue(events.any { it is ReplicationEvent.Recoverable })
    }

    @Test fun replicationUsesOfficialExportWhenOriginalMissing() = runBlocking {
        val glb = GlbWriter.write(TestAssets.texturedCube()).bytes
        val s = server { r -> if (r.path == "/dl/m.glb") MockResponse().setBody(Buffer().write(glb)) else MockResponse().setResponseCode(404) }
        try {
            val fake = object : PlatformAdapter by GenericAdapter {
                override val id = "fake"; override val displayName = "Falso"
                override val operations = setOf(AdapterOperation.OFFICIAL_EXPORT)
                override fun matches(pageUrl: String) = true
                override suspend fun officialExport(ctx: AdapterContext, pageUrl: String) =
                    OfficialExportResult(ResourceClassifier.classify(s.url("/dl/m.glb").toString())!!.copy(access = AccessState.ACCESSIBLE), "CC0", "Modelo")
            }
            val rep = reportFor("https://example.com/p", state = DetectionState.VIEWER_PROBABLE)
            val events = ReplicationEngine().run(ReplicationInput(rep, SafeFetcher(allowHttp = true, blockPrivate = false), AdapterRegistry(listOf(fake)))).toList()
            val fin = (events.last() as ReplicationEvent.Finished).result
            assertTrue(fin.failureReason, fin.success); assertEquals(FidelityLevel.OFFICIAL_EXPORT, fin.fidelity!!.level)
            assertTrue(fin.sourceDescription!!.contains("CC0"))
        } finally { s.shutdown() }
    }

    @Test fun replicationCanBeCancelled() = runBlocking {
        val s = server { MockResponse().setBody(Buffer().write(ByteArray(3_000_000))).throttleBody(30_000, 100, java.util.concurrent.TimeUnit.MILLISECONDS) }
        try {
            val rep = reportFor("https://example.com/p", s.url("/big.glb").toString())
            val job = launch(Dispatchers.Default) { ReplicationEngine().run(ReplicationInput(rep, SafeFetcher(allowHttp = true, blockPrivate = false), AdapterRegistry.default())).collect { } }
            delay(300); job.cancelAndJoin(); assertTrue(job.isCancelled)
        } finally { s.shutdown() }
    }

    // ---------------- Captura WebGL ----------------
    @Test fun webGlCaptureReconstructsInterleavedIndexedCube() {
        val rec = WebGlCaptureReconstructor.reconstruct(CaptureFixtures.cubeCaptureJson())
        assertEquals(1, rec.drawsUsed)
        val p = rec.scene.meshes[0].primitives[0]
        assertEquals(12, p.triangleCount); assertEquals(24, p.vertexCount)
        assertNotNull(p.uv0); assertNotNull(p.normals)
        val b = p.bounds(); assertEquals(1f, b.sizeX, 1e-5f)
        assertEquals(1, rec.scene.images.size) // textura legible incluida
        assertNotNull(rec.scene.materials[p.materialIndex].baseColorTexture)
        assertTrue(rec.scene.importNotices.any { it.code == "capture" })
    }

    @Test fun webGlCaptureSkipsScreenQuadsAndRejectsEmpty() {
        val e = assertThrows(ModelImportException::class.java) { WebGlCaptureReconstructor.reconstruct("""{"v":1,"buffers":{},"draws":[]}""") }
        assertEquals(ModelImportException.Code.EMPTY, e.code)
        assertThrows(ModelImportException::class.java) { WebGlCaptureReconstructor.reconstruct("nope") }
    }

    // ---------------- Animación, cámara, picking ----------------
    @Test fun animationInterpolatesAndSkinsFollowBone() {
        val scene = TestAssets.skinnedAnimated()
        val anim = scene.animations[0]
        val l0 = AnimationEvaluator.localMatrices(scene, anim, 0f)
        assertEquals(1f, l0[2].m[13], 1e-5f) // reposo: hueso1 en y=1
        val mid = AnimationEvaluator.localMatrices(scene, anim, 1f)
        assertEquals(1.25f, mid[2].m[13], 1e-5f) // traslación lineal y: 1 → 1.5 en 2 s; t=1 s → 1.25
    }

    @Test fun jointMatricesAreIdentityInBindPose() {
        val scene = TestAssets.skinnedAnimated()
        val world = SceneEval.worldMatrices(scene, null)
        val jm = AnimationEvaluator.jointMatrices(scene, scene.skins[0], world, 0)
        val ident = Mat4.identityArray()
        for (j in 0 until 2) for (k in 0 until 16) assertEquals("joint $j [$k]", ident[k], jm[j * 16 + k], 1e-5f)
    }

    @Test fun cameraFramesModelAndZoomsWithinLimits() {
        val cam = OrbitCamera()
        val b = Aabb(-1f, 0f, -1f, 1f, 2f, 1f)
        cam.frame(b)
        assertEquals(1f, cam.target[1], 1e-5f)
        val d0 = cam.distance; assertTrue(d0 > b.radius)
        cam.zoom(2f); assertTrue(cam.distance < d0)
        repeat(50) { cam.zoom(5f) }; assertTrue(cam.distance >= b.radius * 0.05f)
        cam.rotate(1000f, 1000f); assertTrue(cam.pitch <= 1.5f)
        cam.reset(); assertEquals(d0, cam.distance, 1e-5f)
        val eye = cam.eye(); assertTrue(eye.all { it.isFinite() })
    }

    @Test fun pickingFindsNearestNode() {
        val cube = TestAssets.texturedCube()
        val world = cube.worldMatrices() // cubo centrado en y=1
        val hit = RayPicker.pick(cube, world, floatArrayOf(0f, 1f, 5f), floatArrayOf(0f, 0f, -1f))
        assertNotNull(hit); assertEquals(0, hit!!.nodeIndex); assertEquals(4.5f, hit.distance, 1e-4f)
        assertNull(RayPicker.pick(cube, world, floatArrayOf(5f, 1f, 5f), floatArrayOf(0f, 0f, -1f)))
        val cam = OrbitCamera().also { it.frame(cube.bounds()) }
        val ray = RayPicker.screenRay(500f, 500f, 1000, 1000, cam.view(), cam.projection(1f))!!
        assertNotNull(RayPicker.pick(cube, world, ray.first, ray.second))
    }

    @Test fun matrixInverseAndDecomposeRoundTrip() {
        val q = Quat.normalize(floatArrayOf(0.1f, 0.5f, -0.3f, 0.8f))
        val m = Mat4.fromTrs(floatArrayOf(1f, 2f, 3f), q, floatArrayOf(2f, 3f, 4f))
        val inv = m.inverse()!!
        val id = m * inv
        for (i in 0 until 16) assertEquals(Mat4.identityArray()[i], id.m[i], 1e-4f)
        val t = m.decompose()
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), t.t, 1e-5f); assertArrayEquals(floatArrayOf(2f, 3f, 4f), t.s, 1e-4f)
        assertEquals(1f, kotlin.math.abs(t.q[0] * q[0] + t.q[1] * q[1] + t.q[2] * q[2] + t.q[3] * q[3]), 1e-4f)
        val e = Quat.toEulerXyzDeg(Quat.fromEulerXyzDeg(30f, 40f, 50f)); assertArrayEquals(floatArrayOf(30f, 40f, 50f), e, 1e-3f)
    }
}

object CaptureFixtures {
    /** Cubo con vértices intercalados [pos3 nrm3 uv2] (32 bytes) y 12 triángulos de 16 bits + una textura legible y una opaca. */
    fun cubeCaptureJson(): String {
        val cube = TestAssets.cubePrimitive()
        val bb = java.nio.ByteBuffer.allocate(24 * 32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in 0 until 24) {
            for (c in 0..2) bb.putFloat(cube.positions[v * 3 + c]); for (c in 0..2) bb.putFloat(cube.normals!![v * 3 + c]); bb.putFloat(cube.uv0!![v * 2]); bb.putFloat(cube.uv0!![v * 2 + 1])
        }
        val ib = java.nio.ByteBuffer.allocate(36 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in cube.indices) ib.putShort(i.toShort())
        val e = Base64.getEncoder()
        val png = e.encodeToString(TestAssets.checkerPng())
        return """{"v":1,"buffers":{"1":{"len":${24 * 32},"data":"${e.encodeToString(bb.array())}"},"2":{"len":72,"data":"${e.encodeToString(ib.array())}"}},
          "draws":[
           {"mode":4,"count":36,"indexType":5123,"indexOffset":0,"elementBuffer":"2","textures":["t1","t2"],"attribs":[
             {"name":"a_position","loc":0,"size":3,"type":5126,"norm":false,"stride":32,"offset":0,"buffer":"1"},
             {"name":"a_normal","loc":1,"size":3,"type":5126,"norm":false,"stride":32,"offset":12,"buffer":"1"},
             {"name":"a_uv","loc":2,"size":2,"type":5126,"norm":false,"stride":32,"offset":24,"buffer":"1"}]},
           {"mode":4,"count":36,"indexType":5123,"indexOffset":0,"elementBuffer":"2","attribs":[{"name":"a_position","loc":0,"size":3,"type":5126,"norm":false,"stride":32,"offset":0,"buffer":"1"}]},
           {"mode":1,"count":2,"first":0,"attribs":[{"name":"a_position","loc":0,"size":3,"type":5126,"norm":false,"stride":32,"offset":0,"buffer":"1"}]}],
          "textures":{"t1":{"w":8,"h":8,"png":"$png"},"t2":{"w":4,"h":4,"reason":"cross-origin"}}}"""
    }
}
