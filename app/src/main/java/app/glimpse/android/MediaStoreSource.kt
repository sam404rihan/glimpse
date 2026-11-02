package app.glimpse.android

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Images.Media
import app.glimpse.index.Fingerprint
import app.glimpse.index.MediaItem
import app.glimpse.index.MediaSource
import java.io.FileInputStream

fun MediaItem.uri(): Uri = Media.getContentUri(volume, mediaId)
fun uriOf(volume: String, mediaId: Long): Uri = Media.getContentUri(volume, mediaId)

/** Every image Android's gallery knows about, on internal storage and SD cards. Read-only.
 *  [onlyPath] limits it to one folder (tests). */
class MediaStoreSource(context: Context, private val onlyPath: String? = null) : MediaSource {
    private val ctx = context.applicationContext
    private val cr = ctx.contentResolver
    private val columns = arrayOf(
        Media._ID, Media.RELATIVE_PATH, Media.DISPLAY_NAME, Media.SIZE, Media.DATE_MODIFIED, Media.DATE_TAKEN,
    )

    override fun mountedVolumes(): List<String> = MediaStore.getExternalVolumeNames(ctx).toList()
    override fun version(volume: String): String = MediaStore.getVersion(ctx, volume)
    override fun generation(volume: String): Long = MediaStore.getGeneration(ctx, volume)

    override fun items(volume: String, sinceGeneration: Long?): List<MediaItem> =
        if (sinceGeneration == null) query(volume, null, null)
        else query(volume, "${Media.GENERATION_MODIFIED} > ?", arrayOf(sinceGeneration.toString()))

    override fun itemsById(volume: String, ids: Collection<Long>): List<MediaItem> =
        ids.chunked(500).flatMap { c -> query(volume, "${Media._ID} IN (${c.joinToString(",")})", null) }

    override fun ids(volume: String): Set<Long> {
        val (sel, args) = where(null, null)
        return cr.query(Media.getContentUri(volume), arrayOf(Media._ID), sel, args, null)?.use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        } ?: emptySet()
    }

    override fun fingerprint(item: MediaItem): String? = runCatching {
        cr.openFileDescriptor(item.uri(), "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { Fingerprint.of(it) }
        }
    }.getOrNull()

    private fun where(sel: String?, args: Array<String>?): Pair<String?, Array<String>?> {
        if (onlyPath == null) return sel to args
        val s = "${Media.RELATIVE_PATH} LIKE ?" + (sel?.let { " AND ($it)" } ?: "")
        return s to (arrayOf("$onlyPath%") + (args ?: emptyArray()))
    }

    private fun query(volume: String, sel: String?, args: Array<String>?): List<MediaItem> {
        val (s, a) = where(sel, args)
        return cr.query(Media.getContentUri(volume), columns, s, a, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(MediaItem(volume, c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "",
                    c.getLong(3), c.getLong(4), c.getLong(5)))
            }
        } ?: emptyList()
    }
}
