package app.glimpse.android

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Pairing with pc_helper.py on the user's PC: "192.168.1.5:8765/abcdefghjk", as the helper prints it.
 *  Only private (home network) IPv4 addresses are accepted, so photos can't be sent anywhere on the internet. */
data class PcLink(val host: String, val port: Int, val token: String) {
    val code get() = "$host:$port/$token"

    companion object {
        private val CODE = Regex("""^\s*(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?/([a-z0-9]{6,64})\s*$""")
        private const val PREFS = "pc"

        fun parse(code: String): PcLink? {
            val m = CODE.matchEntire(code.lowercase()) ?: return null
            val (host, port, token) = m.destructured
            val p = port.ifEmpty { "8765" }.toInt()
            return if (isPrivate(host) && p in 1..65535) PcLink(host, p, token) else null
        }

        /** 10/8, 172.16/12, 192.168/16: addresses a home router hands out, never routed on the internet. */
        fun isPrivate(ip: String): Boolean {
            val b = ip.split(".").map { it.toIntOrNull() ?: return false }
            if (b.size != 4 || b.any { it !in 0..255 }) return false
            return b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168)
        }

        fun load(context: Context): PcLink? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("code", null)?.let(::parse)

        fun save(context: Context, link: PcLink?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("code", link?.code).apply()
        }
    }
}

/** The PC's answer for one photo: boxes in pixels of the JPEG that was sent, unit-length 512-d embeddings. */
class PcFace(val box: FloatArray, val score: Float, val emb: FloatArray)

/** Thrown when the PC is reachable but turned this request down (bad pairing code, not an image). */
class PcRefused(val status: Int, message: String) : IOException(message)

/** [allowed] is only widened by tests, to reach a fake helper on 127.0.0.1. */
class PcClient(val link: PcLink, private val allowed: (String) -> Boolean = PcLink::isPrivate) {
    private val base = "http://${link.host}:${link.port}"

    /** Checks the PC is there and the pairing code is right; returns "cuda" or "cpu". */
    fun hello(timeoutMs: Int = 3000): String =
        JSONObject(String(request("GET", "/hello", null, timeoutMs, timeoutMs))).getString("device")

    fun faces(jpeg: ByteArray): List<PcFace> {
        val r = JSONObject(String(request("POST", "/faces", jpeg, 3000, 60_000)))
        val arr = r.getJSONArray("faces")
        return List(arr.length()) { i ->
            val f = arr.getJSONObject(i)
            val b = f.getJSONArray("box")
            val raw = ByteBuffer.wrap(Base64.getDecoder().decode(f.getString("emb"))).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            PcFace(FloatArray(4) { b.getDouble(it).toFloat() }, f.getDouble("score").toFloat(), FloatArray(raw.remaining()).also { raw.get(it) })
        }
    }

    private fun request(method: String, path: String, body: ByteArray?, connectMs: Int, readMs: Int): ByteArray {
        if (!allowed(link.host)) throw IOException("Not a home network address")
        // Not disconnected: reading both streams to the end lets the next photo reuse the connection.
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.run {
            requestMethod = method
            connectTimeout = connectMs
            readTimeout = readMs
            instanceFollowRedirects = false // never be sent on somewhere else
            setRequestProperty("Authorization", "Bearer ${link.token}")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "image/jpeg")
                setFixedLengthStreamingMode(body.size)
                outputStream.use { it.write(body) }
            }
            if (responseCode != 200) throw PcRefused(responseCode, errorStream?.use { String(it.readBytes()) } ?: "HTTP $responseCode")
            return inputStream.use { it.readBytes() }
        }
    }
}
