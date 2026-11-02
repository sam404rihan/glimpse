package app.glimpse

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.glimpse.android.Decode
import app.glimpse.android.FaceEngine
import app.glimpse.android.MODEL_VERSION
import app.glimpse.android.MediaStoreSource
import app.glimpse.android.PhoneFaceAnalyzer
import app.glimpse.data.PeopleStore
import app.glimpse.data.GlimpseDb
import app.glimpse.data.SqliteIndexStore
import app.glimpse.faces.Vec
import app.glimpse.index.FaceAnalyzer
import app.glimpse.index.MediaItem
import app.glimpse.index.Scanner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

private val inst get() = InstrumentationRegistry.getInstrumentation()
private val ctx get() = inst.targetContext

/** The scanner contract again, on the real SQLite store. */
@RunWith(AndroidJUnit4::class)
class SqliteScannerTest : ScannerContract() {
    override fun newStore() = SqliteIndexStore(GlimpseDb(ctx, null))
}

@RunWith(AndroidJUnit4::class)
class DeviceTests {
    private val test = inst.context.assets
    private val cr = ctx.contentResolver
    private val inserted = mutableListOf<Uri>()

    private fun insert(name: String, bytes: ByteArray, folder: String = "Pictures/GlimpseTest/"): Uri {
        val uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        cr.openOutputStream(uri)!!.use { it.write(bytes) }
        cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        inserted += uri
        return uri
    }

    @After fun cleanUp() { inserted.forEach { runCatching { cr.delete(it, null, null) } } }

    private fun golden() = test.open("golden.txt").bufferedReader().readLines().filter { it.isNotBlank() }.map { l ->
        val p = l.split(" ")
        Triple(p[0], p.subList(1, 5).map { it.toFloat() }, Vec.normalize(p.drop(6).map { it.toFloat() }.toFloatArray()))
    }

    /** Android decode + Kotlin preprocessing + ONNX Runtime give the PC's faces and identities. */
    @Test fun engineMatchesPcGoldens() {
        val byImage = golden().groupBy { it.first }
        FaceEngine(ctx).use { eng ->
            var n = 0; var sum = 0.0; var min = 1.0
            for (name in byImage.keys.take(30)) {
                val uri = insert(name, test.open("lfw/$name").readBytes())
                val d = Decode.pixels(cr, uri)
                val got = eng.analyze(d.px, d.w, d.h)
                for ((_, box, emb) in byImage.getValue(name)) {
                    val best = got.maxBy { (det, _) -> app.glimpse.faces.Scrfd.iou(det.box, box.toFloatArray()) }
                    assertTrue(name, app.glimpse.faces.Scrfd.iou(best.first.box, box.toFloatArray()) > 0.8f)
                    val c = Vec.dot(best.second, emb).toDouble()
                    sum += c; n++; min = minOf(min, c)
                }
            }
            println("GLIMPSE engine vs PC: $n faces, cos mean ${sum / n}, min $min")
            assertTrue(sum / n >= 0.98 && min >= 0.95)
        }
    }

    /** A group photo is recognised a few faces at a time (memory cap) with the same identities as all at once. */
    @Test fun groupPhotoInBatchesMatchesAllAtOnce() {
        val names = golden().map { it.first }.distinct().take(6)
        val collage = Bitmap.createBitmap(750, 500, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(collage)
        names.forEachIndexed { i, name ->
            val b = android.graphics.BitmapFactory.decodeStream(test.open("lfw/$name"))
            canvas.drawBitmap(b, (i % 3) * 250f, (i / 3) * 250f, null)
        }
        val px = IntArray(750 * 500).also { collage.getPixels(it, 0, 750, 0, 0, 750, 500) }
        val batched = FaceEngine(ctx.assets, recBatch = FaceEngine.REC_BATCH).use { it.analyze(px, 750, 500) }
        val whole = FaceEngine(ctx.assets, recBatch = 64).use { it.analyze(px, 750, 500) }
        assertTrue("need more faces than one batch, got ${batched.size}", batched.size > FaceEngine.REC_BATCH)
        assertEquals(whole.size, batched.size)
        for ((a, b) in batched.zip(whole)) assertTrue(Vec.dot(a.second, b.second) > 0.999f)
    }

    /** Portrait camera photos: EXIF rotation is applied before faces are found. */
    @Test fun exifRotatedPhotoIsDecodedUpright() {
        val name = golden().first().first
        val upright = test.open("lfw/$name").readBytes()
        val uri = insert("rotated.jpg", withExifOrientation(upright, 6))
        val d = Decode.pixels(cr, uri)
        FaceEngine(ctx).use { eng -> assertTrue(eng.analyze(d.px, d.w, d.h).isNotEmpty()) }
    }

    /** A 48 MP photo is decoded at 1600 px, not full size. */
    @Test fun hugePhotoIsDecodedSmall() {
        val big = Bitmap.createBitmap(8000, 6000, Bitmap.Config.RGB_565)
        val bytes = ByteArrayOutputStream().also { big.compress(Bitmap.CompressFormat.JPEG, 50, it) }.toByteArray()
        big.recycle()
        val d = Decode.pixels(cr, insert("huge.jpg", bytes))
        assertEquals(1600, maxOf(d.w, d.h))
        assertEquals(8000, d.origW)
    }

    /** End to end on the 120 LFW photos: grouping, then rescans, moves and copies run the models 0 times. */
    @Test fun scanGroupPeopleAndNeverReanalyse() {
        for (name in test.list("lfw")!!) insert(name, test.open("lfw/$name").readBytes())
        val db = GlimpseDb(ctx, null)
        val store = SqliteIndexStore(db)
        val people = PeopleStore(db)
        val src = MediaStoreSource(ctx, onlyPath = "Pictures/GlimpseTest/")
        var analysed = 0
        val counting = {
            val real = PhoneFaceAnalyzer(ctx)
            object : FaceAnalyzer {
                override fun analyze(items: List<MediaItem>) = real.analyze(items).also { analysed += items.size }
                override fun close() = real.close()
            }
        }
        fun scan() = Scanner(src, store, MODEL_VERSION, counting).scan().also { if (it.facesChanged) people.recluster() }

        val t0 = System.nanoTime()
        scan()
        val secs = (System.nanoTime() - t0) / 1e9
        println("GLIMPSE first scan: $analysed photos in %.1f s (%.1f photos/s on this device)".format(secs, analysed / secs))
        assertEquals(120, analysed)
        // Each of the 10 people in the set gets all 8 of their photos and nobody else's. (Extra people are fine:
        // two Laura Bush photos share a second person in the background, correctly grouped.)
        val names = test.list("lfw")!!.associateBy { n -> store.pending(-1, 999).first { it.item.name == n }.fingerprint }
        val groups = people.list().map { p -> people.photos(listOf(p.id)).map { names.getValue(it.fingerprint).substringBeforeLast("_") } }
        println("GLIMPSE people: $groups")
        val identities = test.list("lfw")!!.groupBy { it.substringBeforeLast("_") }.filterValues { it.size == 8 }.keys
        for (ident in identities) assertTrue("$ident: $groups", groups.any { g -> g.size == 8 && g.all { it == ident } })

        analysed = 0
        scan()
        assertEquals(0, analysed)

        // move one photo to another folder and save a copy of another: still nothing to analyse
        cr.update(inserted[0], ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GlimpseTest/moved/") }, null, null)
        insert("copy.jpg", test.open("lfw/${test.list("lfw")!![1]}").readBytes())
        scan()
        assertEquals(0, analysed)
        assertEquals(groups.size, people.list().size)
    }

    /** Writes a JPEG APP1 EXIF block with the given orientation in front of [jpeg]'s data. */
    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        // Rotate the pixels the other way first, so that applying the EXIF tag makes the face upright again.
        val src = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val m = android.graphics.Matrix().apply { postRotate(-90f) }
        val rot = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val body = ByteArrayOutputStream().also { rot.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        val tiff = byteArrayOf(0x4d, 0x4d, 0, 0x2a, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0)
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = payload.size + 2
        val app1 = byteArrayOf(0xff.toByte(), 0xe1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
        return body.copyOfRange(0, 2) + app1 + body.copyOfRange(2, body.size)
    }
}
