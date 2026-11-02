package app.glimpse.android

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import app.glimpse.index.Analysis
import app.glimpse.index.FaceAnalyzer
import app.glimpse.index.FoundFace
import app.glimpse.index.MediaItem
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

class Decoded(val px: IntArray, val w: Int, val h: Int, val origW: Int, val origH: Int)

object Decode {
    const val ANALYSE_SIDE = 1600 // like people.py on the desktop
    const val MIN_SIDE = 64 // icons, emoji and tracking pixels are not photos

    /** Decodes once, already scaled down to at most [side] px (JPEG decodes at reduced scale), EXIF rotation applied. */
    fun pixels(cr: ContentResolver, uri: Uri, side: Int = ANALYSE_SIDE): Decoded = bitmap(cr, uri, side) { bmp, ow, oh ->
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        Decoded(px, bmp.width, bmp.height, ow, oh)
    }

    /** The same image as [pixels], as a JPEG for the PC. Rotation is already applied, so it carries no EXIF. */
    fun jpeg(cr: ContentResolver, uri: Uri, side: Int = ANALYSE_SIDE): Encoded = bitmap(cr, uri, side) { bmp, ow, oh ->
        val out = ByteArrayOutputStream(256 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        Encoded(out.toByteArray(), bmp.width, bmp.height, ow, oh)
    }

    private fun <T> bitmap(cr: ContentResolver, uri: Uri, side: Int, use: (Bitmap, Int, Int) -> T): T {
        var ow = 0
        var oh = 0
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { dec, info, _ ->
            ow = info.size.width; oh = info.size.height
            val s = minOf(1.0, side.toDouble() / maxOf(ow, oh))
            if (s < 1.0) dec.setTargetSize(maxOf(1, (ow * s).roundToInt()), maxOf(1, (oh * s).roundToInt()))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            return use(bmp, ow, oh)
        } finally {
            bmp.recycle()
        }
    }
}

class Encoded(val jpeg: ByteArray, val w: Int, val h: Int, val origW: Int, val origH: Int)

/** Decodes up to 3 photos ahead on 2 threads while ONNX Runtime works on the current one. */
class PhoneFaceAnalyzer(context: Context, private val engine: FaceEngine = FaceEngine(context)) : FaceAnalyzer {
    private val cr = context.contentResolver
    private val pool = Executors.newFixedThreadPool(2)

    override fun analyze(items: List<MediaItem>): List<Analysis> {
        val ahead = ArrayDeque<Future<Decoded?>>()
        var next = 0
        fun fill() {
            while (ahead.size < 3 && next < items.size) {
                val uri = items[next++].uri()
                ahead.addLast(pool.submit<Decoded?> { runCatching { Decode.pixels(cr, uri) }.getOrNull() })
            }
        }
        val out = ArrayList<Analysis>(items.size)
        fill()
        while (ahead.isNotEmpty()) {
            val d = ahead.removeFirst().get()
            fill()
            out += when {
                d == null -> Analysis.Failed
                minOf(d.origW, d.origH) < Decode.MIN_SIDE -> Analysis.Faces(d.origW, d.origH, emptyList())
                else -> runCatching {
                    val faces = engine.analyze(d.px, d.w, d.h).map { (det, emb) ->
                        val b = det.box
                        FoundFace(
                            floatArrayOf(maxOf(0f, b[0] / d.w), maxOf(0f, b[1] / d.h), minOf(1f, b[2] / d.w), minOf(1f, b[3] / d.h)),
                            det.score, det.size, emb,
                        )
                    }
                    Analysis.Faces(d.origW, d.origH, faces)
                }.getOrElse { Analysis.Failed }
            }
        }
        return out
    }

    override fun close() {
        pool.shutdownNow()
        engine.close()
    }
}
