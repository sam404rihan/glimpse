package app.glimpse

import app.glimpse.android.PcLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Photos may only go to a PC on the home network, with the code pc_helper.py printed. */
class PcLinkTest {
    @Test fun parsesTheCodeAsPrinted() {
        assertEquals(PcLink("192.168.1.5", 8765, "abcdefghjk"), PcLink.parse("192.168.1.5:8765/abcdefghjk"))
        assertEquals(PcLink("10.0.0.7", 8765, "abcdefghjk"), PcLink.parse("  10.0.0.7/ABCDEFGHJK "))
        assertEquals("172.20.3.4:9000/abcdefghjk", PcLink.parse("172.20.3.4:9000/abcdefghjk")!!.code)
    }

    @Test fun refusesAnythingOffTheHomeNetwork() {
        for (code in listOf("8.8.8.8:8765/abcdefghjk", "172.32.0.1:8765/abcdefghjk", "192.169.1.5:8765/abcdefghjk",
            "example.com:8765/abcdefghjk", "192.168.1.300:8765/abcdefghjk", "192.168.1.5:99999/abcdefghjk",
            "192.168.1.5:8765", "192.168.1.5:8765/ab", "http://192.168.1.5:8765/abcdefghjk")) {
            assertNull(code, PcLink.parse(code))
        }
    }

    @Test fun privateRanges() {
        assertTrue(PcLink.isPrivate("10.255.0.1"))
        assertTrue(PcLink.isPrivate("172.16.0.1"))
        assertTrue(PcLink.isPrivate("172.31.255.255"))
        assertFalse(PcLink.isPrivate("172.15.0.1"))
        assertFalse(PcLink.isPrivate("127.0.0.1"))
        assertFalse(PcLink.isPrivate("1.2.3"))
    }
}
