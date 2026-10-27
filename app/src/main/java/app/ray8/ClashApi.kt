package app.ray8

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder

/** Tiny client for the clash API of the running sing-box (live switching, real delay, counters). */
class ClashApi(private val port: Int, private val secret: String) {
    class Traffic(val up: Long, val down: Long, val connections: Int)

    private fun call(path: String, method: String = "GET", body: String? = null, timeoutMs: Int = 5000): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 3000
            c.readTimeout = timeoutMs
            c.setRequestProperty("Authorization", "Bearer $secret")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }

    fun ready(): Boolean = try {
        call("/version", timeoutMs = 2000).first == 200
    } catch (_: Exception) {
        false
    }

    /** Real delay of one outbound in ms (the request runs through that outbound), or null on failure. */
    @Volatile var lastError: String = ""

    fun delay(tag: String, url: String, timeoutMs: Int): Int? = try {
        val q = "?url=" + URLEncoder.encode(url, "UTF-8") + "&timeout=$timeoutMs"
        val (code, text) = call("/proxies/${URLEncoder.encode(tag, "UTF-8")}/delay$q", timeoutMs = timeoutMs + 4000)
        if (code == 200) {
            lastError = ""
            JSONObject(text).optInt("delay", -1).takeIf { it > 0 }
        } else {
            lastError = "HTTP $code ${text.take(300)}"
            null
        }
    } catch (e: Exception) {
        lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
        null
    }

    /**
     * Runs the url test of a group (the auto / urltest group) over all its members right now, so the group
     * switches to the best working one at once. True when at least one member answered.
     */
    fun groupTest(group: String, url: String, timeoutMs: Int): Boolean = try {
        val q = "?url=" + URLEncoder.encode(url, "UTF-8") + "&timeout=$timeoutMs"
        val (code, text) = call("/group/${URLEncoder.encode(group, "UTF-8")}/delay$q", timeoutMs = timeoutMs * 2 + 6000)
        code == 200 && JSONObject(text).let { o -> o.keys().asSequence().any { k -> o.optInt(k, 0) > 0 } }
    } catch (_: Exception) {
        false
    }

    fun select(group: String, tag: String): Boolean = try {
        call("/proxies/$group", "PUT", JSONObject().put("name", tag).toString()).first in 200..299
    } catch (_: Exception) {
        false
    }

    /** The config the selector points at right now. */
    fun selected(group: String): String? = try {
        val (code, text) = call("/proxies/$group")
        if (code == 200) JSONObject(text).optString("now").ifEmpty { null } else null
    } catch (_: Exception) {
        null
    }

    fun traffic(): Traffic? = try {
        val (code, text) = call("/connections")
        if (code == 200) {
            val o = JSONObject(text)
            Traffic(o.optLong("uploadTotal"), o.optLong("downloadTotal"), o.optJSONArray("connections")?.length() ?: 0)
        } else null
    } catch (_: Exception) {
        null
    }

    /** Drops every open connection so apps reconnect on the new path right away. */
    fun closeAll() {
        runCatching { call("/connections", "DELETE") }
    }
}
