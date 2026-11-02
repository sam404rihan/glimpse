package app.glimpse.android

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.res.AssetManager
import androidx.annotation.Keep
import app.glimpse.faces.Detection
import app.glimpse.faces.Pixels
import app.glimpse.faces.Scrfd
import app.glimpse.faces.Vec
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/** Bump when the shipped models change: every photo is then analysed again, once. */
const val MODEL_VERSION = 1

/** SCRFD detector + ArcFace recogniser on ONNX Runtime (CPU, one thread per big core).
 *  The models are memory-mapped straight out of the APK, so they never sit on the Java heap. */
class FaceEngine(assets: AssetManager, private val recBatch: Int = REC_BATCH) : AutoCloseable {
    constructor(context: Context) : this(context.assets)

    private val env = OrtEnvironment.getEnvironment()
    // The sessions read these mappings directly, so they must live as long as the engine. @Keep stops R8 from
    // turning them into locals; the buffers would then be collected and unmapped mid-scan (SIGSEGV in ORT).
    @Keep private val detBuf = map(assets, "models/det.ort")
    @Keep private val recBuf = map(assets, "models/rec.ort")
    private val det = env.createSession(detBuf, options())
    private val rec = env.createSession(recBuf, options())
    private val detName = det.inputNames.first()
    private val recName = rec.inputNames.first()
    private val detInput = FloatArray(3 * Scrfd.SIZE * Scrfd.SIZE)

    /** Faces in an ARGB image ([w] x [h]) with their unit-length 512-d identity vectors. */
    fun analyze(px: IntArray, w: Int, h: Int): List<Pair<Detection, FloatArray>> {
        val scale = Pixels.detInput(px, w, h, detInput)
        val outs = OnnxTensor.createTensor(env, FloatBuffer.wrap(detInput), longArrayOf(1, 3, Scrfd.SIZE.toLong(), Scrfd.SIZE.toLong())).use { t ->
            det.run(mapOf(detName to t)).use { r -> headOutputs(r) }
        }
        val dets = Scrfd.decode(outs).map { it.scaled(1f / scale) }.filter { it.size >= Scrfd.MIN_FACE_PX }
        if (dets.isEmpty()) return emptyList()
        val n = Pixels.FACE * Pixels.FACE * 3
        val crops = FloatArray(dets.size * n)
        dets.forEachIndexed { i, d -> Pixels.alignedInput(px, w, h, Pixels.similarity(d.kps), crops, i * n) }
        // At most recBatch faces per run: ORT's arena grows to the biggest batch and never shrinks. Measured on the
        // Galaxy A54: 4 faces +69 MB, 32 faces +1 GB, 64 faces +2.1 GB, enough for Android to kill the scan.
        val embs = ArrayList<FloatArray>(dets.size)
        for (start in dets.indices step recBatch) {
            val m = minOf(recBatch, dets.size - start)
            val shape = longArrayOf(m.toLong(), 3, Pixels.FACE.toLong(), Pixels.FACE.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(crops, start * n, m * n).slice(), shape).use { t ->
                rec.run(mapOf(recName to t)).use { r ->
                    val b = (r.get(0) as OnnxTensor).floatBuffer
                    val all = FloatArray(b.remaining()).also { b.get(it) }
                    for (i in 0 until m) embs += Vec.normalize(all.copyOfRange(i * 512, (i + 1) * 512))
                }
            }
        }
        return dets.zip(embs)
    }

    /** The 9 SCRFD outputs ordered scores, boxes, landmarks x strides 8, 16, 32 (found by shape, not name). */
    private fun headOutputs(r: OrtSession.Result): List<FloatArray> =
        r.map { (it.value as OnnxTensor) }
            .sortedWith(compareBy<OnnxTensor> { it.info.shape[1] }.thenByDescending { it.info.shape[0] })
            .map { t -> t.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } } }

    override fun close() {
        det.close(); rec.close()
    }

    companion object {
        const val REC_BATCH = 4

        private fun options() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(bigCores().coerceIn(1, 4))
            setInterOpNumThreads(1)
            addConfigEntry("session.use_ort_model_bytes_directly", "1")
            addConfigEntry("session.use_ort_model_bytes_for_initializers", "1")
        }

        private fun map(assets: AssetManager, path: String): ByteBuffer = assets.openFd(path).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        }

        /** Cores with the highest max frequency (4 Cortex-A78 on the Galaxy A54). */
        fun bigCores(): Int {
            val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
                runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
            }
            return if (freqs.isEmpty()) Runtime.getRuntime().availableProcessors() else freqs.count { it == freqs.max() }
        }
    }
}
