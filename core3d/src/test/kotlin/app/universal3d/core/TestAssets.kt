package app.universal3d.core

import app.universal3d.core.io.Png
import app.universal3d.core.model.*

/** Modelos de prueba generados por código (sin assets de terceros). */
object TestAssets {
    fun checkerPng(size: Int = 8): ByteArray {
        val px = ByteArray(size * size * 4)
        for (y in 0 until size) for (x in 0 until size) {
            val on = (x / 2 + y / 2) % 2 == 0
            val o = (y * size + x) * 4
            px[o] = (if (on) 230 else 40).toByte(); px[o + 1] = (if (on) 90 else 40).toByte(); px[o + 2] = (if (on) 30 else 200).toByte(); px[o + 3] = 255.toByte()
        }
        return Png.encodeRgba(size, size, px)
    }

    /** Cubo unitario con normales por cara, UV y textura de tablero. 24 vértices, 12 triángulos. */
    fun cubePrimitive(material: Int = 0): Primitive3D {
        val faces = arrayOf(
            floatArrayOf(0f, 0f, 1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f, 1f, 1f, -1f, 1f, 1f),
            floatArrayOf(0f, 0f, -1f, 1f, -1f, -1f, -1f, -1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f),
            floatArrayOf(1f, 0f, 0f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f, 1f, 1f),
            floatArrayOf(-1f, 0f, 0f, -1f, -1f, -1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, -1f),
            floatArrayOf(0f, 1f, 0f, -1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, -1f, -1f, 1f, -1f),
            floatArrayOf(0f, -1f, 0f, -1f, -1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, -1f, -1f, 1f),
        )
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>()
        val uvs = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        for ((fi, f) in faces.withIndex()) {
            for (v in 0 until 4) {
                pos.add(f[3 + v * 3] * 0.5f); pos.add(f[4 + v * 3] * 0.5f); pos.add(f[5 + v * 3] * 0.5f)
                nrm.add(f[0]); nrm.add(f[1]); nrm.add(f[2]); uv.add(uvs[v * 2]); uv.add(uvs[v * 2 + 1])
            }
            val b = fi * 4
            idx.addAll(listOf(b, b + 1, b + 2, b, b + 2, b + 3))
        }
        return Primitive3D(pos.toFloatArray(), idx.toIntArray(), nrm.toFloatArray(), uv.toFloatArray(), materialIndex = material)
    }

    fun texturedCube(): Scene3D {
        val png = checkerPng()
        val mat = Material3D(
            name = "Checker", baseColorFactor = floatArrayOf(1f, 0.9f, 0.8f, 1f), baseColorTexture = TextureRef(0),
            metallicFactor = 0.25f, roughnessFactor = 0.6f, normalTexture = TextureRef(1), emissiveFactor = floatArrayOf(0.1f, 0.05f, 0f),
        )
        return Scene3D(
            name = "cube", sourceFormat = ModelFormat.GLB,
            nodes = listOf(Node3D("Cubo", meshIndex = 0, translation = floatArrayOf(0f, 1f, 0f))),
            rootNodes = listOf(0), meshes = listOf(Mesh3D("CuboMesh", listOf(cubePrimitive()))),
            materials = listOf(mat), textures = listOf(Texture3D("albedo", 0), Texture3D("normal", 1)),
            images = listOf(Image3D("albedo.png", "image/png", png), Image3D("normal.png", "image/png", Png.solid(4, 4, 128, 128, 255))),
        )
    }

    /** Dos huesos + malla de 4 vértices en tira, con animación de rotación y traslación. */
    fun skinnedAnimated(): Scene3D {
        val pos = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 0f, 2f, 0f, 1f, 2f, 0f)
        val idx = intArrayOf(0, 1, 2, 1, 3, 2, 2, 3, 4, 3, 5, 4)
        val joints = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0)
        val w = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0.5f, 0.5f, 0f, 0f, 0.5f, 0.5f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)
        val prim = Primitive3D(pos, idx, joints = joints, weights = w, materialIndex = 0)
        val nodes = listOf(
            Node3D("Mesh", meshIndex = 0, skinIndex = 0),
            Node3D("Hueso0", children = listOf(2)),
            Node3D("Hueso1", translation = floatArrayOf(0f, 1f, 0f)),
        )
        val ibm = floatArrayOf(
            1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
            1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, -1f, 0f, 1f,
        )
        val rot = AnimSampler(floatArrayOf(0f, 1f, 2f), floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0.3826834f, 0.9238795f, 0f, 0f, 0f, 1f), Interpolation.LINEAR)
        val tr = AnimSampler(floatArrayOf(0f, 2f), floatArrayOf(0f, 1f, 0f, 0f, 1.5f, 0f), Interpolation.LINEAR)
        return Scene3D(
            "skinned", ModelFormat.GLB, nodes, listOf(0, 1), listOf(Mesh3D("Tira", listOf(prim))),
            listOf(Material3D("Rojo", baseColorFactor = floatArrayOf(0.8f, 0.1f, 0.1f, 1f))), emptyList(), emptyList(),
            skins = listOf(Skin3D("Piel", listOf(1, 2), ibm, 1)),
            animations = listOf(Animation3D("Doblar", listOf(AnimChannel(2, AnimPath.ROTATION, rot), AnimChannel(2, AnimPath.TRANSLATION, tr)))),
        )
    }
}
