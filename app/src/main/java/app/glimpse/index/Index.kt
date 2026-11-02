package app.glimpse.index

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** One image MediaStore knows about. [volume] + [mediaId] say where it is; its content is fingerprinted. */
data class MediaItem(
    val volume: String,
    val mediaId: Long,
    val relPath: String,
    val name: String,
    val size: Long,
    val dateModified: Long, // seconds
    val dateTaken: Long, // milliseconds, 0 if unknown
)

/** Where photos come from: MediaStore on the phone, a fake in tests. */
interface MediaSource {
    fun mountedVolumes(): List<String>
    fun version(volume: String): String
    fun generation(volume: String): Long
    /** Items changed since [sinceGeneration], or every item when it is null. */
    fun items(volume: String, sinceGeneration: Long?): List<MediaItem>
    fun itemsById(volume: String, ids: Collection<Long>): List<MediaItem>
    fun ids(volume: String): Set<Long>
    /** Content fingerprint (see [Fingerprint]), or null if the file can't be read right now. */
    fun fingerprint(item: MediaItem): String?
}

class FoundFace(val box: FloatArray, val score: Float, val px: Float, val emb: FloatArray) // box as fractions

sealed interface Analysis {
    class Faces(val width: Int, val height: Int, val faces: List<FoundFace>) : Analysis
    object Failed : Analysis
}

interface FaceAnalyzer : AutoCloseable {
    fun analyze(items: List<MediaItem>): List<Analysis>
}

data class VolumeState(val version: String, val generation: Long, val mounted: Boolean)

data class StoredFile(
    val volume: String, val mediaId: Long, val relPath: String, val name: String,
    val size: Long, val dateModified: Long, val dateTaken: Long, val fingerprint: String,
)

class Pending(val fingerprint: String, val item: MediaItem)

/** The index: where photos are (`files`) and what's in them (`face_scan`/`faces`, keyed by content). */
interface IndexStore {
    fun volume(name: String): VolumeState?
    fun saveVolume(name: String, state: VolumeState)
    fun volumes(): List<String>
    fun setMounted(name: String, mounted: Boolean)
    fun file(volume: String, mediaId: Long): StoredFile?
    fun fileIds(volume: String): Set<Long>
    fun putFile(item: MediaItem, fingerprint: String)
    fun deleteFiles(volume: String, mediaIds: Collection<Long>)
    /** Fingerprints on mounted volumes that the current model hasn't analysed, one readable file each. */
    fun pending(modelVersion: Int, limit: Int): List<Pending>
    fun pendingCount(modelVersion: Int): Int
    /** Stores results atomically: either the whole batch is saved or none of it. */
    fun saveAnalyses(modelVersion: Int, results: List<Pair<String, Analysis>>)
    /** Hides face data whose photo is gone, brings it back if the photo returns, and deletes data gone
     *  since before [deleteBefore]. True if the set of visible faces changed. */
    fun sweep(modelVersion: Int, now: Long, deleteBefore: Long): Boolean
}

/** SHA-256 of (size, first 64 KB, last 64 KB): cheap, and the same wherever the file is moved or copied. */
object Fingerprint {
    private const val CHUNK = 65536

    fun of(ch: FileChannel): String {
        val md = MessageDigest.getInstance("SHA-256")
        val len = ch.size()
        md.update(ByteBuffer.allocate(8).putLong(len).array())
        md.update(read(ch, 0, minOf(len, CHUNK.toLong()).toInt()))
        val tail = maxOf(CHUNK.toLong(), len - CHUNK)
        if (tail < len) md.update(read(ch, tail, (len - tail).toInt()))
        return md.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    private fun read(ch: FileChannel, pos: Long, n: Int): ByteArray {
        val buf = ByteBuffer.allocate(n)
        var p = pos
        while (buf.hasRemaining()) { val r = ch.read(buf, p); if (r < 0) break; p += r }
        return buf.array().copyOf(buf.position())
    }
}

/**
 * Brings the index up to date with MediaStore and analyses only content it has never seen.
 * A photo that is moved, renamed, copied, re-listed under a new id or on an SD card that comes back
 * is matched by fingerprint, so the models never run on it again.
 */
class Scanner(
    private val source: MediaSource,
    private val store: IndexStore,
    private val modelVersion: Int,
    private val analyzer: () -> FaceAnalyzer, // only loaded when there is something to analyse
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Phase { LISTING, ANALYSING }
    data class Progress(val phase: Phase, val done: Int, val total: Int)
    data class Result(val analysed: Int, val failed: Int, val removed: Int, val stopped: Boolean, val facesChanged: Boolean)

    fun scan(stop: () -> Boolean = { false }, onProgress: (Progress) -> Unit = {}): Result {
        var removed = 0
        val mounted = runCatching { source.mountedVolumes() }.getOrDefault(emptyList()).toSet()
        for (v in store.volumes()) if (v !in mounted) store.setMounted(v, false)
        for (v in mounted) {
            if (stop()) return Result(0, 0, removed, true, removed > 0)
            onProgress(Progress(Phase.LISTING, 0, 0))
            // A volume that disappears mid-scan (SD card pulled) is skipped; its index is kept.
            removed += runCatching { list(v, stop) }.getOrDefault(0)
        }

        var analysed = 0
        var failed = 0
        var faces = false
        val total = store.pendingCount(modelVersion)
        var az: FaceAnalyzer? = null
        try {
            while (!stop()) {
                val batch = store.pending(modelVersion, BATCH)
                if (batch.isEmpty()) break
                val a = az ?: analyzer().also { az = it }
                val results = a.analyze(batch.map { it.item })
                store.saveAnalyses(modelVersion, batch.map { it.fingerprint }.zip(results))
                analysed += batch.size
                failed += results.count { it is Analysis.Failed }
                if (results.any { it is Analysis.Faces && it.faces.isNotEmpty() }) faces = true
                onProgress(Progress(Phase.ANALYSING, analysed, total))
            }
        } finally {
            az?.close()
        }
        val now = clock()
        if (store.sweep(modelVersion, now, now - KEEP_ORPHANS_MS)) faces = true
        return Result(analysed, failed, removed, stop(), faces)
    }

    /** Updates `files` for one volume; returns how many files went away. */
    private fun list(v: String, stop: () -> Boolean): Int {
        val generation = source.generation(v) // read first: changes made during the scan are seen next time
        val version = source.version(v)
        val saved = store.volume(v)
        val since = if (saved != null && saved.version == version) saved.generation else null
        val ids = source.ids(v)
        val known = store.fileIds(v)
        val changed = source.items(v, since).associateByTo(LinkedHashMap()) { it.mediaId }
        // Photos that became visible without changing (wider permission, restored from trash) aren't in the
        // generation query; comparing ids finds them.
        val unseen = ids - known - changed.keys
        if (unseen.isNotEmpty()) source.itemsById(v, unseen).forEach { changed[it.mediaId] = it }
        for (item in changed.values) {
            if (stop()) return 0 // volume state isn't saved, so the next scan lists again
            update(item)
        }
        val gone = known - ids
        store.deleteFiles(v, gone)
        store.saveVolume(v, VolumeState(version, generation, true))
        return gone.size
    }

    private fun update(item: MediaItem) {
        val old = store.file(item.volume, item.mediaId)
        if (skip(item)) {
            if (old == null || old.fingerprint != SKIPPED || old.relPath != item.relPath) store.putFile(item, SKIPPED)
            return
        }
        if (old != null && old.fingerprint != SKIPPED && old.size == item.size && old.dateModified == item.dateModified) {
            if (old.relPath != item.relPath || old.name != item.name || old.dateTaken != item.dateTaken) {
                store.putFile(item, old.fingerprint) // moved or renamed: same content
            }
            return
        }
        val fp = source.fingerprint(item) ?: return // unreadable right now: retried next scan
        store.putFile(item, fp)
    }

    companion object {
        const val SKIPPED = "" // stickers and tiny files: remembered, never analysed
        const val BATCH = 16
        const val MIN_BYTES = 1024L
        const val KEEP_ORPHANS_MS = 30L * 24 * 3600 * 1000 // Samsung's trash keeps photos 30 days

        /** Chat stickers (WhatsApp/Telegram "Stickers" folders) aren't photos; nor are tiny files. */
        fun skip(item: MediaItem): Boolean =
            item.size < MIN_BYTES || item.relPath.trimEnd('/').substringAfterLast('/').contains("sticker", ignoreCase = true)
    }
}
