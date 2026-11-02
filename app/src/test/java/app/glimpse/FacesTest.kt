package app.glimpse

import app.glimpse.faces.Pixels
import app.glimpse.faces.Scrfd
import app.glimpse.faces.Vec
import app.glimpse.people.ClusterInput
import app.glimpse.people.Clusterer
import app.glimpse.people.FaceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Fixtures come from mobile_export/test_mobile_models.py (the phone models run on the PC). */
class FacesTest {
    private fun text(name: String) = javaClass.getResource("/fixtures/$name")!!.readText().lines().filter { it.isNotBlank() }
    private fun floats(line: String) = line.trim().split(" ").map { it.toFloat() }

    @Test
    fun scrfdDecodeMatchesDesktop() {
        for (stem in listOf("Colin_Powell_0011") + (javaClass.getResource("/fixtures")!!.let { java.io.File(it.toURI()) }
            .list()!!.filter { it.startsWith("det_0") && it.endsWith(".bin") }.map { it.removePrefix("det_").removeSuffix(".bin") })) {
            val raw = javaClass.getResource("/fixtures/det_$stem.bin")!!.readBytes()
            val all = FloatArray(raw.size / 4).also { ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            val sizes = listOf(12800, 3200, 800).let { s -> s + s.map { it * 4 } + s.map { it * 10 } }
            var off = 0
            val outs = sizes.map { n -> all.copyOfRange(off, off + n).also { off += n } }
            val got = Scrfd.decode(outs)
            val want = text("det_$stem.txt").map(::floats)
            assertEquals(stem, want.size, got.size)
            for ((g, w) in got.zip(want)) {
                for (k in 0 until 4) assertEquals(w[k], g.box[k], 0.01f)
                assertEquals(w[4], g.score, 1e-4f)
                for (k in 0 until 10) assertEquals(w[5 + k], g.kps[k], 0.01f)
            }
        }
    }

    @Test
    fun similarityTransformMatchesNumpy() {
        for (l in text("align.txt")) {
            val v = floats(l)
            val m = Pixels.similarity(v.subList(0, 10).toFloatArray())
            for (k in 0 until 6) assertEquals(v[10 + k].toDouble(), m[k], 1e-3 * maxOf(1.0, abs(v[10 + k].toDouble())))
        }
    }

    @Test
    fun halfFloatsRoundTrip() {
        assertEquals(0x3c00, Vec.toHalf(1f)); assertEquals(0xc000, Vec.toHalf(-2f)); assertEquals(0, Vec.toHalf(0f))
        val v = FloatArray(512) { (it - 256) / 300f }
        val back = Vec.fromHalfBytes(Vec.toHalfBytes(v))
        for (i in v.indices) assertEquals(v[i], back[i], 1e-3f)
    }

    @Test
    fun resizeKeepsFlatColourAndSize() {
        val px = IntArray(250 * 250) { 0xff336699.toInt() }
        val out = Pixels.resize(px, 250, 250, 640, 640)
        assertEquals(640 * 640, out.size)
        assertTrue(out.all { it == 0xff336699.toInt() })
    }

    /** The desktop's acceptance (tests/test_faces.py) on the phone models' vectors for the 120 LFW photos. */
    @Test fun lfwPeopleAreGroupedCorrectly() = grouping("faces.txt") // vectors from the shipped INT8 recogniser

    private fun grouping(fixture: String) {
        val identities = listOf("Colin_Powell", "Tony_Blair", "Serena_Williams", "Vladimir_Putin", "Junichiro_Koizumi",
            "Hugo_Chavez", "Laura_Bush", "Arnold_Schwarzenegger", "Lleyton_Hewitt", "Gloria_Macapagal_Arroyo")
        val rows = text(fixture).mapIndexed { i, l ->
            val p = l.split(" ")
            FaceRow(i.toLong(), p[0], Vec.normalize(p.drop(3).map { it.toFloat() }.toFloatArray()), null, p[1].toFloat(), p[2].toFloat())
        }
        fun who(img: String) = img.substringBeforeLast("_")
        var next = 1000L
        val t0 = System.nanoTime()
        val r = Clusterer.cluster(ClusterInput(rows, emptySet(), emptySet(), emptySet())) { next++ }
        println("$fixture: clustered ${rows.size} faces in ${(System.nanoTime() - t0) / 1_000_000} ms")
        val groups = rows.groupBy { r.assign[it.id] }.filterKeys { it != null }.mapValues { (_, fs) -> fs.map { who(it.image) } }
        for (ident in identities) {
            val labels = groups.values.maxBy { g -> g.count { it == ident } }
            val recall = labels.count { it == ident } / 8.0
            val purity = labels.count { it == ident } / labels.size.toDouble()
            println("  %-26s found %.0f/8 photos, purity %.0f%%".format(ident, recall * 8, purity * 100))
            assertTrue("$ident $labels", recall >= 7 / 8.0)
            assertTrue("$ident $labels", purity >= 0.9)
        }
        val singles = rows.map { who(it.image) }.toSet() - identities.toSet()
        for (labels in groups.values) assertTrue(labels.toString(), !(labels.any { it in singles } && labels.any { it !in singles }))
    }
}
