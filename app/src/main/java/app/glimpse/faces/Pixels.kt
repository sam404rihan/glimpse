package app.glimpse.faces

import kotlin.math.floor

/** Model inputs from ARGB pixels, matching the desktop's OpenCV calls (see mobile_export/pipeline.py). */
object Pixels {
    const val FACE = 112

    /** Where ArcFace expects the eyes, nose and mouth corners in its 112x112 input. */
    val ARCFACE = floatArrayOf(
        38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f,
    )

    /** Bilinear resize like cv2.INTER_LINEAR: sample at (d + 0.5) * scale - 0.5, clamped at the edges. */
    fun resize(src: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        val xs = IntArray(nw); val xf = FloatArray(nw)
        for (x in 0 until nw) {
            var f = (x + 0.5f) * w / nw - 0.5f
            var i = floor(f).toInt()
            f -= i
            if (i < 0) { i = 0; f = 0f }
            if (i >= w - 1) { i = w - 1; f = 0f }
            xs[x] = i; xf[x] = f
        }
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            var fy = (y + 0.5f) * h / nh - 0.5f
            var y0 = floor(fy).toInt()
            fy -= y0
            if (y0 < 0) { y0 = 0; fy = 0f }
            if (y0 >= h - 1) { y0 = h - 1; fy = 0f }
            val r0 = y0 * w
            val r1 = minOf(y0 + 1, h - 1) * w
            for (x in 0 until nw) {
                val x0 = xs[x]; val x1 = minOf(x0 + 1, w - 1); val fx = xf[x]
                val a = src[r0 + x0]; val b = src[r0 + x1]; val c = src[r1 + x0]; val d = src[r1 + x1]
                var px = 0xff shl 24
                for (sh in intArrayOf(16, 8, 0)) {
                    val top = ((a shr sh) and 255) * (1 - fx) + ((b shr sh) and 255) * fx
                    val bot = ((c shr sh) and 255) * (1 - fx) + ((d shr sh) and 255) * fx
                    val v = (top * (1 - fy) + bot * fy + 0.5f).toInt().coerceIn(0, 255)
                    px = px or (v shl sh)
                }
                out[y * nw + x] = px
            }
        }
        return out
    }

    /** SCRFD input: the image letterboxed into the top-left of 640x640 as (v - 127.5) / 128, planar RGB.
     *  Returns the scale from image pixels to letterbox pixels. */
    fun detInput(px: IntArray, w: Int, h: Int, out: FloatArray): Float {
        val n = Scrfd.SIZE
        val scale = n.toDouble() / maxOf(w, h)
        val nw = maxOf(1, Math.rint(w * scale).toInt())
        val nh = maxOf(1, Math.rint(h * scale).toInt())
        val small = if (nw == w && nh == h) px else resize(px, w, h, nw, nh)
        val plane = n * n
        val pad = -127.5f / 128f
        for (y in 0 until n) {
            for (x in 0 until n) {
                val i = y * n + x
                if (x < nw && y < nh) {
                    val c = small[y * nw + x]
                    out[i] = (((c shr 16) and 255) - 127.5f) / 128f
                    out[plane + i] = (((c shr 8) and 255) - 127.5f) / 128f
                    out[2 * plane + i] = ((c and 255) - 127.5f) / 128f
                } else {
                    out[i] = pad; out[plane + i] = pad; out[2 * plane + i] = pad
                }
            }
        }
        return scale.toFloat()
    }

    /** ArcFace input: warp by [m] (image -> 112x112 face) with bilinear sampling and black outside the image,
     *  like cv2.warpAffine(..., borderValue=0), then (v - 127.5) / 127.5 planar RGB into [out] at [offset]. */
    fun alignedInput(px: IntArray, w: Int, h: Int, m: DoubleArray, out: FloatArray, offset: Int) {
        val det = m[0] * m[4] - m[1] * m[3]
        val ia = m[4] / det; val ib = -m[1] / det; val id = -m[3] / det; val ie = m[0] / det
        val ic = -(ia * m[2] + ib * m[5]); val iff = -(id * m[2] + ie * m[5])
        val plane = FACE * FACE
        val rgb = DoubleArray(3)
        for (y in 0 until FACE) {
            for (x in 0 until FACE) {
                val sx = ia * x + ib * y + ic
                val sy = id * x + ie * y + iff
                val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
                val fx = sx - x0; val fy = sy - y0
                rgb.fill(0.0)
                for (k in 0 until 4) {
                    val xx = x0 + (k and 1); val yy = y0 + (k shr 1)
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val wt = (if (k and 1 == 1) fx else 1 - fx) * (if (k shr 1 == 1) fy else 1 - fy)
                    val c = px[yy * w + xx]
                    rgb[0] += wt * ((c shr 16) and 255); rgb[1] += wt * ((c shr 8) and 255); rgb[2] += wt * (c and 255)
                }
                val i = offset + y * FACE + x
                for (ch in 0 until 3) {
                    val v = Math.rint(rgb[ch]).coerceIn(0.0, 255.0)
                    out[i + ch * plane] = ((v - 127.5) / 127.5).toFloat()
                }
            }
        }
    }

    /** Least-squares similarity transform (rotation + uniform scale + shift) [src] -> [dst] points, as 2x3. */
    fun similarity(src: FloatArray, dst: FloatArray = ARCFACE): DoubleArray {
        val n = src.size / 2
        var smx = 0.0; var smy = 0.0; var dmx = 0.0; var dmy = 0.0
        for (i in 0 until n) { smx += src[2 * i]; smy += src[2 * i + 1]; dmx += dst[2 * i]; dmy += dst[2 * i + 1] }
        smx /= n; smy /= n; dmx /= n; dmy /= n
        var norm = 0.0; var a = 0.0; var b = 0.0
        for (i in 0 until n) {
            val sx = src[2 * i] - smx; val sy = src[2 * i + 1] - smy
            val dx = dst[2 * i] - dmx; val dy = dst[2 * i + 1] - dmy
            norm += sx * sx + sy * sy
            a += sx * dx + sy * dy
            b += sx * dy - sy * dx
        }
        a /= norm; b /= norm
        return doubleArrayOf(a, -b, dmx - (a * smx - b * smy), b, a, dmy - (b * smx + a * smy))
    }
}
