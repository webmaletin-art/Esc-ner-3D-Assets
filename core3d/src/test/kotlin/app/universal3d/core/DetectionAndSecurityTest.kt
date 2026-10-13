package app.universal3d.core

import app.universal3d.core.adapters.*
import app.universal3d.core.detect.*
import app.universal3d.core.io.*
import app.universal3d.core.model.ModelFormat
import app.universal3d.core.resources.*
import app.universal3d.core.security.*
import org.junit.Assert.*
import org.junit.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class DetectionAndSecurityTest {
    private val adapters = AdapterRegistry.default().all

    // ---- extensiones, MIME, firmas ----
    @Test fun classifiesExtensionsAndMime() {
        assertEquals(ModelFormat.GLB, ResourceClassifier.classify("https://x.com/a/b.GLB?v=2")!!.format)
        assertEquals(ModelFormat.GLTF, ResourceClassifier.classify("https://x.com/a.gltf")!!.format)
        assertEquals(ModelFormat.OBJ, ResourceClassifier.classify("https://x.com/a.obj")!!.format)
        assertEquals(ModelFormat.FBX, ResourceClassifier.classify("https://x.com/a.fbx#frag")!!.format)
        assertEquals(ResourceKind.TEXTURE, ResourceClassifier.classify("https://x.com/t.webp")!!.kind)
        assertEquals(ResourceKind.BUFFER, ResourceClassifier.classify("https://x.com/m.bin")!!.kind)
        assertEquals(ResourceKind.MATERIAL_LIBRARY, ResourceClassifier.classify("https://x.com/m.mtl")!!.kind)
        assertNull(ResourceClassifier.classify("https://x.com/index.html"))
        val usdz = ResourceClassifier.classify("https://x.com/a.usdz")!!
        assertFalse(usdz.importable); assertEquals("usdz", usdz.unsupportedExtension); assertNotNull(usdz.incompatibilityReason)
        assertEquals(ModelFormat.GLB, ResourceClassifier.classify("https://x.com/download?id=3", "model/gltf-binary")!!.format)
        assertEquals(ModelFormat.GLB, FormatSniffer.byMime("model/gltf-binary; charset=x"))
    }

    @Test fun sniffsContent() {
        assertEquals(ModelFormat.GLB, FormatSniffer.byContent(GlbWriterBytes.glb()))
        assertEquals(ModelFormat.FBX, FormatSniffer.byContent("Kaydara FBX Binary  \u0000\u001a\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(ModelFormat.OBJ, FormatSniffer.byContent("# c\nv 0 0 0\nv 1 0 0\nf 1 2 3\n".toByteArray()))
        assertEquals(ModelFormat.UNKNOWN, FormatSniffer.byContent("<!doctype html><html></html>".toByteArray()))
        assertEquals(ModelFormat.GLTF, FormatSniffer.byContent("{\"asset\":{\"version\":\"2.0\"}}".toByteArray()))
        assertEquals(ModelFormat.ZIP, FormatSniffer.byContent(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)))
    }

    object GlbWriterBytes { fun glb() = GlbWriter.write(TestAssets.texturedCube()).bytes }

    // ---- nombres seguros / zip-slip ----
    @Test fun safeFileNames() {
        assertEquals("Mi_modelo_final", SafeNames.fileStem("Mi modelo final"))
        assertEquals("final", SafeNames.fileStem("Mi modelo/final"))
        assertEquals("passwd", SafeNames.fileStem("../../etc/passwd"))
        assertEquals("modelo", SafeNames.fileStem("CON"))
        assertEquals("modelo", SafeNames.fileStem("..."))
        assertEquals("cafe.glb", SafeNames.fileName("café.GLB", "glb"))
        assertTrue(SafeNames.fileStem("a".repeat(300)).length <= 80)
        assertNull(SafeNames.safeZipPath("../evil.txt")); assertNull(SafeNames.safeZipPath("/abs/x")); assertNull(SafeNames.safeZipPath("C:/x"))
        assertEquals("a/b.png", SafeNames.safeZipPath("a\\b.png"))
    }

    @Test fun zipSlipEntriesAreIgnoredAndBombLimited() {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("../evil.obj")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("ok/model.obj")); z.write("v 0 0 0".toByteArray()); z.closeEntry()
        }
        val b = ModelImporter.unzip(bos.toByteArray())
        assertEquals(listOf("ok/model.obj"), b.files.map { it.path })
        assertThrows(ModelImportException::class.java) { ModelImporter.unzip(byteArrayOf(1, 2, 3)) }
    }

    // ---- política de URL ----
    @Test fun urlPolicy() {
        assertTrue(UrlPolicy.checkNavigation("https://example.com/a").allowed)
        assertFalse(UrlPolicy.checkNavigation("http://example.com").allowed)
        assertTrue(UrlPolicy.checkNavigation("http://example.com", allowHttp = true).allowed)
        for (bad in listOf("file:///sdcard/x", "javascript:alert(1)", "content://x/y", "intent://x#Intent;end", "data:text/html,hi", "https://user:pw@example.com/"))
            assertFalse(bad, UrlPolicy.checkNavigation(bad).allowed)
        assertFalse(UrlPolicy.checkResourceUrl("https://127.0.0.1/x.glb").allowed)
        assertFalse(UrlPolicy.checkResourceUrl("https://192.168.1.5/x.glb").allowed)
        assertFalse(UrlPolicy.checkResourceUrl("https://localhost/x.glb").allowed)
        assertFalse(UrlPolicy.checkResourceUrl("https://[::1]/x.glb").allowed)
        assertFalse(UrlPolicy.checkResourceUrl("https://169.254.169.254/latest").allowed)
        assertTrue(UrlPolicy.checkResourceUrl("https://cdn.example.com/x.glb").allowed)
        assertEquals("https://example.com", UrlPolicy.fromAddressBar("example.com"))
        assertTrue(UrlPolicy.fromAddressBar("gato 3d modelo").startsWith("https://duckduckgo.com/?q=gato"))
    }

    @Test fun logRedaction() {
        val r = LogRedactor.redactUrl("https://a.com/p?token=SECRET123&x=1&sig=abc")
        assertFalse(r.contains("SECRET123")); assertFalse(r.contains("abc")); assertTrue(r.contains("x=1"))
        assertEquals("***", LogRedactor.redactHeader("Authorization", "Token abc"))
        assertEquals("***", LogRedactor.redactHeader("Cookie", "a=b"))
    }

    // ---- dependencias ----
    @Test fun dependencyTableDeduplicates() {
        val t = DependencyTable()
        assertTrue(t.register(ResourceRef("https://x.com/t/albedo.png", ResourceKind.TEXTURE), "matA"))
        assertFalse(t.register(ResourceRef("https://X.com:443/t/albedo.png#x", ResourceKind.TEXTURE), "matB"))
        assertTrue(t.register(ResourceRef("https://x.com/t/other.png", ResourceKind.TEXTURE), "matB"))
        assertEquals(2, t.size)
        assertEquals(setOf("matA", "matB"), t.referencesOf("https://x.com/t/albedo.png"))
    }

    @Test fun resolvesDependencies() {
        val gltf = """{"asset":{"version":"2.0"},"buffers":[{"uri":"d.bin","byteLength":1}],"images":[{"uri":"tex/a%20b.png"},{"uri":"data:image/png;base64,AAAA"}]}"""
        val deps = DependencyResolver.forGltf("https://h.com/m/model.gltf", gltf)
        assertEquals(listOf("https://h.com/m/d.bin", "https://h.com/m/tex/a%20b.png"), deps.map { it.url })
        val mtl = DependencyResolver.forMtl("https://h.com/m/a.mtl", "newmtl a\nmap_Kd -s 1 1 1 tex/albedo.png\nbump nrm.png\n")
        assertEquals(listOf("https://h.com/m/tex/albedo.png", "https://h.com/m/nrm.png"), mtl.map { it.url })
        assertEquals(listOf("https://h.com/m/a.mtl"), DependencyResolver.forObj("https://h.com/m/a.obj", "mtllib a.mtl\nv 0 0 0").map { it.url })
    }

    // ---- clasificación de estados ----
    private fun analyze(sig: PageSignals?, net: List<String> = emptyList(), page: String = "https://example.com/p", probed: Map<String, DetectedResource> = emptyMap()) =
        DetectionClassifier.analyze(page, sig, net, adapters, probed)

    @Test fun emptyPageHasNoModels() {
        val r = analyze(PageSignals("https://example.com/p"))
        assertEquals(DetectionState.NONE, r.state); assertFalse(r.canReplicate)
    }

    @Test fun canvasAloneIsNotAModel() {
        val sig = PageSignals("https://example.com/p", canvases = listOf(CanvasSignal(300, 150, null, 0, 0, true)))
        assertEquals(DetectionState.NONE, analyze(sig).state)
        val gl = PageSignals("https://example.com/p", canvases = listOf(CanvasSignal(300, 150, "webgl2", 120, 50000, true)))
        assertEquals("un canvas WebGL activo es solo un visor probable", DetectionState.VIEWER_PROBABLE, analyze(gl).state)
    }

    @Test fun viewerLibraryWithActiveGlButNoFileIsVisibleWithoutGeometry() {
        val sig = PageSignals("https://example.com/p", libs = listOf("three"), canvases = listOf(CanvasSignal(800, 600, "webgl2", 200, 80000, true)))
        val r = analyze(sig)
        assertEquals(DetectionState.VISIBLE_NO_GEOMETRY, r.state); assertTrue(r.canReplicate); assertFalse(r.canExportFormats)
    }

    @Test fun modelViewerSrcIsFileDetectedThenConfirmedAfterProbe() {
        val sig = PageSignals("https://example.com/p", viewers = listOf(ViewerSignal("model-viewer", "/models/astro.glb")))
        val r = analyze(sig)
        assertEquals(DetectionState.FILE_DETECTED, r.state)
        val res = r.resources.single()
        assertEquals("https://example.com/models/astro.glb", res.url)
        assertEquals(Provenance.MODEL_VIEWER, res.origin)
        val confirmed = analyze(sig, probed = mapOf(res.id to res.copy(access = AccessState.ACCESSIBLE, contentVerified = true, sizeBytes = 1234)))
        assertEquals(DetectionState.MODEL_CONFIRMED, confirmed.state)
        assertTrue(confirmed.canExportFormats); assertEquals(1234L, confirmed.primaryModel!!.sizeBytes)
        assertEquals(setOf(ModelFormat.GLB, ModelFormat.OBJ, ModelFormat.FBX, ModelFormat.ZIP), confirmed.primaryModel!!.conversions)
    }

    @Test fun authAndMismatchAndUnsupported() {
        val sig = PageSignals("https://example.com/p", links = listOf("https://example.com/a.glb"))
        val res = analyze(sig).resources.single()
        assertEquals(DetectionState.AUTH_REQUIRED, analyze(sig, probed = mapOf(res.id to res.copy(access = AccessState.REQUIRES_AUTH))).state)
        assertEquals("HTML en vez de GLB no cuenta como modelo", DetectionState.NONE, analyze(sig, probed = mapOf(res.id to res.copy(access = AccessState.CONTENT_MISMATCH))).state)
        val usdz = PageSignals("https://example.com/p", links = listOf("https://example.com/a.usdz"))
        assertEquals(DetectionState.UNSUPPORTED_FORMAT, analyze(usdz).state)
    }

    @Test fun captureMakesGeometryCapturable() {
        val sig = PageSignals("https://example.com/p", canvases = listOf(CanvasSignal(800, 600, "webgl", 50, 9000, true)), capture = CaptureSignal(true, 12, 400_000, false))
        val r = analyze(sig)
        assertEquals(DetectionState.GEOMETRY_CAPTURABLE, r.state); assertTrue(r.captureAvailable)
    }

    @Test fun crossOriginFramesGiveIncomplete() {
        val sig = PageSignals("https://example.com/p", libs = listOf("three"), crossOriginIframeBlocked = true)
        assertEquals(DetectionState.INCOMPLETE, analyze(sig).state)
    }

    @Test fun networkUrlsAreUsedAndDeduplicated() {
        val sig = PageSignals("https://example.com/p", links = listOf("https://cdn.x.com/m.glb"))
        val r = analyze(sig, net = listOf("https://cdn.x.com/m.glb", "https://cdn.x.com/m.bin"))
        assertEquals(1, r.resources.count { it.format == ModelFormat.GLB })
    }

    @Test fun signalsParsingIsRobustAndBounded() {
        assertNull(PageSignals.parse("no json"))
        assertNull(PageSignals.parse("{\"title\":\"x\"}"))
        val huge = "{\"url\":\"https://a.com\",\"links\":[" + (1..1000).joinToString(",") { "\"https://a.com/$it.glb\"" } + "]}"
        assertEquals(200, PageSignals.parse(huge)!!.links.size)
        assertNull(PageSignals.parse("{\"url\":\"x\",\"pad\":\"" + "a".repeat(PageSignals.MAX_JSON) + "\"}"))
        val s = PageSignals.parse("""{"url":"https://a.com","libs":["three","evil-lib"],"canvases":[{"w":10,"h":5,"gl":"webgl2","draws":3,"tris":9}]}""")!!
        assertEquals(listOf("three"), s.libs); assertEquals(1, s.canvases.size)
    }

    // ---- adaptadores ----
    @Test fun githubAdapterRewritesBlobToRaw() {
        assertEquals("https://raw.githubusercontent.com/o/r/main/models/a.glb", GitHubAdapter.rawUrl("https://github.com/o/r/blob/main/models/a.glb"))
        assertEquals("https://raw.githubusercontent.com/o/r/v1/a%20b.glb", GitHubAdapter.rawUrl("https://github.com/o/r/raw/v1/a%20b.glb"))
        assertNull(GitHubAdapter.rawUrl("https://github.com/o/r"))
        val res = GitHubAdapter.identify("https://github.com/o/r/blob/main/m/a.glb", PageSignals("https://github.com/o/r/blob/main/m/a.glb"))
        assertEquals(1, res.size); assertEquals(ModelFormat.GLB, res[0].format); assertEquals("github", res[0].adapterId)
        assertTrue(GitHubAdapter.identify("https://github.com/o/r/blob/main/README.md", PageSignals("x")).isEmpty())
        assertTrue(analyze(PageSignals("https://github.com/o/r/blob/main/m/a.glb"), page = "https://github.com/o/r/blob/main/m/a.glb").resources.any { it.url.startsWith("https://raw.githubusercontent.com/") })
    }

    private val uid = "0123456789abcdef0123456789abcdef"

    @Test fun sketchfabIdentifiesUidAndParsesDocumentedShapes() {
        val a = SketchfabAdapter()
        assertEquals(uid, a.modelUid("https://sketchfab.com/3d-models/mi-modelo-lindo-$uid"))
        assertEquals(uid, a.modelUid("https://sketchfab.com/models/$uid/embed?autostart=1"))
        assertNull(a.modelUid("https://sketchfab.com/feed"))
        val found = a.identify("https://sketchfab.com/3d-models/x-$uid", PageSignals("https://sketchfab.com/3d-models/x-$uid"))
        assertEquals(1, found.size); assertEquals(ResourceKind.VIEWER_PAGE, found[0].kind)
        assertEquals(AuthRequirement.REQUIRED_TOKEN, a.authRequirement)
        val meta = a.parseMetadata("""{"uid":"$uid","name":"Robot","isDownloadable":true,"license":{"label":"CC Attribution"},"user":{"displayName":"Ana"},"faceCount":1200}""")!!
        assertTrue(meta.downloadable); assertEquals("CC Attribution", meta.licenseLabel); assertEquals(1200L, meta.faceCount)
        val link = a.parseDownloadResponse("""{"gltf":{"url":"https://s3.example.com/m.zip","size":1000,"expires":300},"usdz":{"url":"https://s3.example.com/m.usdz"}}""")!!
        assertEquals("gltf", link.key); assertEquals(300L, link.expires)
        assertNull(a.parseDownloadResponse("""{"error":"x"}"""))
    }

    private fun cannedClient(map: Map<String, Pair<Int, String>>, seenAuth: MutableList<String?> = ArrayList()) = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        seenAuth.add(req.header("Authorization"))
        val (code, body) = map[req.url.encodedPath] ?: (404 to "")
        okhttp3.Response.Builder().request(req).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toByteArray().toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun sketchfabOfficialExportRequiresTokenAndDownloadable() = kotlinx.coroutines.test.runTest {
        val a = SketchfabAdapter("https://api.test.invalid")
        val auth = ArrayList<String?>()
        val client = cannedClient(mapOf(
            "/v3/models/$uid" to (200 to """{"uid":"$uid","name":"Robot","isDownloadable":true,"license":{"label":"CC0"}}"""),
            "/v3/models/$uid/download" to (200 to """{"glb":{"url":"https://s3.example.com/r.glb","size":500,"expires":60}}"""),
        ), auth)
        val fetcher = SafeFetcher(client = client, blockPrivate = false)
        val noToken = assertThrows(AdapterException::class.java) { kotlinx.coroutines.runBlocking { a.officialExport(AdapterContext(fetcher) { null }, "https://sketchfab.com/3d-models/x-$uid") } }
        assertTrue(noToken.needsToken)
        val ok = a.officialExport(AdapterContext(fetcher) { "TOK" }, "https://sketchfab.com/3d-models/x-$uid")!!
        assertEquals(ModelFormat.GLB, ok.resource.format); assertEquals("CC0", ok.license)
        assertTrue(auth.contains("Token TOK"))
        // no descargable
        val client2 = cannedClient(mapOf("/v3/models/$uid" to (200 to """{"uid":"$uid","isDownloadable":false}""")))
        val e = assertThrows(AdapterException::class.java) { kotlinx.coroutines.runBlocking { a.officialExport(AdapterContext(SafeFetcher(client = client2, blockPrivate = false)) { "TOK" }, "https://sketchfab.com/3d-models/x-$uid") } }
        assertFalse(e.needsToken)
    }
}
