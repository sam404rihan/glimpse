package app.glimpse

import app.glimpse.android.PcClient
import app.glimpse.android.PcLink
import app.glimpse.android.PcRefused
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.concurrent.thread

/** The phone's side of pc_helper.py's protocol, against a fake helper answering as tools/pc_helper.py does. */
class PcClientTest {
    private val received = mutableListOf<ByteArray>()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { serve(it) }
            }
        }
    }

    /** One HTTP/1.1 request per connection: just enough of HTTP for HttpURLConnection. */
    private fun serve(sock: Socket) {
        val inp = BufferedInputStream(sock.getInputStream())
        fun line() = generateSequence { inp.read().takeIf { it >= 0 } }.takeWhile { it != '\n'.code }
            .map { it.toChar() }.joinToString("").trimEnd('\r')
        val path = line().split(" ")[1]
        val headers = generateSequence(::line).takeWhile { it.isNotEmpty() }
            .associate { it.substringBefore(":").lowercase() to it.substringAfter(":").trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { b -> var n = 0; while (n < b.size) n += inp.read(b, n, b.size - n) }
        val (status, json) = when {
            headers["authorization"] != "Bearer abcdefghjk" -> 401 to """{"detail":"Wrong pairing code"}"""
            path == "/hello" -> 200 to """{"device":"cuda"}"""
            else -> {
                received += body
                val emb = ByteBuffer.allocate(512 * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until 512) emb.putFloat(if (i == 3) 1f else 0f)
                200 to """{"w":1600,"h":1200,"faces":[{"box":[100.5,200.0,300.0,420.0],"score":0.91,""" +
                    """"emb":"${Base64.getEncoder().encodeToString(emb.array())}"}]}"""
            }
        }
        val out = json.toByteArray()
        sock.getOutputStream().apply {
            write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(out)
            flush()
        }
    }

    /** 127.0.0.1 isn't a home-network address, so the fake is reached through a link built by hand. */
    private fun client(token: String = "abcdefghjk") = PcClient(PcLink("127.0.0.1", server.localPort, token)) { true }

    @After fun stop() = server.close()

    @Test fun findsFaces() {
        assertEquals("cuda", client().hello())
        val faces = client().faces(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), received.single())
        val f = faces.single()
        assertArrayEquals(floatArrayOf(100.5f, 200f, 300f, 420f), f.box, 0f)
        assertEquals(0.91f, f.score, 1e-6f)
        assertEquals(512, f.emb.size)
        assertEquals(1f, f.emb[3], 0f)
    }

    @Test fun wrongCodeIsRefused() {
        try { client("wrongcode1").hello(); fail() } catch (e: PcRefused) { assertEquals(401, e.status) }
    }

    @Test fun neverSendsOffTheHomeNetwork() {
        try { PcClient(PcLink("8.8.8.8", 8765, "abcdefghjk")).hello(); fail() } catch (e: PcRefused) { fail() } catch (e: java.io.IOException) { }
    }
}
