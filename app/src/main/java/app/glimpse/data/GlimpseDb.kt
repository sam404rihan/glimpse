package app.glimpse.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.glimpse.faces.Vec
import app.glimpse.index.Analysis
import app.glimpse.index.IndexStore
import app.glimpse.index.MediaItem
import app.glimpse.index.Pending
import app.glimpse.index.StoredFile
import app.glimpse.index.VolumeState

/** The whole index in one SQLite file in app-private storage ([name] null = in memory, for tests). */
class GlimpseDb(context: Context, name: String? = NAME) : SQLiteOpenHelper(context, name, null, 1) {
    companion object {
        const val NAME = "glimpse.db"
    }

    init { setWriteAheadLoggingEnabled(true) } // the UI reads while a scan writes

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE volumes(name TEXT PRIMARY KEY, ms_version TEXT, last_generation INTEGER, mounted INTEGER)")
        db.execSQL(
            """CREATE TABLE files(id INTEGER PRIMARY KEY, volume TEXT NOT NULL, media_id INTEGER NOT NULL,
               rel_path TEXT, name TEXT, size INTEGER, date_modified INTEGER, date_taken INTEGER,
               fingerprint TEXT NOT NULL, UNIQUE(volume, media_id))""",
        )
        db.execSQL("CREATE INDEX files_fp ON files(fingerprint)")
        db.execSQL(
            """CREATE TABLE face_scan(fingerprint TEXT NOT NULL, model_ver INTEGER NOT NULL, face_count INTEGER,
               failed INTEGER, width INTEGER, height INTEGER, orphaned_at INTEGER, PRIMARY KEY(fingerprint, model_ver))""",
        )
        db.execSQL(
            """CREATE TABLE faces(id INTEGER PRIMARY KEY, fingerprint TEXT NOT NULL, x1 REAL, y1 REAL, x2 REAL, y2 REAL,
               score REAL, px REAL, emb BLOB NOT NULL, person_id INTEGER)""",
        )
        db.execSQL("CREATE INDEX faces_fp ON faces(fingerprint)")
        db.execSQL("CREATE INDEX faces_person ON faces(person_id)")
        db.execSQL("CREATE TABLE people(id INTEGER PRIMARY KEY, name TEXT, kept INTEGER DEFAULT 0, cover_face INTEGER)")
        db.execSQL("CREATE TABLE face_rejects(face_id INTEGER, person_id INTEGER, PRIMARY KEY(face_id, person_id))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

inline fun <T> SQLiteDatabase.tx(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        val r = block()
        setTransactionSuccessful()
        return r
    } finally {
        endTransaction()
    }
}

/** SQL "IN (?, ?, ...)" for up to 500 values at a time. */
internal fun <T> Collection<T>.chunkedIn(block: (String, Array<String>) -> Unit) {
    for (c in chunked(500)) block(c.joinToString(",", "(", ")") { "?" }, c.map { it.toString() }.toTypedArray())
}

class SqliteIndexStore(helper: GlimpseDb) : IndexStore {
    private val db = helper.writableDatabase

    override fun volume(name: String): VolumeState? =
        db.rawQuery("SELECT ms_version, last_generation, mounted FROM volumes WHERE name = ?", arrayOf(name)).use {
            if (it.moveToFirst()) VolumeState(it.getString(0), it.getLong(1), it.getInt(2) == 1) else null
        }

    override fun saveVolume(name: String, state: VolumeState) {
        db.insertWithOnConflict("volumes", null, ContentValues().apply {
            put("name", name); put("ms_version", state.version); put("last_generation", state.generation)
            put("mounted", if (state.mounted) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun volumes(): List<String> =
        db.rawQuery("SELECT name FROM volumes", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    override fun setMounted(name: String, mounted: Boolean) {
        db.execSQL("UPDATE volumes SET mounted = ? WHERE name = ?", arrayOf(if (mounted) 1 else 0, name))
    }

    override fun file(volume: String, mediaId: Long): StoredFile? = db.rawQuery(
        "SELECT rel_path, name, size, date_modified, date_taken, fingerprint FROM files WHERE volume = ? AND media_id = ?",
        arrayOf(volume, mediaId.toString()),
    ).use {
        if (!it.moveToFirst()) null
        else StoredFile(volume, mediaId, it.getString(0), it.getString(1), it.getLong(2), it.getLong(3), it.getLong(4), it.getString(5))
    }

    override fun fileIds(volume: String): Set<Long> =
        db.rawQuery("SELECT media_id FROM files WHERE volume = ?", arrayOf(volume)).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }

    override fun putFile(item: MediaItem, fingerprint: String) {
        db.execSQL(
            """INSERT INTO files(volume, media_id, rel_path, name, size, date_modified, date_taken, fingerprint)
               VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(volume, media_id) DO UPDATE SET rel_path = excluded.rel_path,
               name = excluded.name, size = excluded.size, date_modified = excluded.date_modified,
               date_taken = excluded.date_taken, fingerprint = excluded.fingerprint""",
            arrayOf(item.volume, item.mediaId, item.relPath, item.name, item.size, item.dateModified, item.dateTaken, fingerprint),
        )
    }

    override fun deleteFiles(volume: String, mediaIds: Collection<Long>) {
        db.tx { mediaIds.chunkedIn { inList, args -> execSQL("DELETE FROM files WHERE volume = ? AND media_id IN $inList", arrayOf(volume, *args)) } }
    }

    private val pendingSql = """FROM files f JOIN volumes v ON v.name = f.volume AND v.mounted = 1
        WHERE f.fingerprint != '' AND NOT EXISTS
        (SELECT 1 FROM face_scan s WHERE s.fingerprint = f.fingerprint AND s.model_ver = ?)"""

    override fun pending(modelVersion: Int, limit: Int): List<Pending> = db.rawQuery(
        """SELECT f.fingerprint, f.volume, f.media_id, f.rel_path, f.name, f.size, f.date_modified, f.date_taken
           $pendingSql GROUP BY f.fingerprint LIMIT $limit""",
        arrayOf(modelVersion.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Pending(c.getString(0),
                MediaItem(c.getString(1), c.getLong(2), c.getString(3), c.getString(4), c.getLong(5), c.getLong(6), c.getLong(7))))
        }
    }

    override fun pendingCount(modelVersion: Int): Int =
        db.rawQuery("SELECT COUNT(DISTINCT f.fingerprint) $pendingSql", arrayOf(modelVersion.toString())).use {
            it.moveToFirst(); it.getInt(0)
        }

    override fun saveAnalyses(modelVersion: Int, results: List<Pair<String, Analysis>>) = db.tx {
        for ((fp, a) in results) {
            delete("faces", "fingerprint = ?", arrayOf(fp))
            val faces = (a as? Analysis.Faces)?.faces.orEmpty()
            for (f in faces) insert("faces", null, ContentValues().apply {
                put("fingerprint", fp); put("x1", f.box[0]); put("y1", f.box[1]); put("x2", f.box[2]); put("y2", f.box[3])
                put("score", f.score); put("px", f.px); put("emb", Vec.toHalfBytes(f.emb))
            })
            insertWithOnConflict("face_scan", null, ContentValues().apply {
                put("fingerprint", fp); put("model_ver", modelVersion); put("face_count", faces.size)
                put("failed", if (a is Analysis.Failed) 1 else 0)
                put("width", (a as? Analysis.Faces)?.width ?: 0); put("height", (a as? Analysis.Faces)?.height ?: 0)
                putNull("orphaned_at")
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun sweep(modelVersion: Int, now: Long, deleteBefore: Long): Boolean = db.tx {
        fun count(sql: String) = rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }
        val lost = count("SELECT COUNT(*) FROM face_scan WHERE orphaned_at IS NULL AND face_count > 0 AND fingerprint NOT IN (SELECT fingerprint FROM files)")
        val back = count("SELECT COUNT(*) FROM face_scan WHERE orphaned_at IS NOT NULL AND face_count > 0 AND fingerprint IN (SELECT fingerprint FROM files)")
        execSQL("UPDATE face_scan SET orphaned_at = ? WHERE orphaned_at IS NULL AND fingerprint NOT IN (SELECT fingerprint FROM files)", arrayOf(now))
        execSQL("UPDATE face_scan SET orphaned_at = NULL WHERE orphaned_at IS NOT NULL AND fingerprint IN (SELECT fingerprint FROM files)")
        execSQL("DELETE FROM face_scan WHERE orphaned_at < ? OR model_ver != ?", arrayOf(deleteBefore, modelVersion))
        execSQL("DELETE FROM faces WHERE fingerprint NOT IN (SELECT fingerprint FROM face_scan)")
        execSQL("DELETE FROM face_rejects WHERE face_id NOT IN (SELECT id FROM faces)")
        lost > 0 || back > 0
    }
}
