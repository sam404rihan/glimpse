package app.glimpse.faces

/** A face in some image's pixel space: box x1,y1,x2,y2, detector score, 5 landmarks as x,y pairs. */
class Detection(val box: FloatArray, val score: Float, val kps: FloatArray) {
    val size: Float get() = minOf(box[2] - box[0], box[3] - box[1])

    fun scaled(f: Float) = Detection(FloatArray(4) { box[it] * f }, score, FloatArray(10) { kps[it] * f })
}

/** InsightFace SCRFD (det_2.5g from buffalo_m; same heads as det_10g) post-processing, as faces.FaceModel._decode and faces._nms on the desktop. */
object Scrfd {
    const val SIZE = 640
    const val DET_THRESH = 0.5f
    const val NMS_THRESH = 0.4f
    const val MIN_FACE_PX = 20f // smaller faces give unreliable identities
    private val STRIDES = intArrayOf(8, 16, 32)

    /** [outs] are the 9 head outputs: scores, box distances, landmark offsets for strides 8, 16, 32. */
    fun decode(outs: List<FloatArray>): List<Detection> {
        val found = ArrayList<Detection>()
        for ((i, stride) in STRIDES.withIndex()) {
            val scores = outs[i]
            val boxes = outs[i + 3]
            val kps = outs[i + 6]
            val n = SIZE / stride
            for (a in scores.indices) {
                val s = scores[a]
                if (s < DET_THRESH) continue
                val cell = a / 2 // two anchors per cell
                val cx = (cell % n) * stride.toFloat()
                val cy = (cell / n) * stride.toFloat()
                val b = floatArrayOf(
                    cx - boxes[a * 4] * stride, cy - boxes[a * 4 + 1] * stride,
                    cx + boxes[a * 4 + 2] * stride, cy + boxes[a * 4 + 3] * stride,
                )
                val k = FloatArray(10) { j -> (if (j % 2 == 0) cx else cy) + kps[a * 10 + j] * stride }
                found += Detection(b, s, k)
            }
        }
        return nms(found)
    }

    fun nms(dets: List<Detection>): List<Detection> {
        val order = dets.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Detection>()
        while (order.isNotEmpty()) {
            val best = order.removeAt(0)
            keep += best
            order.removeAll { iou(best.box, it.box) > NMS_THRESH }
        }
        return keep
    }

    fun iou(a: FloatArray, b: FloatArray): Float {
        val iw = maxOf(0f, minOf(a[2], b[2]) - maxOf(a[0], b[0]))
        val ih = maxOf(0f, minOf(a[3], b[3]) - maxOf(a[1], b[1]))
        val inter = iw * ih
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union > 0f) inter / union else 0f
    }
}
