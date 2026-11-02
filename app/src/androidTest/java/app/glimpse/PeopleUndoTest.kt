package app.glimpse

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.glimpse.data.PeopleStore
import app.glimpse.data.GlimpseDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The snackbar's Undo puts people back exactly as they were. */
@RunWith(AndroidJUnit4::class)
class PeopleUndoTest {
    private val helper = GlimpseDb(InstrumentationRegistry.getInstrumentation().targetContext, null)
    private val db: SQLiteDatabase = helper.writableDatabase
    private val people = PeopleStore(helper)

    private fun person(id: Long, name: String?, kept: Int, cover: Long?) =
        db.execSQL("INSERT INTO people(id, name, kept, cover_face) VALUES (?, ?, ?, ?)", arrayOf(id, name, kept, cover))
    private fun face(id: Long, person: Long?) =
        db.execSQL("INSERT INTO faces(id, fingerprint, emb, person_id) VALUES (?, ?, ?, ?)", arrayOf(id, "fp$id", ByteArray(4), person))
    private fun reject(face: Long, person: Long) = db.execSQL("INSERT INTO face_rejects VALUES (?, ?)", arrayOf(face, person))

    private fun snapshot() = listOf(
        "SELECT id, name, kept, cover_face FROM people ORDER BY id",
        "SELECT id, person_id FROM faces ORDER BY id",
        "SELECT face_id, person_id FROM face_rejects ORDER BY face_id, person_id",
    ).joinToString("\n") { sql ->
        db.rawQuery(sql, null).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString(",") { c.getString(it) ?: "null" }) }.joinToString(";")
        }
    }

    @Test fun mergeThenUndoRestoresBothPeople() {
        person(1, null, 0, 10); person(2, "Alex", 1, 20)
        face(10, 1); face(11, 1); face(20, 2); face(21, 2); face(30, null)
        reject(30, 1) // moves to Alex
        reject(31, 1); reject(31, 2) // already Alex's: stays with person 1
        val before = snapshot()

        val undo = people.merge(1, 2)!!
        assertEquals("0", db.rawQuery("SELECT COUNT(*) FROM faces WHERE person_id = 1", null).use { it.moveToFirst(); it.getString(0) })
        people.unmerge(undo)
        assertEquals(before, snapshot())
    }

    @Test fun mergeNamesTheUnnamedSideAndUndoTakesItBack() {
        person(1, "Sam", 1, 10); person(2, null, 0, 20)
        face(10, 1); face(20, 2)
        val before = snapshot()
        val undo = people.merge(1, 2)!!
        assertEquals("Sam", db.rawQuery("SELECT name FROM people WHERE id = 2", null).use { it.moveToFirst(); it.getString(0) })
        people.unmerge(undo)
        assertEquals(before, snapshot())
    }

    @Test fun mergeWithSelfOrMissingIsANoOp() {
        person(1, null, 0, null)
        assertNull(people.merge(1, 1))
        assertNull(people.merge(1, 99))
    }

    @Test fun notThemThenUndo() {
        person(1, "Alex", 0, 10)
        face(10, 1); face(11, 1)
        val before = snapshot()
        assertEquals(1L, people.reject(11))
        people.unreject(11, 1)
        // kept becomes 1 on reject (the person was corrected by hand); everything else is back.
        assertEquals(before.replace("1,Alex,0,10", "1,Alex,1,10"), snapshot())
    }
}
