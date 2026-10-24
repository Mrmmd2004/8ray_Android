package app.ray8

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** Downloads subscription lists (plain or base64) and keeps their configs up to date. */
object Subs {
    class Fetched(
        val links: List<String>, val upload: Long, val download: Long, val total: Long, val expire: Long, val title: String,
    )

    class Result(val added: Int, val removed: Int, val error: String?)

    private fun open(url: String, viaPort: Int): HttpURLConnection {
        val u = URL(url)
        val c = (if (viaPort > 0) u.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", viaPort))) else u.openConnection()) as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "v2rayN/7.0 8ray/" + BuildConfig.VERSION_NAME)
        c.setRequestProperty("Accept", "*/*")
        return c
    }

    /** Plain download of any text, used for subscriptions. [viaPort] > 0 goes through the local SOCKS5 inbound. */
    fun fetch(url: String, viaPort: Int): Fetched {
        val c = open(url, viaPort)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val out = ByteArrayOutputStream()
            c.inputStream.use { i ->
                val buf = ByteArray(16 * 1024)
                while (out.size() < 8 * 1024 * 1024) {
                    val n = i.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            val body = String(out.toByteArray(), Charsets.UTF_8)
            val links = Links.extract(body)
            val info = c.getHeaderField("subscription-userinfo") ?: ""
            fun num(key: String): Long =
                Regex("$key\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE).find(info)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            val rawTitle = c.getHeaderField("profile-title") ?: ""
            val title = when {
                rawTitle.startsWith("base64:") -> runCatching { String(Links.b64d(rawTitle.removePrefix("base64:")), Charsets.UTF_8) }.getOrDefault("")
                else -> runCatching { java.net.URLDecoder.decode(rawTitle, "UTF-8") }.getOrDefault(rawTitle)
            }
            return Fetched(links, num("upload"), num("download"), num("total"), num("expire"), title.trim())
        } finally {
            c.disconnect()
        }
    }

    private fun proxyPort(): Int =
        if (Engine.status.value.phase == Phase.CONNECTED) Engine.internalPort else 0

    /** Fetches one subscription, going through the tunnel when it is up and falling back to a direct download. */
    fun fetchSmart(url: String): Fetched {
        val via = proxyPort()
        return if (via > 0) {
            try {
                fetch(url, via)
            } catch (_: Exception) {
                fetch(url, 0)
            }
        } else {
            try {
                fetch(url, 0)
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun update(subId: String): Result = withContext(Dispatchers.IO) {
        val sub = NodeStore.sub(subId) ?: return@withContext Result(0, 0, "missing")
        try {
            val f = fetchSmart(sub.url)
            if (f.links.isEmpty()) throw java.io.IOException("no configs found")
            val (added, removed) = NodeStore.applySub(subId, f.links)
            val keepName = sub.name.isNotBlank() && sub.name != (runCatching { java.net.URI(sub.url).host }.getOrNull() ?: "")
            NodeStore.updateSub(
                sub.copy(
                    name = if (!keepName && f.title.isNotEmpty()) f.title else sub.name,
                    updated = System.currentTimeMillis(), upload = f.upload, download = f.download,
                    total = f.total, expire = f.expire, error = "",
                )
            )
            Result(added, removed, null)
        } catch (e: Exception) {
            NodeStore.updateSub(sub.copy(error = e.message ?: e.javaClass.simpleName))
            Result(0, 0, e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun updateAll(onlyDue: Boolean, hours: Int): Int {
        var n = 0
        val now = System.currentTimeMillis()
        for (s in NodeStore.subs.value) {
            if (onlyDue && (!s.auto || now - s.updated < hours.coerceAtLeast(1) * 3_600_000L)) continue
            if (update(s.id).error == null) n++
        }
        return n
    }
}
