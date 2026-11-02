package app.glimpse.android

import android.content.Context
import app.glimpse.index.Analysis
import app.glimpse.index.FaceAnalyzer
import app.glimpse.index.FoundFace
import app.glimpse.index.MediaItem
import java.io.IOException
import java.util.concurrent.Executors

/** Finds faces on the paired PC (pc_helper.py), which runs the same buffalo_m models at full precision.
 *  The phone decodes each photo to 1600 px as it would for itself and sends that JPEG; the PC keeps nothing.
 *  If the PC goes away mid-scan, the rest of the scan runs on the phone. */
class PcFaceAnalyzer(private val context: Context, private val pc: PcClient) : FaceAnalyzer {
    private val cr = context.contentResolver
    private val pool = Executors.newFixedThreadPool(WORKERS) // decode + upload of one photo overlaps the PC's work on others
    @Volatile var pcDown = false; private set
    private var phone: PhoneFaceAnalyzer? = null // only loaded if the PC can't do some photo

    override fun analyze(items: List<MediaItem>): List<Analysis> {
        val onPc = items.map { item -> pool.submit<Analysis?> { if (pcDown) null else remote(item) } }
        return items.zip(onPc).map { (item, f) -> f.get() ?: onPhone(item) }
    }

    /** null: the PC couldn't analyse it, so the phone does. */
    private fun remote(item: MediaItem): Analysis? {
        val e = runCatching { Decode.jpeg(cr, item.uri()) }.getOrElse { return Analysis.Failed }
        if (minOf(e.origW, e.origH) < Decode.MIN_SIDE) return Analysis.Faces(e.origW, e.origH, emptyList())
        val found = try {
            pc.faces(e.jpeg)
        } catch (ex: PcRefused) {
            if (ex.status == 401 || ex.status == 403) pcDown = true // unpaired: don't send it anything more
            return null
        } catch (ex: IOException) {
            pcDown = true
            return null
        } catch (ex: Exception) { // malformed answer
            return null
        }
        return Analysis.Faces(e.origW, e.origH, found.map { f ->
            val b = f.box
            FoundFace(
                floatArrayOf(maxOf(0f, b[0] / e.w), maxOf(0f, b[1] / e.h), minOf(1f, b[2] / e.w), minOf(1f, b[3] / e.h)),
                f.score, minOf(b[2] - b[0], b[3] - b[1]), f.emb,
            )
        })
    }

    private fun onPhone(item: MediaItem): Analysis =
        (phone ?: PhoneFaceAnalyzer(context).also { phone = it }).analyze(listOf(item)).single()

    override fun close() {
        pool.shutdownNow()
        phone?.close()
    }

    companion object {
        const val WORKERS = 4
    }
}
