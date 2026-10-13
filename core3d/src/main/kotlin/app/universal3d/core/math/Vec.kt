package app.universal3d.core.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Matriz 4x4 column-major (misma convención que OpenGL y glTF). */
class Mat4(val m: FloatArray = identityArray()) {
    init { require(m.size == 16) { "Mat4 requiere 16 elementos" } }

    operator fun times(o: Mat4): Mat4 {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += m[k * 4 + row] * o.m[c * 4 + k]
            r[c * 4 + row] = s
        }
        return Mat4(r)
    }

    fun copy() = Mat4(m.copyOf())

    fun transformPoint(x: Float, y: Float, z: Float, out: FloatArray = FloatArray(3)): FloatArray {
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        val iw = if (w != 0f && w != 1f) 1f / w else 1f
        out[0] = (m[0] * x + m[4] * y + m[8] * z + m[12]) * iw
        out[1] = (m[1] * x + m[5] * y + m[9] * z + m[13]) * iw
        out[2] = (m[2] * x + m[6] * y + m[10] * z + m[14]) * iw
        return out
    }

    fun transformDirection(x: Float, y: Float, z: Float, out: FloatArray = FloatArray(3)): FloatArray {
        out[0] = m[0] * x + m[4] * y + m[8] * z
        out[1] = m[1] * x + m[5] * y + m[9] * z
        out[2] = m[2] * x + m[6] * y + m[10] * z
        return out
    }

    fun determinant3(): Float =
        m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

    fun inverse(): Mat4? {
        val a = m
        val inv = FloatArray(16)
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10]
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10]
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9]
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9]
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10]
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10]
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9]
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9]
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6]
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6]
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5]
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5]
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6]
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6]
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5]
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5]
        val det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12]
        if (abs(det) < 1e-20f || det.isNaN()) return null
        val id = 1f / det
        for (i in 0 until 16) inv[i] *= id
        return Mat4(inv)
    }

    fun transposed(): Mat4 {
        val r = FloatArray(16)
        for (c in 0 until 4) for (rr in 0 until 4) r[c * 4 + rr] = m[rr * 4 + c]
        return Mat4(r)
    }

    /** Descompone en traslación, rotación (cuaternión xyzw) y escala. Asume sin cizalla. */
    fun decompose(): Trs {
        val t = floatArrayOf(m[12], m[13], m[14])
        var sx = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        val sy = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6])
        val sz = sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
        if (determinant3() < 0f) sx = -sx
        val isx = if (sx != 0f) 1f / sx else 0f
        val isy = if (sy != 0f) 1f / sy else 0f
        val isz = if (sz != 0f) 1f / sz else 0f
        val r = floatArrayOf(
            m[0] * isx, m[1] * isx, m[2] * isx,
            m[4] * isy, m[5] * isy, m[6] * isy,
            m[8] * isz, m[9] * isz, m[10] * isz,
        )
        return Trs(t, Quat.fromRotationMatrix(r), floatArrayOf(sx, sy, sz))
    }

    companion object {
        fun identityArray() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        fun identity() = Mat4()

        fun fromTrs(t: FloatArray, q: FloatArray, s: FloatArray): Mat4 {
            val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
            val xx = x * x; val yy = y * y; val zz = z * z
            val xy = x * y; val xz = x * z; val yz = y * z
            val wx = w * x; val wy = w * y; val wz = w * z
            val r = FloatArray(16)
            r[0] = (1 - 2 * (yy + zz)) * s[0]; r[1] = 2 * (xy + wz) * s[0]; r[2] = 2 * (xz - wy) * s[0]; r[3] = 0f
            r[4] = 2 * (xy - wz) * s[1]; r[5] = (1 - 2 * (xx + zz)) * s[1]; r[6] = 2 * (yz + wx) * s[1]; r[7] = 0f
            r[8] = 2 * (xz + wy) * s[2]; r[9] = 2 * (yz - wx) * s[2]; r[10] = (1 - 2 * (xx + yy)) * s[2]; r[11] = 0f
            r[12] = t[0]; r[13] = t[1]; r[14] = t[2]; r[15] = 1f
            return Mat4(r)
        }

        fun translation(x: Float, y: Float, z: Float) = fromTrs(floatArrayOf(x, y, z), Quat.IDENTITY, floatArrayOf(1f, 1f, 1f))
        fun scale(x: Float, y: Float, z: Float) = fromTrs(floatArrayOf(0f, 0f, 0f), Quat.IDENTITY, floatArrayOf(x, y, z))

        fun perspective(fovYRad: Float, aspect: Float, near: Float, far: Float): Mat4 {
            val f = 1f / kotlin.math.tan(fovYRad / 2f)
            val r = FloatArray(16)
            r[0] = f / aspect
            r[5] = f
            r[10] = (far + near) / (near - far)
            r[11] = -1f
            r[14] = 2f * far * near / (near - far)
            return Mat4(r)
        }

        fun lookAt(eye: FloatArray, target: FloatArray, up: FloatArray): Mat4 {
            var fx = target[0] - eye[0]; var fy = target[1] - eye[1]; var fz = target[2] - eye[2]
            val fl = sqrt(fx * fx + fy * fy + fz * fz).let { if (it == 0f) 1f else it }
            fx /= fl; fy /= fl; fz /= fl
            var sx = fy * up[2] - fz * up[1]; var sy = fz * up[0] - fx * up[2]; var sz = fx * up[1] - fy * up[0]
            val sl = sqrt(sx * sx + sy * sy + sz * sz).let { if (it == 0f) 1f else it }
            sx /= sl; sy /= sl; sz /= sl
            val ux = sy * fz - sz * fy; val uy = sz * fx - sx * fz; val uz = sx * fy - sy * fx
            val r = FloatArray(16)
            r[0] = sx; r[1] = ux; r[2] = -fx
            r[4] = sy; r[5] = uy; r[6] = -fy
            r[8] = sz; r[9] = uz; r[10] = -fz
            r[12] = -(sx * eye[0] + sy * eye[1] + sz * eye[2])
            r[13] = -(ux * eye[0] + uy * eye[1] + uz * eye[2])
            r[14] = (fx * eye[0] + fy * eye[1] + fz * eye[2])
            r[15] = 1f
            return Mat4(r)
        }
    }
}

class Trs(val t: FloatArray, val q: FloatArray, val s: FloatArray)

object Quat {
    val IDENTITY get() = floatArrayOf(0f, 0f, 0f, 1f)

    fun normalize(q: FloatArray): FloatArray {
        val l = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (l == 0f) return IDENTITY
        return floatArrayOf(q[0] / l, q[1] / l, q[2] / l, q[3] / l)
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    fun fromAxisAngle(x: Float, y: Float, z: Float, angle: Float): FloatArray {
        val s = sin(angle / 2f)
        return floatArrayOf(x * s, y * s, z * s, cos(angle / 2f))
    }

    /** Matriz de rotación 3x3 column-major (9 floats). */
    fun fromRotationMatrix(r: FloatArray): FloatArray {
        val m00 = r[0]; val m10 = r[1]; val m20 = r[2]
        val m01 = r[3]; val m11 = r[4]; val m21 = r[5]
        val m02 = r[6]; val m12 = r[7]; val m22 = r[8]
        val tr = m00 + m11 + m22
        val q = FloatArray(4)
        if (tr > 0f) {
            val s = sqrt(tr + 1f) * 2f
            q[3] = 0.25f * s; q[0] = (m21 - m12) / s; q[1] = (m02 - m20) / s; q[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            q[3] = (m21 - m12) / s; q[0] = 0.25f * s; q[1] = (m01 + m10) / s; q[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            q[3] = (m02 - m20) / s; q[0] = (m01 + m10) / s; q[1] = 0.25f * s; q[2] = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            q[3] = (m10 - m01) / s; q[0] = (m02 + m20) / s; q[1] = (m12 + m21) / s; q[2] = 0.25f * s
        }
        return normalize(q)
    }

    fun slerp(a: FloatArray, b0: FloatArray, t: Float): FloatArray {
        var b = b0
        var dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        if (dot < 0f) { b = floatArrayOf(-b[0], -b[1], -b[2], -b[3]); dot = -dot }
        if (dot > 0.9995f) {
            return normalize(floatArrayOf(
                a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]),
                a[2] + t * (b[2] - a[2]), a[3] + t * (b[3] - a[3]),
            ))
        }
        val theta0 = acos(dot.coerceIn(-1f, 1f))
        val theta = theta0 * t
        val s0 = cos(theta) - dot * sin(theta) / sin(theta0)
        val s1 = sin(theta) / sin(theta0)
        return floatArrayOf(s0 * a[0] + s1 * b[0], s0 * a[1] + s1 * b[1], s0 * a[2] + s1 * b[2], s0 * a[3] + s1 * b[3])
    }

    /**
     * Euler XYZ intrínseco al estilo FBX (RotationOrder = eEulerXYZ): R = Rz * Ry * Rx. Ángulos en grados.
     */
    fun fromEulerXyzDeg(xDeg: Float, yDeg: Float, zDeg: Float): FloatArray {
        val d = (Math.PI / 180.0).toFloat()
        val qx = fromAxisAngle(1f, 0f, 0f, xDeg * d)
        val qy = fromAxisAngle(0f, 1f, 0f, yDeg * d)
        val qz = fromAxisAngle(0f, 0f, 1f, zDeg * d)
        return normalize(multiply(qz, multiply(qy, qx)))
    }

    /** Inversa de [fromEulerXyzDeg]. */
    fun toEulerXyzDeg(q: FloatArray): FloatArray {
        val x = q[0].toDouble(); val y = q[1].toDouble(); val z = q[2].toDouble(); val w = q[3].toDouble()
        // matriz de rotación R = Rz*Ry*Rx
        val r20 = 2 * (x * z - w * y)
        val r00 = 1 - 2 * (y * y + z * z)
        val r10 = 2 * (x * y + w * z)
        val r21 = 2 * (y * z + w * x)
        val r22 = 1 - 2 * (x * x + y * y)
        val sy = (-r20).coerceIn(-1.0, 1.0)
        val ey = Math.asin(sy)
        val ex: Double
        val ez: Double
        if (abs(sy) < 0.999999) {
            ex = atan2(r21, r22)
            ez = atan2(r10, r00)
        } else {
            val r01 = 2 * (x * y - w * z)
            val r11 = 1 - 2 * (x * x + z * z)
            ex = atan2(-2 * (y * z - w * x), r11)
            ez = 0.0
            if (r01.isNaN()) return floatArrayOf(0f, 0f, 0f)
        }
        val k = 180.0 / Math.PI
        return floatArrayOf((ex * k).toFloat(), (ey * k).toFloat(), (ez * k).toFloat())
    }
}

class Aabb(
    var minX: Float = Float.POSITIVE_INFINITY, var minY: Float = Float.POSITIVE_INFINITY, var minZ: Float = Float.POSITIVE_INFINITY,
    var maxX: Float = Float.NEGATIVE_INFINITY, var maxY: Float = Float.NEGATIVE_INFINITY, var maxZ: Float = Float.NEGATIVE_INFINITY,
) {
    val isEmpty get() = minX > maxX
    fun add(x: Float, y: Float, z: Float) {
        minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
        maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
    }
    fun add(o: Aabb) { if (!o.isEmpty) { add(o.minX, o.minY, o.minZ); add(o.maxX, o.maxY, o.maxZ) } }
    val centerX get() = (minX + maxX) / 2f
    val centerY get() = (minY + maxY) / 2f
    val centerZ get() = (minZ + maxZ) / 2f
    val sizeX get() = maxX - minX
    val sizeY get() = maxY - minY
    val sizeZ get() = maxZ - minZ
    val radius get() = if (isEmpty) 0f else 0.5f * sqrt(sizeX * sizeX + sizeY * sizeY + sizeZ * sizeZ)

    fun transformed(mat: Mat4): Aabb {
        val out = Aabb()
        if (isEmpty) return out
        val p = FloatArray(3)
        for (i in 0 until 8) {
            mat.transformPoint(if (i and 1 == 0) minX else maxX, if (i and 2 == 0) minY else maxY, if (i and 4 == 0) minZ else maxZ, p)
            out.add(p[0], p[1], p[2])
        }
        return out
    }
}
