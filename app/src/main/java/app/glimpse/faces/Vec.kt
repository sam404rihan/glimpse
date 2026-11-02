package app.glimpse.faces

import kotlin.math.sqrt

object Vec {
    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(dot(v, v)) + 1e-9f
        for (i in v.indices) v[i] /= n
        return v
    }

    /** Little-endian IEEE half floats: identity vectors are stored at 1 KB per face. */
    fun toHalfBytes(v: FloatArray): ByteArray {
        val out = ByteArray(v.size * 2)
        for (i in v.indices) {
            val h = toHalf(v[i])
            out[2 * i] = h.toByte(); out[2 * i + 1] = (h shr 8).toByte()
        }
        return out
    }

    fun fromHalfBytes(b: ByteArray): FloatArray =
        FloatArray(b.size / 2) { toFloat((b[2 * it].toInt() and 255) or ((b[2 * it + 1].toInt() and 255) shl 8)) }

    fun toHalf(f: Float): Int {
        val bits = java.lang.Float.floatToRawIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xff
        var mant = bits and 0x7fffff
        if (exp == 0xff) return sign or 0x7c00 or (if (mant != 0) 0x200 else 0)
        val e = exp - 127 + 15
        if (e >= 0x1f) return sign or 0x7c00
        if (e <= 0) {
            if (e < -10) return sign
            mant = mant or 0x800000
            val shift = 14 - e
            var h = mant ushr shift
            val rem = mant and ((1 shl shift) - 1)
            val half = 1 shl (shift - 1)
            if (rem > half || (rem == half && (h and 1) != 0)) h++
            return sign or h
        }
        var h = (e shl 10) or (mant ushr 13)
        val rem = mant and 0x1fff
        if (rem > 0x1000 || (rem == 0x1000 && (h and 1) != 0)) h++
        return sign or h
    }

    fun toFloat(h: Int): Float {
        val sign = (h and 0x8000) shl 16
        val exp = (h ushr 10) and 0x1f
        val mant = h and 0x3ff
        val bits = when (exp) {
            0 -> if (mant == 0) sign else {
                var e = 127 - 15 + 1
                var m = mant
                while (m and 0x400 == 0) { m = m shl 1; e-- }
                sign or (e shl 23) or ((m and 0x3ff) shl 13)
            }
            0x1f -> sign or 0x7f800000 or (mant shl 13)
            else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }
}
