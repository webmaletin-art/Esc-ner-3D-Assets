package app.universal3d.core

import app.universal3d.core.io.*
import app.universal3d.core.model.*
import org.junit.Assert.*
import org.junit.Test

class FormatRoundTripTest {
    private fun tris(s: Scene3D) = s.meshes.sumOf { m -> m.primitives.sumOf { it.triangleCount } }
    private fun verts(s: Scene3D) = s.meshes.sumOf { m -> m.primitives.sumOf { it.vertexCount } }

    @Test fun glbRoundTripKeepsGeometryMaterialsAndTextures() {
        val src = TestAssets.texturedCube()
        val glb = GlbWriter.write(src)
        val back = ModelImporter.importBytes("cube.glb", glb.bytes)
        assertEquals(ModelFormat.GLB, back.sourceFormat)
        assertEquals(12, tris(back)); assertEquals(24, verts(back))
        assertEquals(1, back.materials.size)
        val m = back.materials[0]
        assertEquals("Checker", m.name)
        assertEquals(0.25f, m.metallicFactor, 1e-6f); assertEquals(0.6f, m.roughnessFactor, 1e-6f)
        assertNotNull(m.baseColorTexture); assertNotNull(m.normalTexture)
        assertEquals(2, back.images.size)
        assertArrayEquals(src.images[0].bytes, back.images[m.baseColorTexture!!.textureIndex.let { back.textures[it].imageIndex }].bytes)
        assertEquals(0.1f, m.emissiveFactor[0], 1e-6f)
        assertNotNull(back.meshes[0].primitives[0].uv0); assertNotNull(back.meshes[0].primitives[0].normals)
        assertArrayEquals(src.meshes[0].primitives[0].positions, back.meshes[0].primitives[0].positions, 0f)
        assertEquals(1f, back.nodes[0].translation[1], 0f)
    }

    @Test fun glbRoundTripKeepsSkinAndAnimation() {
        val src = TestAssets.skinnedAnimated()
        val back = ModelImporter.importBytes("sk.glb", GlbWriter.write(src).bytes)
        assertEquals(1, back.skins.size)
        assertEquals(listOf(1, 2), back.skins[0].joints)
        assertEquals(32, back.skins[0].inverseBindMatrices!!.size)
        assertEquals(1, back.animations.size)
        assertEquals("Doblar", back.animations[0].name)
        assertEquals(2, back.animations[0].channels.size)
        assertEquals(2f, back.animations[0].duration, 1e-6f)
        val p = back.meshes[0].primitives[0]
        assertNotNull(p.joints); assertNotNull(p.weights)
        assertArrayEquals(src.meshes[0].primitives[0].joints, p.joints)
    }

    @Test fun objExportPackagesTexturesAndReimports() {
        val src = TestAssets.texturedCube()
        val zip = ObjWriter.write(src, "Mi Cubo (1)")
        assertTrue(zip.files.contains("Mi_Cubo_1.obj"))
        assertTrue(zip.files.contains("Mi_Cubo_1.mtl"))
        assertTrue(zip.files.any { it.startsWith("textures/") && it.endsWith(".png") })
        val bundle = ModelImporter.unzip(zip.zip)
        val mtl = String(bundle.find("Mi_Cubo_1.mtl")!!.read())
        assertTrue(mtl.contains("map_Kd textures/albedo.png"))
        // referencias relativas existen en el paquete
        assertNotNull(bundle.find("textures/albedo.png", "Mi_Cubo_1.mtl"))
        val back = ModelImporter.importBundle(bundle)
        assertEquals(ModelFormat.OBJ, back.sourceFormat)
        assertEquals(12, tris(back))
        assertTrue(back.missingDependencies.isEmpty())
        assertNotNull(back.materials[0].baseColorTexture)
        assertNotNull(back.materials[0].normalTexture)
        // V invertida dos veces => idéntica
        assertArrayEquals(src.meshes[0].primitives[0].uv0!!.copyOf(8), back.meshes[0].primitives[0].uv0!!.copyOf(8), 1e-5f)
        // El nodo trasladado (0,1,0) se aplicó a los vértices
        val b = back.bounds()
        assertEquals(1f, b.centerY, 1e-4f)
        assertTrue(zip.notices.any { it.code == "hier" })
    }

    @Test fun objReportsMissingDependencies() {
        val obj = "mtllib m.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0 1\nusemtl a\nf 1/1 2/2 3/3\n"
        val mtl = "newmtl a\nKd 1 0 0\nmap_Kd missing.png\n"
        val b = FileBundle(listOf(BundleFile.ofBytes("m.obj", obj.toByteArray()), BundleFile.ofBytes("m.mtl", mtl.toByteArray())))
        val s = ModelImporter.importBundle(b)
        assertEquals(listOf("missing.png"), s.missingDependencies)
        assertEquals(1, tris(s))
        val noMtl = ModelImporter.importBundle(FileBundle(listOf(BundleFile.ofBytes("m.obj", obj.toByteArray()))))
        assertTrue(noMtl.missingDependencies.contains("m.mtl"))
    }

    @Test fun objHandlesNegativeIndicesAndQuads() {
        val obj = "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nf -4 -3 -2 -1\n"
        val s = ModelImporter.importBytes("q.obj", obj.toByteArray())
        assertEquals(2, tris(s))
        assertNotNull(s.meshes[0].primitives[0].normals)
    }

    @Test fun malformedObjGivesClearError() {
        val e = assertThrows(ModelImportException::class.java) { ModelImporter.importBytes("x.obj", "v 0 0 0\nv 1 0 0\nf 1 2 9\n".toByteArray()) }
        assertEquals(ModelImportException.Code.MALFORMED, e.code)
        val e2 = assertThrows(ModelImportException::class.java) { ModelImporter.importBytes("y.obj", "# nada\n".toByteArray()) }
        assertEquals(ModelImportException.Code.EMPTY, e2.code)
    }

    @Test fun fbxExportImportRoundTrip() {
        val src = TestAssets.texturedCube()
        val fbx = FbxWriter.write(src)
        assertTrue(FbxParser.isBinary(fbx.bytes))
        val back = ModelImporter.importBytes("cube.fbx", fbx.bytes)
        assertEquals(ModelFormat.FBX, back.sourceFormat)
        assertEquals(12, tris(back))
        assertEquals(1, back.materials.size)
        assertNotNull(back.materials[0].baseColorTexture); assertNotNull(back.materials[0].normalTexture)
        assertEquals(0.8f, back.materials[0].baseColorFactor[1] * 1f / 0.9f * 0.8f, 0.05f)
        assertEquals(2, back.images.size)
        assertArrayEquals(src.images[0].bytes, back.images.first { it.name == "albedo.png" }.bytes)
        val b = back.bounds()
        assertEquals(1f, b.centerY, 1e-4f); assertEquals(1f, b.sizeX, 1e-4f)
        assertTrue(fbx.notices.any { it.severity == Notice.Severity.LOSS })
    }

    @Test fun fbxReportsLossesForSkinsAndAnimations() {
        val fbx = FbxWriter.write(TestAssets.skinnedAnimated())
        assertTrue(fbx.notices.any { it.code == "skin" })
        assertTrue(fbx.notices.any { it.code == "anim" })
    }

    @Test fun truncatedFbxFailsCleanly() {
        val fbx = FbxWriter.write(TestAssets.texturedCube()).bytes
        assertThrows(ModelImportException::class.java) { ModelImporter.importBytes("t.fbx", fbx.copyOf(fbx.size / 2)) }
    }

    @Test fun truncatedGlbFailsCleanly() {
        val glb = GlbWriter.write(TestAssets.texturedCube()).bytes
        val e = assertThrows(ModelImportException::class.java) { ModelImporter.importBytes("t.glb", glb.copyOf(glb.size - 100)) }
        assertTrue(e.code == ModelImportException.Code.MALFORMED || e.code == ModelImportException.Code.INCOMPLETE)
    }

    @Test fun dracoRequiredIsReportedAsUnsupported() {
        val json = """{"asset":{"version":"2.0"},"extensionsRequired":["KHR_draco_mesh_compression"],"extensionsUsed":["KHR_draco_mesh_compression"]}"""
        val e = assertThrows(UnsupportedFormatException::class.java) { GltfReader.readGltf(json, "d", null, null) }
        assertTrue(e.message!!.contains("Draco"))
    }

    @Test fun gltfWithExternalBinAndMissingTexture() {
        val glb = GlbWriter.write(TestAssets.texturedCube()).bytes
        // Convertir a gltf+bin manualmente: se parte el GLB
        val bb = java.nio.ByteBuffer.wrap(glb).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val jl = bb.getInt(12)
        val json = String(glb, 20, jl).trim()
        val binLen = bb.getInt(20 + jl)
        val bin = glb.copyOfRange(20 + jl + 8, 20 + jl + 8 + binLen)
        val gltf = json.replace("\"buffers\":[{", "\"buffers\":[{\"uri\":\"data.bin\",")
        val ok = ModelImporter.importBundle(FileBundle(listOf(BundleFile.ofBytes("a/m.gltf", gltf.toByteArray()), BundleFile.ofBytes("a/data.bin", bin))))
        assertEquals(12, tris(ok))
        assertEquals(ModelFormat.GLTF, ok.sourceFormat)
        assertEquals(listOf("data.bin"), GltfReader.listExternalUris(gltf))
        val missing = ModelImporter.importBundle(FileBundle(listOf(BundleFile.ofBytes("m.gltf", gltf.toByteArray()))).let { it }, "x").let { it }
        assertTrue(missing.missingDependencies.isNotEmpty())
    }
}
