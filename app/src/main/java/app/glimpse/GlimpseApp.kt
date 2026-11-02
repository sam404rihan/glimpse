package app.glimpse

import android.app.Application
import java.io.File
import app.glimpse.data.PeopleStore
import app.glimpse.data.GlimpseDb
import app.glimpse.data.SqliteIndexStore
import app.glimpse.scan.ScanController
import app.glimpse.scan.ScanWorker

class GlimpseApp : Application() {
    val db by lazy { GlimpseDb(this) }
    val store by lazy { SqliteIndexStore(db) }
    val people by lazy { PeopleStore(db) }
    val scans by lazy { ScanController(this) }

    override fun onCreate() {
        super.onCreate()
        adoptOldDatabase()
        ScanWorker.schedule(this)
    }

    /**
     * Builds from before the rename kept the index in seek.db. Move it, with its write-ahead log, before anything
     * opens the database, so an update keeps every analysed photo, name and merge.
     */
    private fun adoptOldDatabase() {
        val old = getDatabasePath("seek.db")
        val new = getDatabasePath(GlimpseDb.NAME)
        if (!old.exists() || new.exists()) return
        for (suffix in listOf("-wal", "-shm", "-journal", "")) { // the main file last: never a database without its log
            File(old.path + suffix).takeIf { it.exists() }?.renameTo(File(new.path + suffix))
        }
    }
}
