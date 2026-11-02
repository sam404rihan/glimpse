package app.glimpse.data

import android.content.ContentValues
import app.glimpse.faces.Vec
import app.glimpse.people.ClusterInput
import app.glimpse.people.Clusterer
import app.glimpse.people.FaceRow

data class Person(val id: Long, val name: String?, val coverFace: Long?, val count: Int)
data class Photo(val fingerprint: String, val volume: String, val mediaId: Long, val date: Long)
data class FaceInPhoto(val id: Long, val box: FloatArray, val person: Long?, val name: String?)
data class FaceRef(val photo: Photo, val box: FloatArray)
data class Stats(val photos: Int, val people: Int)

/** People: grouping, queries and the user's corrections (a port of people.py's People). */
class PeopleStore(helper: GlimpseDb) {
    companion object {
        const val MIN_PICTURES = 5
    }

    private val db = helper.writableDatabase
    private val lock = Any()

    // Faces whose photo is still on the phone (deleted ones are kept 30 days in case they come back).
    private val liveFaces = "f.fingerprint IN (SELECT fingerprint FROM face_scan WHERE orphaned_at IS NULL)"
    // Photos that can be shown right now (their volume is mounted).
    private val visible = "EXISTS (SELECT 1 FROM files fl JOIN volumes v ON v.name = fl.volume AND v.mounted = 1 WHERE fl.fingerprint = f.fingerprint)"

    fun recluster() = synchronized(lock) {
        db.tx {
            val faces = rawQuery("SELECT f.id, f.fingerprint, f.emb, f.person_id, f.score, f.px FROM faces f WHERE $liveFaces", null).use { c ->
                buildList {
                    while (c.moveToNext()) add(FaceRow(c.getLong(0), c.getString(1), Vec.fromHalfBytes(c.getBlob(2)),
                        if (c.isNull(3)) null else c.getLong(3), c.getFloat(4), c.getFloat(5)))
                }
            }
            fun ids(sql: String) = rawQuery(sql, null).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
            val rejects = rawQuery("SELECT face_id, person_id FROM face_rejects", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) }
            }
            val input = ClusterInput(faces, ids("SELECT id FROM people WHERE kept = 1"), ids("SELECT id FROM people WHERE kept = 0"), rejects)
            val result = Clusterer.cluster(input) { insert("people", null, ContentValues().apply { put("kept", 0) }) }
            val st = compileStatement("UPDATE faces SET person_id = ? WHERE id = ?")
            for ((face, person) in result.assign) {
                if (person == null) st.bindNull(1) else st.bindLong(1, person)
                st.bindLong(2, face)
                st.executeUpdateDelete()
            }
            execSQL("DELETE FROM people WHERE kept = 0 AND id NOT IN (SELECT DISTINCT person_id FROM faces WHERE person_id IS NOT NULL)")
            for ((p, face) in result.covers) execSQL("UPDATE people SET cover_face = ? WHERE id = ?", arrayOf(face, p))
        }
    }

    /** People to show: anyone named, and unnamed people once they're in [MIN_PICTURES] pictures (fewer is mostly noise). */
    fun list(): List<Person> = db.rawQuery(
        """SELECT p.id, p.name, p.cover_face, COUNT(DISTINCT f.fingerprint) AS n
           FROM people p JOIN faces f ON f.person_id = p.id
           WHERE $liveFaces AND $visible
           GROUP BY p.id HAVING n >= $MIN_PICTURES OR p.name IS NOT NULL
           ORDER BY (p.name IS NULL), n DESC, p.id""", null,
    ).use { c ->
        buildList { while (c.moveToNext()) add(Person(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2), c.getInt(3))) }
    }

    fun stats(): Stats {
        val photos = db.rawQuery(
            "SELECT COUNT(DISTINCT fl.fingerprint) FROM files fl JOIN volumes v ON v.name = fl.volume AND v.mounted = 1 WHERE fl.fingerprint != ''", null,
        ).use { it.moveToFirst(); it.getInt(0) }
        return Stats(photos, list().size)
    }

    /** Every visible photo, newest first (one file per distinct photo). */
    fun allPhotos(): List<Photo> {
        val out = LinkedHashMap<String, Photo>()
        db.rawQuery(
            """SELECT fl.fingerprint, fl.volume, fl.media_id, fl.date_taken, fl.date_modified FROM files fl
               JOIN volumes v ON v.name = fl.volume AND v.mounted = 1 WHERE fl.fingerprint != ''""", null,
        ).use { c ->
            while (c.moveToNext()) {
                val date = if (c.getLong(3) > 0) c.getLong(3) else c.getLong(4) * 1000
                out.putIfAbsent(c.getString(0), Photo(c.getString(0), c.getString(1), c.getLong(2), date))
            }
        }
        return out.values.sortedByDescending { it.date }
    }

    /**
     * Photos that contain every one of [people], newest first (one file per distinct photo).
     * [only]: and nobody else, i.e. every other clear face in the photo belongs to one of them.
     * Small or blurry faces in the background don't count, or hardly any photo would qualify.
     */
    fun photos(people: List<Long>, only: Boolean = false): List<Photo> {
        if (people.isEmpty()) return emptyList()
        val sets = people.map { p ->
            db.rawQuery("SELECT DISTINCT f.fingerprint FROM faces f WHERE f.person_id = ? AND $liveFaces", arrayOf(p.toString())).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
        }
        var fps = sets.reduce { a, b -> a intersect b }
        if (only) {
            val group = people.toSet()
            val crowded = HashSet<String>()
            fps.chunkedIn { inList, args ->
                db.rawQuery("SELECT fingerprint, person_id, score, px FROM faces WHERE fingerprint IN $inList", args).use { c ->
                    while (c.moveToNext()) {
                        val someone = if (c.isNull(1)) null else c.getLong(1)
                        val other = if (someone != null) someone !in group
                            else c.getFloat(2) >= Clusterer.GOOD_SCORE && c.getFloat(3) >= Clusterer.GOOD_PX
                        if (other) crowded += c.getString(0)
                    }
                }
            }
            fps = fps - crowded
        }
        val out = LinkedHashMap<String, Photo>()
        fps.chunkedIn { inList, args ->
            db.rawQuery(
                """SELECT fl.fingerprint, fl.volume, fl.media_id, fl.date_taken, fl.date_modified FROM files fl
                   JOIN volumes v ON v.name = fl.volume AND v.mounted = 1 WHERE fl.fingerprint IN $inList""", args,
            ).use { c ->
                while (c.moveToNext()) {
                    val date = if (c.getLong(3) > 0) c.getLong(3) else c.getLong(4) * 1000
                    out.putIfAbsent(c.getString(0), Photo(c.getString(0), c.getString(1), c.getLong(2), date))
                }
            }
        }
        return out.values.sortedByDescending { it.date }
    }

    fun facesIn(fingerprint: String): List<FaceInPhoto> = db.rawQuery(
        """SELECT f.id, f.x1, f.y1, f.x2, f.y2, f.person_id, p.name FROM faces f LEFT JOIN people p ON p.id = f.person_id
           WHERE f.fingerprint = ? AND (f.person_id IS NOT NULL OR f.score >= ?) ORDER BY f.x1""",
        arrayOf(fingerprint, Clusterer.SHOW_SCORE.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(FaceInPhoto(c.getLong(0), floatArrayOf(c.getFloat(1), c.getFloat(2), c.getFloat(3), c.getFloat(4)),
                if (c.isNull(5)) null else c.getLong(5), c.getString(6)))
        }
    }

    /** A face and a file it can be cropped from. */
    fun face(faceId: Long): FaceRef? = db.rawQuery(
        """SELECT f.x1, f.y1, f.x2, f.y2, fl.fingerprint, fl.volume, fl.media_id, fl.date_taken FROM faces f
           JOIN files fl ON fl.fingerprint = f.fingerprint JOIN volumes v ON v.name = fl.volume AND v.mounted = 1
           WHERE f.id = ? LIMIT 1""", arrayOf(faceId.toString()),
    ).use { c ->
        if (!c.moveToFirst()) null
        else FaceRef(Photo(c.getString(4), c.getString(5), c.getLong(6), c.getLong(7)),
            floatArrayOf(c.getFloat(0), c.getFloat(1), c.getFloat(2), c.getFloat(3)))
    }

    fun faceOf(fingerprint: String, person: Long): Long? =
        db.rawQuery("SELECT id FROM faces WHERE fingerprint = ? AND person_id = ? LIMIT 1", arrayOf(fingerprint, person.toString())).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }

    fun rename(person: Long, name: String?) = synchronized(lock) {
        db.execSQL("UPDATE people SET name = ?, kept = 1 WHERE id = ?", arrayOf(name?.trim()?.ifEmpty { null }, person))
    }

    /** What [merge] changed, so it can be undone. */
    class MergeUndo internal constructor(
        val src: Long, val srcName: String?, val srcKept: Int, val srcCover: Long?,
        val dst: Long, val dstName: String?, val dstKept: Int, val faces: List<Long>, val rejects: List<Long>,
    )

    private fun longs(sql: String, vararg args: Any): List<Long> =
        db.rawQuery(sql, args.map { it.toString() }.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    /** [src] is the same person as [dst]: all of src's faces move to dst. Returns how to undo it. */
    fun merge(src: Long, dst: Long): MergeUndo? = synchronized(lock) {
        if (src == dst) return@synchronized null
        fun row(id: Long) = db.rawQuery("SELECT name, kept, cover_face FROM people WHERE id = ?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) Triple(it.getString(0), it.getInt(1), if (it.isNull(2)) null else it.getLong(2)) else null
        }
        val s = row(src) ?: return@synchronized null
        val d = row(dst) ?: return@synchronized null
        val dstRejects = longs("SELECT face_id FROM face_rejects WHERE person_id = ?", dst).toSet()
        val undo = MergeUndo(src, s.first, s.second, s.third, dst, d.first, d.second,
            longs("SELECT id FROM faces WHERE person_id = ?", src),
            longs("SELECT face_id FROM face_rejects WHERE person_id = ?", src).filter { it !in dstRejects })
        db.tx {
            val srcName = rawQuery("SELECT name FROM people WHERE id = ?", arrayOf(src.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
            execSQL("UPDATE faces SET person_id = ? WHERE person_id = ?", arrayOf(dst, src))
            execSQL("UPDATE OR IGNORE face_rejects SET person_id = ? WHERE person_id = ?", arrayOf(dst, src))
            execSQL("UPDATE people SET kept = 1, name = COALESCE(name, ?) WHERE id = ?", arrayOf(srcName, dst))
            execSQL("DELETE FROM people WHERE id = ?", arrayOf(src))
        }
        undo
    }

    /** Puts [merge]'s two people back as they were (faces regrouped since then are left alone). */
    fun unmerge(u: MergeUndo) = synchronized(lock) {
        db.tx {
            execSQL("INSERT OR IGNORE INTO people(id, name, kept, cover_face) VALUES (?, ?, ?, ?)", arrayOf(u.src, u.srcName, u.srcKept, u.srcCover))
            u.faces.chunkedIn { inList, args ->
                execSQL("UPDATE faces SET person_id = ? WHERE person_id = ? AND id IN $inList", arrayOf<Any>(u.src, u.dst, *args))
            }
            u.rejects.chunkedIn { inList, args ->
                execSQL("UPDATE OR IGNORE face_rejects SET person_id = ? WHERE person_id = ? AND face_id IN $inList", arrayOf<Any>(u.src, u.dst, *args))
            }
            execSQL("UPDATE people SET name = ?, kept = ? WHERE id = ?", arrayOf(u.dstName, u.dstKept, u.dst))
        }
    }

    /**
     * Forgets [people] for good: their faces (identity vectors and boxes), their "Not them" records and their names.
     * The pictures themselves are never touched. Their photos count as analysed, so a later scan doesn't find these
     * faces again. Returns how many faces were deleted.
     */
    fun forget(people: List<Long>): Int = synchronized(lock) {
        if (people.isEmpty()) return@synchronized 0
        var n = 0
        db.tx {
            people.chunkedIn { inList, args ->
                execSQL("DELETE FROM face_rejects WHERE person_id IN $inList OR face_id IN (SELECT id FROM faces WHERE person_id IN $inList)",
                    arrayOf<Any>(*args, *args))
                n += compileStatement("DELETE FROM faces WHERE person_id IN $inList").apply { args.forEachIndexed { i, a -> bindString(i + 1, a) } }
                    .executeUpdateDelete()
                execSQL("DELETE FROM people WHERE id IN $inList", arrayOf<Any>(*args))
            }
        }
        n
    }

    /** "Not them": unlink the face and never put it back into that person. Returns that person. */
    fun reject(faceId: Long): Long? = synchronized(lock) {
        db.tx {
            val p = rawQuery("SELECT person_id FROM faces WHERE id = ?", arrayOf(faceId.toString())).use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            } ?: return@tx null
            execSQL("INSERT OR IGNORE INTO face_rejects(face_id, person_id) VALUES (?, ?)", arrayOf(faceId, p))
            execSQL("UPDATE faces SET person_id = NULL WHERE id = ?", arrayOf(faceId))
            execSQL("UPDATE people SET kept = 1 WHERE id = ?", arrayOf(p))
            p
        }
    }

    /** Undoes [reject]: the face belongs to [person] again. */
    fun unreject(faceId: Long, person: Long) = synchronized(lock) {
        db.tx {
            execSQL("DELETE FROM face_rejects WHERE face_id = ? AND person_id = ?", arrayOf(faceId, person))
            execSQL("UPDATE faces SET person_id = ? WHERE id = ? AND person_id IS NULL", arrayOf(person, faceId))
        }
    }

    /** Other people who might really be [person], most alike first ("Same person?"). */
    fun similar(person: Long, limit: Int = 6): List<Person> {
        val people = list()
        val cents = people.associate { p ->
            val embs = db.rawQuery("SELECT f.emb FROM faces f WHERE f.person_id = ? AND $liveFaces", arrayOf(p.id.toString())).use { c ->
                buildList { while (c.moveToNext()) add(Vec.fromHalfBytes(c.getBlob(0))) }
            }
            val sum = FloatArray(embs.first().size)
            for (e in embs) for (k in sum.indices) sum[k] += e[k]
            p.id to Vec.normalize(sum)
        }
        val me = cents[person] ?: return emptyList()
        return people.filter { it.id != person }
            .map { it to Vec.dot(me, cents.getValue(it.id)) }
            .filter { it.second >= Clusterer.SUGGEST_SIM }
            .sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
