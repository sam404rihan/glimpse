package app.glimpse

import app.glimpse.index.Analysis
import app.glimpse.index.FaceAnalyzer
import app.glimpse.index.FoundFace
import app.glimpse.index.IndexStore
import app.glimpse.index.MediaItem
import app.glimpse.index.MediaSource
import app.glimpse.index.Pending
import app.glimpse.index.Scanner
import app.glimpse.index.StoredFile
import app.glimpse.index.VolumeState

/** A MediaStore stand-in: volumes with items, generations and file contents. */
class FakeSource : MediaSource {
    class Vol(var version: String = "v1", var generation: Long = 0, var mounted: Boolean = true) {
        val items = LinkedHashMap<Long, Pair<MediaItem, Long>>() // id -> item, generation_modified
    }
    val vols = LinkedHashMap<String, Vol>()
    val contents = HashMap<Pair<String, Long>, String>() // what's in each file
    var hidden = setOf<Long>() // ids invisible to the app (partial access, trash)
    var failVolume: String? = null
    private var nextId = 1L

    fun vol(v: String) = vols.getOrPut(v) { Vol() }

    fun add(v: String, path: String, content: String, size: Long = 5000): MediaItem {
        val vol = vol(v)
        val item = MediaItem(v, nextId++, path.substringBeforeLast('/') + "/", path.substringAfterLast('/'), size, 100, 0)
        vol.items[item.mediaId] = item to ++vol.generation
        contents[v to item.mediaId] = content
        return item
    }

    fun move(item: MediaItem, newPath: String): MediaItem {
        val vol = vol(item.volume)
        val moved = item.copy(relPath = newPath.substringBeforeLast('/') + "/", name = newPath.substringAfterLast('/'))
        vol.items[item.mediaId] = moved to ++vol.generation
        return moved
    }

    fun edit(item: MediaItem, content: String) {
        val vol = vol(item.volume)
        val e = item.copy(dateModified = item.dateModified + 1, size = item.size + 1)
        vol.items[item.mediaId] = e to ++vol.generation
        contents[item.volume to item.mediaId] = content
    }

    fun delete(item: MediaItem) { vol(item.volume).items.remove(item.mediaId); contents.remove(item.volume to item.mediaId) }

    private fun check(v: String) { if (v == failVolume) throw IllegalStateException("volume $v vanished") }
    override fun mountedVolumes() = vols.filter { it.value.mounted }.keys.toList()
    override fun version(volume: String) = vol(volume).version
    override fun generation(volume: String) = vol(volume).generation
    override fun items(volume: String, sinceGeneration: Long?): List<MediaItem> {
        check(volume)
        return vol(volume).items.values.filter { it.first.mediaId !in hidden && (sinceGeneration == null || it.second > sinceGeneration) }.map { it.first }
    }
    override fun itemsById(volume: String, ids: Collection<Long>) = items(volume, null).filter { it.mediaId in ids }
    override fun ids(volume: String) = items(volume, null).map { it.mediaId }.toSet()
    override fun fingerprint(item: MediaItem) = contents[item.volume to item.mediaId]?.let { "fp-$it" }
}

/** Counts how often the models would run; content "faceN" yields one face with a vector seeded by N. */
class CountingAnalyzer : FaceAnalyzer {
    var calls = 0
    var analysed = mutableListOf<MediaItem>()
    override fun analyze(items: List<MediaItem>): List<Analysis> {
        calls++
        analysed += items
        return items.map { Analysis.Faces(100, 100, listOf(FoundFace(floatArrayOf(.1f, .1f, .5f, .5f), .9f, 60f, FloatArray(512) { 1f / 22.6f }))) }
    }
    override fun close() = Unit
}

/** IndexStore in memory, with the same semantics as SqliteIndexStore. */
class MemoryIndexStore : IndexStore {
    val volumesMap = LinkedHashMap<String, VolumeState>()
    val files = LinkedHashMap<Pair<String, Long>, StoredFile>()
    class Scan(val model: Int, val faces: Int, var orphanedAt: Long?)
    val scans = HashMap<String, Scan>()

    override fun volume(name: String) = volumesMap[name]
    override fun saveVolume(name: String, state: VolumeState) { volumesMap[name] = state }
    override fun volumes() = volumesMap.keys.toList()
    override fun setMounted(name: String, mounted: Boolean) { volumesMap[name]?.let { volumesMap[name] = it.copy(mounted = mounted) } }
    override fun file(volume: String, mediaId: Long) = files[volume to mediaId]
    override fun fileIds(volume: String) = files.keys.filter { it.first == volume }.map { it.second }.toSet()
    override fun putFile(item: MediaItem, fingerprint: String) {
        files[item.volume to item.mediaId] = StoredFile(item.volume, item.mediaId, item.relPath, item.name, item.size, item.dateModified, item.dateTaken, fingerprint)
    }
    override fun deleteFiles(volume: String, mediaIds: Collection<Long>) { mediaIds.forEach { files.remove(volume to it) } }
    override fun pending(modelVersion: Int, limit: Int): List<Pending> = files.values
        .filter { it.fingerprint != Scanner.SKIPPED && volumesMap[it.volume]?.mounted == true && scans[it.fingerprint]?.model != modelVersion }
        .distinctBy { it.fingerprint }.take(limit)
        .map { Pending(it.fingerprint, MediaItem(it.volume, it.mediaId, it.relPath, it.name, it.size, it.dateModified, it.dateTaken)) }
    override fun pendingCount(modelVersion: Int) = pending(modelVersion, Int.MAX_VALUE).size
    override fun saveAnalyses(modelVersion: Int, results: List<Pair<String, Analysis>>) {
        for ((fp, a) in results) scans[fp] = Scan(modelVersion, (a as? Analysis.Faces)?.faces?.size ?: 0, null)
    }
    override fun sweep(modelVersion: Int, now: Long, deleteBefore: Long): Boolean {
        val live = files.values.map { it.fingerprint }.toSet()
        var changed = false
        for ((fp, s) in scans) {
            if (s.orphanedAt == null && fp !in live) { s.orphanedAt = now; if (s.faces > 0) changed = true }
            else if (s.orphanedAt != null && fp in live) { s.orphanedAt = null; if (s.faces > 0) changed = true }
        }
        scans.entries.removeAll { (_, s) -> (s.orphanedAt ?: Long.MAX_VALUE) < deleteBefore || s.model != modelVersion }
        return changed
    }
}
