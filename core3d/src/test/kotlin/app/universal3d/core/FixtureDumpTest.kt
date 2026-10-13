package app.universal3d.core

import app.universal3d.core.io.*
import org.junit.Test
import java.io.File

/** Escribe archivos de ejemplo en build/fixtures para validarlos con herramientas externas (trimesh, assimp, gltf-validator). */
class FixtureDumpTest {
    @Test fun dump() {
        val dir = File("build/fixtures").apply { mkdirs() }
        val cube = TestAssets.texturedCube()
        File(dir, "cube.glb").writeBytes(GlbWriter.write(cube).bytes)
        File(dir, "skinned.glb").writeBytes(GlbWriter.write(TestAssets.skinnedAnimated()).bytes)
        File(dir, "cube_obj.zip").writeBytes(ObjWriter.write(cube, "cube").zip)
        File(dir, "cube.fbx").writeBytes(FbxWriter.write(cube).bytes)
    }
}
