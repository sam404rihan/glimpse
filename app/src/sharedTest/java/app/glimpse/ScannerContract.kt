package app.glimpse

import app.glimpse.index.IndexStore
import app.glimpse.index.Scanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every way a photo can move around must never re-run the models on content already seen.
 *  Runs on the JVM against MemoryIndexStore and on the device against SqliteIndexStore. */
abstract class ScannerContract {
    abstract fun newStore(): IndexStore

    private val src = FakeSource()
    private val az = CountingAnalyzer()
    private var now = 1_000_000L
    private val store by lazy { newStore() }
    private fun scan(stop: () -> Boolean = { false }) = Scanner(src, store, 1, { az }, { now }).scan(stop)
    private fun analysedNow(block: () -> Unit): Int { val before = az.analysed.size; block(); return az.analysed.size - before }

    private fun library(n: Int = 20) = (1..n).map { src.add("external_primary", "DCIM/Camera/IMG_$it.jpg", "face$it") }

    @Test fun firstScanAnalysesEachPhotoOnce_secondScanNothing() {
        library()
        assertEquals(20, analysedNow { scan() })
        assertEquals(0, analysedNow { scan() })
        assertEquals(0, analysedNow { scan() })
    }

    @Test fun movedRenamedAndCopiedPhotosAreNotAnalysedAgain() {
        val items = library()
        scan()
        assertEquals(0, analysedNow {
            src.move(items[0], "Pictures/Trip/renamed.jpg")
            src.add("external_primary", "Download/copy.jpg", "face2") // same content, new file
            scan()
        })
        assertEquals("Pictures/Trip/", store.file("external_primary", items[0].mediaId)!!.relPath)
    }

    @Test fun reassignedMediaStoreIdsAreMatchedByContent() {
        val items = library()
        scan()
        // MediaStore rebuilt its database: every photo gets a new id and the volume version changes.
        items.forEach { src.delete(it) }
        src.vol("external_primary").version = "v2"
        (1..20).forEach { src.add("external_primary", "DCIM/Camera/IMG_$it.jpg", "face$it") }
        assertEquals(0, analysedNow { scan() })
    }

    @Test fun editedPhotoIsAnalysedAgain_onlyThatOne() {
        val items = library()
        scan()
        src.edit(items[3], "face99")
        assertEquals(1, analysedNow { scan() })
    }

    @Test fun sdCardRemovedAndReinsertedIsNotAnalysedAgain() {
        library(5)
        val sd = (1..5).map { src.add("1234-abcd", "DCIM/IMG_sd$it.jpg", "sd$it") }
        scan()
        src.vol("1234-abcd").mounted = false
        assertEquals(0, analysedNow { scan() })
        assertFalse(store.volume("1234-abcd")!!.mounted)
        assertTrue(store.fileIds("1234-abcd").containsAll(sd.map { it.mediaId })) // kept while the card is out
        src.vol("1234-abcd").mounted = true
        assertEquals(0, analysedNow { scan() })
    }

    @Test fun volumeThatFailsMidScanDoesNotStopTheOthers() {
        src.add("1234-abcd", "DCIM/IMG_sd.jpg", "sd1")
        src.failVolume = "1234-abcd"
        library(3)
        assertEquals(3, analysedNow { scan() })
        src.failVolume = null
        assertEquals(1, analysedNow { scan() })
    }

    @Test fun interruptedScanContinuesWhereItStopped() {
        library(40)
        val r = Scanner(src, store, 1, { az }, { now }).scan(stop = { az.calls >= 1 }) // killed after one batch
        assertTrue(r.stopped)
        val first = az.analysed.size
        assertTrue(first in 1 until 40)
        assertEquals(40 - first, analysedNow { scan() })
        assertEquals(40, az.analysed.map { it.mediaId }.toSet().size)
    }

    @Test fun partialAccessWidenedLaterAnalysesOnlyNewlyVisible() {
        val items = library(10)
        src.hidden = items.drop(4).map { it.mediaId }.toSet() // user picked 4 photos
        assertEquals(4, analysedNow { scan() })
        src.hidden = emptySet() // "Allow all": the other 6 appear without changing
        assertEquals(6, analysedNow { scan() })
    }

    @Test fun photoRestoredFromTrashWithin30DaysIsNotAnalysedAgain() {
        val items = library(5)
        scan()
        src.hidden = setOf(items[0].mediaId) // moved to trash
        assertTrue(scan().facesChanged) // its faces leave the people view
        now += 20L * 24 * 3600 * 1000
        src.hidden = emptySet() // restored
        assertEquals(0, analysedNow { assertTrue(scan().facesChanged) })
    }

    @Test fun photoGoneMoreThan30DaysIsForgotten() {
        val items = library(2)
        scan()
        src.delete(items[0])
        scan()
        now += 31L * 24 * 3600 * 1000
        scan()
        src.add("external_primary", "DCIM/back.jpg", "face1")
        assertEquals(1, analysedNow { scan() })
    }

    @Test fun stickersAndTinyFilesAreSkipped() {
        src.add("external_primary", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/s.webp", "sticker")
        src.add("external_primary", "DCIM/tiny.png", "tiny", size = 300)
        library(1)
        assertEquals(1, analysedNow { scan() })
        assertEquals(0, analysedNow { scan() })
    }

    @Test fun modelsAreNotLoadedWhenNothingIsNew() {
        library(3)
        scan()
        var loads = 0
        Scanner(src, store, 1, { loads++; az }, { now }).scan()
        assertEquals(0, loads)
    }
}
