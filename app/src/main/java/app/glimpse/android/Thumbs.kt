package app.glimpse.android

import android.content.ContentResolver
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.glimpse.data.FaceRef
import app.glimpse.data.Photo
import kotlin.math.roundToInt

/** Thumbnails come from Android's own thumbnail cache and live only in memory: Glimpse writes no image files. */
class Thumbs(private val cr: ContentResolver) {
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(key: String): ImageBitmap? = cache.get(key)
    fun cachedPhoto(p: Photo, px: Int = 320): ImageBitmap? = cache.get("p${p.volume}/${p.mediaId}/$px")
    fun cachedFace(faceId: Long): ImageBitmap? = cache.get("f$faceId")

    fun photo(p: Photo, px: Int = 320): ImageBitmap? = cache.get("p${p.volume}/${p.mediaId}/$px") ?: runCatching {
        cr.loadThumbnail(uriOf(p.volume, p.mediaId), Size(px, px), null).asImageBitmap()
    }.getOrNull()?.also { cache.put("p${p.volume}/${p.mediaId}/$px", it) }

    /** A square crop around the face with a little hair and chin, like the desktop's face thumbnails. */
    fun face(faceId: Long, ref: FaceRef, px: Int = 192): ImageBitmap? = cache.get("f$faceId") ?: runCatching {
        val src = cr.loadThumbnail(uriOf(ref.photo.volume, ref.photo.mediaId), Size(768, 768), null)
        val (x1, y1, x2, y2) = ref.box.toList()
        val w = src.width; val h = src.height
        val cx = (x1 + x2) / 2 * w; val cy = (y1 + y2) / 2 * h
        val half = maxOf((x2 - x1) * w, (y2 - y1) * h) * 0.8f
        val l = (cx - half).roundToInt().coerceIn(0, w - 1); val t = (cy - half).roundToInt().coerceIn(0, h - 1)
        val r = (cx + half).roundToInt().coerceIn(l + 1, w); val b = (cy + half).roundToInt().coerceIn(t + 1, h)
        val crop = Bitmap.createBitmap(src, l, t, r - l, b - t)
        Bitmap.createScaledBitmap(crop, px, px, true).asImageBitmap()
    }.getOrNull()?.also { cache.put("f$faceId", it) }
}
