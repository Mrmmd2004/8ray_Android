package app.ray8

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

class LinkError(msg: String) : Exception(msg)

/** A share link turned into a sing-box outbound (or WireGuard endpoint). [json] has no tag yet. */
class ParsedLink(
    val name: String,
    val json: JSONObject,
    val endpoint: Boolean,
    val kind: String,
    /** True when the protocol is carried over UDP (QUIC, WireGuard): a TCP ping means nothing for it. */
    val udp: Boolean,
) {
    val proto: String get() = json.optString("type")
    val host: String get() = json.optString("server")
    val port: Int get() = json.optInt("server_port")
}

/**
 * Share links (vless / vmess / trojan / ss / hysteria2 / hysteria / tuic / wireguard / socks) to sing-box
 * JSON. Same rules as the Windows app, plus WireGuard, Hysteria v1 and raw sing-box outbound JSON.
 */
object Links {
    private val SCHEMES = listOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "hysteria", "tuic", "wireguard", "wg", "socks5", "socks", "singbox")
    private val LINK_RE = Regex("(?:" + SCHEMES.joinToString("|") + ")://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)

    // ───────────────────────── helpers ─────────────────────────

    fun b64d(input: String): ByteArray {
        val s = input.filter { !it.isWhitespace() }.replace('-', '+').replace('_', '/')
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun b64s(input: String) = String(b64d(input), Charsets.UTF_8)

    private fun dec(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun truthy(v: String?) = v != null && v.trim().lowercase() in setOf("1", "true", "yes", "on")

    private fun splitList(s: String?, regex: Regex) = (s ?: "").split(regex).map { it.trim() }.filter { it.isNotEmpty() }

    private class U(
        val scheme: String, val user: String?, val pass: String?, val host: String, val portRaw: String,
        val path: String, val q: Map<String, String>, val frag: String,
    ) {
        val port: Int? get() = portRaw.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private fun parseUrl(link: String): U {
        val s = link.trim()
        val se = s.indexOf("://")
        if (se < 0) throw LinkError("unknown link type")
        val scheme = s.substring(0, se).lowercase()
        var rest = s.substring(se + 3)
        var frag = ""
        rest.indexOf('#').let { if (it >= 0) { frag = dec(rest.substring(it + 1)); rest = rest.substring(0, it) } }
        var qs = ""
        rest.indexOf('?').let { if (it >= 0) { qs = rest.substring(it + 1); rest = rest.substring(0, it) } }
        var path = ""
        rest.indexOf('/').let { if (it >= 0) { path = rest.substring(it); rest = rest.substring(0, it) } }
        val at = rest.lastIndexOf('@')
        val userinfo = if (at >= 0) rest.substring(0, at) else null
        val hostport = rest.substring(at + 1)
        val host: String
        val portRaw: String
        if (hostport.startsWith("[")) {
            val e = hostport.indexOf(']')
            if (e < 0) throw LinkError("address or port is missing")
            host = hostport.substring(1, e)
            portRaw = hostport.substring(e + 1).removePrefix(":")
        } else {
            val c = hostport.lastIndexOf(':')
            host = if (c >= 0) hostport.substring(0, c) else hostport
            portRaw = if (c >= 0) hostport.substring(c + 1) else ""
        }
        var user: String? = null
        var pass: String? = null
        if (userinfo != null) {
            val c = userinfo.indexOf(':')
            if (c >= 0) { user = dec(userinfo.substring(0, c)); pass = dec(userinfo.substring(c + 1)) } else user = dec(userinfo)
        }
        val q = LinkedHashMap<String, String>()
        for (part in qs.split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            val k = dec(if (i >= 0) part.substring(0, i) else part)
            if (k !in q) q[k] = if (i >= 0) dec(part.substring(i + 1)) else ""
        }
        return U(scheme, user, pass, host, portRaw, path, q, frag)
    }

    private fun hostPort(u: U): Pair<String, Int> {
        val p = u.port
        if (u.host.isEmpty() || p == null) throw LinkError("address or port is missing")
        return u.host to p
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) if (v != null) o.put(k, v)
        return o
    }

    private fun arr(items: List<String>) = JSONArray().also { a -> items.forEach { a.put(it) } }

    /** "/ws?ed=2048" to ("/ws", 2048). */
    private fun cleanPath(path: String): Pair<String, Int> {
        val p = path.ifEmpty { "/" }
        val i = p.indexOf('?')
        if (i < 0) return p to 0
        val base = p.substring(0, i)
        var ed = 0
        val keep = ArrayList<String>()
        for (part in p.substring(i + 1).split('&')) {
            if (part.startsWith("ed=") && part.substring(3).all { it.isDigit() } && part.length > 3) ed = part.substring(3).toInt()
            else if (part.isNotEmpty()) keep.add(part)
        }
        return (if (keep.isEmpty()) base else base + "?" + keep.joinToString("&")) to ed
    }

    private val HOST_SPLIT = Regex("[,\\s]+")

    /**
     * Xray (v2rayNG) sends a Chrome User-Agent on WebSocket / HTTPUpgrade requests; sing-box sends
     * "Go-http-client" unless a header is given. Some servers and fronts drop the latter, so the same
     * browser-like value is used here when the link does not set its own.
     */
    private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private fun transport(netIn: String?, hostIn: String?, path: String, service: String, headerType: String): JSONObject? {
        val net = (netIn ?: "tcp").trim().lowercase()
        val host = (hostIn ?: "").trim()
        return when (net) {
            "", "tcp", "raw" ->
                if (headerType.lowercase() == "http") obj("type" to "http", "host" to arr(splitList(host, HOST_SPLIT)), "path" to path.ifEmpty { "/" })
                else null
            "ws", "websocket" -> {
                val (p, ed) = cleanPath(path)
                val t = obj("type" to "ws", "path" to p)
                t.put("headers", obj("User-Agent" to BROWSER_UA).also { if (host.isNotEmpty()) it.put("Host", host) })
                if (ed > 0) {
                    t.put("max_early_data", ed)
                    t.put("early_data_header_name", "Sec-WebSocket-Protocol")
                }
                t
            }
            "grpc" -> obj("type" to "grpc", "service_name" to service.ifEmpty { path.trimStart('/') })
            "h2", "http" -> obj("type" to "http", "host" to arr(splitList(host, HOST_SPLIT)), "path" to path.ifEmpty { "/" })
            "httpupgrade" -> obj("type" to "httpupgrade", "host" to host, "path" to path.ifEmpty { "/" }, "headers" to obj("User-Agent" to BROWSER_UA))
            "xhttp", "splithttp" -> throw LinkError("xhttp is not supported by sing-box")
            "kcp", "mkcp" -> throw LinkError("mKCP is not supported by sing-box")
            "quic" -> throw LinkError("the QUIC transport is not supported by sing-box")
            else -> throw LinkError("unsupported transport: $net")
        }
    }

    private val FINGERPRINTS = setOf("chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized")

    private fun fingerprint(fp: String?): String? {
        val f = (fp ?: "").trim().lowercase()
        if (f.isEmpty() || f == "none") return null
        if (f.startsWith("randomized")) return "randomized"
        return if (f in FINGERPRINTS) f else "chrome"
    }

    private fun tls(
        security: String?, sni: String?, alpn: String?, fp: String?, insecure: Boolean,
        fallbackName: String = "", pbk: String = "", sid: String = "",
    ): JSONObject? {
        val sec = (security ?: "").trim().lowercase()
        if (sec.isEmpty() || sec == "none") return null
        val t = obj("enabled" to true)
        val name = (sni ?: "").trim().ifEmpty { fallbackName.trim() }
        if (name.isNotEmpty()) t.put("server_name", name)
        if (insecure) t.put("insecure", true)
        val a = splitList(alpn, Regex("[,;]"))
        if (a.isNotEmpty()) t.put("alpn", arr(a))
        val f = fingerprint(fp)
        if (sec == "reality") {
            if (pbk.isEmpty()) throw LinkError("reality needs a public key (pbk)")
            t.put("reality", obj("enabled" to true, "public_key" to pbk, "short_id" to sid))
            t.put("utls", obj("enabled" to true, "fingerprint" to (f ?: "chrome")))
        } else if (f != null) {
            t.put("utls", obj("enabled" to true, "fingerprint" to f))
        }
        return t
    }

    /**
     * WebSocket and HTTPUpgrade only speak HTTP/1.1. With "alpn=h2,http/1.1" the server picks h2 and the
     * upgrade fails (Xray / v2rayNG force http/1.1 for these transports, so such links work there).
     */
    private fun fixAlpn(out: JSONObject, net: String) {
        if (net.trim().lowercase() !in setOf("ws", "websocket", "httpupgrade")) return
        val t = out.optJSONObject("tls") ?: return
        val a = t.optJSONArray("alpn") ?: return
        val keep = ArrayList<String>()
        for (i in 0 until a.length()) a.optString(i).let { if (it.isNotEmpty() && it != "h2") keep.add(it) }
        t.put("alpn", arr(if (keep.isEmpty()) listOf("http/1.1") else keep))
    }

    /** Transport + TLS part shared by vless, new style vmess and trojan links. Returns (network, tls?). */
    private fun applyNetTls(out: JSONObject, q: Map<String, String>, defaultSecurity: String, fallbackHost: String): Pair<String, Boolean> {
        val net = q["type"] ?: q["net"] ?: "tcp"
        val host = q["host"] ?: ""
        val tr = transport(net, host, q["path"] ?: "", q["serviceName"] ?: q["service_name"] ?: "", q["headerType"] ?: "")
        if (tr != null) out.put("transport", tr)
        val t = tls(
            q["security"] ?: defaultSecurity, q["sni"] ?: q["peer"], q["alpn"], q["fp"],
            truthy(q["allowInsecure"] ?: q["insecure"]), host.ifEmpty { fallbackHost },
            q["pbk"] ?: "", q["sid"] ?: "",
        )
        if (t != null) out.put("tls", t)
        fixAlpn(out, net)
        return net.lowercase() to (t != null)
    }

    private class Res(val name: String, val out: JSONObject, val net: String, val tls: Boolean, val endpoint: Boolean = false)

    // ───────────────────────── parsers ─────────────────────────

    private fun vless(link: String): Res {
        val u = parseUrl(link)
        val (host, port) = hostPort(u)
        val q = u.q
        val enc = q["encryption"] ?: "none"
        if (enc != "" && enc != "none") throw LinkError("vless encryption is not supported")
        val out = obj("type" to "vless", "server" to host, "server_port" to port, "uuid" to (u.user ?: ""), "packet_encoding" to "xudp")
        val flow = q["flow"] ?: ""
        if (flow.startsWith("xtls-rprx-vision")) out.put("flow", "xtls-rprx-vision")
        val (net, t) = applyNetTls(out, q, "", "")
        return Res(u.frag, out, net, t)
    }

    private fun trojan(link: String): Res {
        val u = parseUrl(link)
        val (host, port) = hostPort(u)
        val pw = (u.user ?: "") + (u.pass?.let { ":$it" } ?: "")
        val out = obj("type" to "trojan", "server" to host, "server_port" to port, "password" to pw)
        val (net, t) = applyNetTls(out, u.q, "tls", host)
        return Res(u.frag, out, net, t)
    }

    private fun vmess(link: String): Res {
        val raw = link.substring("vmess://".length)
        val head = raw.substringBefore('#').substringBefore('?')
        if ('@' in head) {                       // new style: vmess://uuid@host:port?...
            val u = parseUrl(link)
            val (host, port) = hostPort(u)
            val q = u.q
            val out = obj(
                "type" to "vmess", "server" to host, "server_port" to port, "uuid" to (u.user ?: ""),
                "security" to (q["encryption"]?.ifEmpty { null } ?: "auto"), "alter_id" to (q["aid"]?.toIntOrNull() ?: 0),
                "packet_encoding" to "xudp",
            )
            val (net, t) = applyNetTls(out, q, "", "")
            return Res(u.frag, out, net, t)
        }
        val d = JSONObject(b64s(raw.substringBefore('#')))
        fun s(k: String) = d.opt(k)?.toString()?.takeIf { it != "null" } ?: ""
        val host = s("add").trim()
        val port = s("port").trim().toIntOrNull() ?: 0
        if (host.isEmpty() || port !in 1..65535) throw LinkError("address or port is missing")
        val out = obj(
            "type" to "vmess", "server" to host, "server_port" to port, "uuid" to s("id"),
            "security" to s("scy").ifEmpty { "auto" }, "alter_id" to (s("aid").toIntOrNull() ?: 0), "packet_encoding" to "xudp",
        )
        val net = s("net").ifEmpty { "tcp" }
        val h = s("host")
        val tr = transport(net, h, s("path"), if (net == "grpc") s("path") else "", s("type"))
        if (tr != null) out.put("transport", tr)
        val t = tls(s("tls"), s("sni").ifEmpty { s("peer") }, s("alpn"), s("fp"), truthy(s("allowInsecure").ifEmpty { s("allowinsecure") }), h)
        if (t != null) out.put("tls", t)
        fixAlpn(out, net)
        return Res(s("ps"), out, net.lowercase(), t != null)
    }

    private fun shadowsocks(link: String): Res {
        var body = link.substring("ss://".length)
        var name = ""
        body.indexOf('#').let { if (it >= 0) { name = dec(body.substring(it + 1)); body = body.substring(0, it) } }
        var qs = ""
        body.indexOf('?').let { if (it >= 0) { qs = body.substring(it + 1); body = body.substring(0, it) } }
        body = body.trimEnd('/')
        val ui: String
        val hostport: String
        if ('@' in body) {
            val at = body.lastIndexOf('@')
            val userinfo = dec(body.substring(0, at))
            ui = if (':' in userinfo) userinfo else b64s(userinfo)
            hostport = body.substring(at + 1)
        } else {
            val decoded = b64s(dec(body))
            val at = decoded.lastIndexOf('@')
            if (at < 0) throw LinkError("bad shadowsocks link")
            ui = decoded.substring(0, at)
            hostport = decoded.substring(at + 1)
        }
        if (':' !in ui) throw LinkError("bad shadowsocks user info")
        val method = ui.substringBefore(':')
        val password = ui.substringAfter(':')
        val host: String
        val portS: String
        if (hostport.startsWith("[")) {
            host = hostport.substring(1).substringBefore(']')
            portS = hostport.substringAfter(']').trimStart(':')
        } else {
            host = hostport.substringBeforeLast(':', "")
            portS = hostport.substringAfterLast(':', "")
        }
        val port = portS.toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) throw LinkError("address or port is missing")
        val out = obj("type" to "shadowsocks", "server" to host, "server_port" to port, "method" to method, "password" to password)
        val plugin = qs.split('&').firstOrNull { it.startsWith("plugin=") }?.substring(7)?.let { dec(it) } ?: ""
        if (plugin.isNotEmpty()) {
            var pname = plugin.substringBefore(';')
            val popts = plugin.substringAfter(';', "")
            if (pname == "simple-obfs" || pname == "obfs") pname = "obfs-local"
            if (pname != "obfs-local" && pname != "v2ray-plugin") throw LinkError("unsupported ss plugin: $pname")
            out.put("plugin", pname)
            out.put("plugin_opts", popts)
        }
        return Res(name, out, "tcp", false)
    }

    private fun hysteria2(link: String): Res {
        val u = parseUrl(link)
        val q = u.q
        // hysteria2://pw@host:443,5000-6000?...  (port hopping)
        val ports = u.portRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val first = ports.firstOrNull { it.all { c -> c.isDigit() } }?.toIntOrNull() ?: ports.firstOrNull()?.substringBefore('-')?.toIntOrNull()
        val range = ports.filter { '-' in it }.map { it.replace('-', ':') } + (q["mport"]?.split(',')?.map { it.trim().replace('-', ':') }?.filter { it.isNotEmpty() } ?: emptyList())
        if (u.host.isEmpty() || first == null || first !in 1..65535) throw LinkError("address or port is missing")
        val pw = (u.user ?: "") + (u.pass?.let { ":$it" } ?: "")
        val t = obj("enabled" to true)
        (q["sni"] ?: q["peer"])?.takeIf { it.isNotEmpty() }?.let { t.put("server_name", it) }
        if (truthy(q["insecure"] ?: q["allowInsecure"])) t.put("insecure", true)
        splitList(q["alpn"], Regex("[,;]")).takeIf { it.isNotEmpty() }?.let { t.put("alpn", arr(it)) }
        val out = obj("type" to "hysteria2", "server" to u.host, "server_port" to first, "password" to pw, "tls" to t)
        if (range.isNotEmpty()) out.put("server_ports", arr(range))
        if ((q["obfs"] ?: "") == "salamander") out.put("obfs", obj("type" to "salamander", "password" to (q["obfs-password"] ?: "")))
        q["up"]?.filter { it.isDigit() }?.toIntOrNull()?.let { out.put("up_mbps", it) }
        q["down"]?.filter { it.isDigit() }?.toIntOrNull()?.let { out.put("down_mbps", it) }
        return Res(u.frag, out, "quic", true)
    }

    private fun hysteria1(link: String): Res {
        val u = parseUrl(link)
        val (host, port) = hostPort(u)
        val q = u.q
        val t = obj("enabled" to true)
        (q["peer"] ?: q["sni"])?.takeIf { it.isNotEmpty() }?.let { t.put("server_name", it) }
        if (truthy(q["insecure"])) t.put("insecure", true)
        splitList(q["alpn"], Regex("[,;]")).takeIf { it.isNotEmpty() }?.let { t.put("alpn", arr(it)) }
        val out = obj(
            "type" to "hysteria", "server" to host, "server_port" to port, "tls" to t,
            "up_mbps" to (q["upmbps"]?.toIntOrNull() ?: 100), "down_mbps" to (q["downmbps"]?.toIntOrNull() ?: 100),
        )
        (q["auth"] ?: u.user)?.takeIf { it.isNotEmpty() }?.let { out.put("auth_str", it) }
        q["obfsParam"]?.takeIf { it.isNotEmpty() }?.let { out.put("obfs", it) }
        return Res(u.frag, out, "quic", true)
    }

    private fun tuic(link: String): Res {
        val u = parseUrl(link)
        val (host, port) = hostPort(u)
        val q = u.q
        val t = obj("enabled" to true)
        q["sni"]?.takeIf { it.isNotEmpty() }?.let { t.put("server_name", it) }
        splitList(q["alpn"], Regex(",")).takeIf { it.isNotEmpty() }?.let { t.put("alpn", arr(it)) }
        if (truthy(q["allow_insecure"] ?: q["insecure"] ?: q["allowInsecure"])) t.put("insecure", true)
        val out = obj(
            "type" to "tuic", "server" to host, "server_port" to port, "uuid" to (u.user ?: ""),
            "password" to (u.pass ?: ""), "congestion_control" to (q["congestion_control"] ?: "bbr"), "tls" to t,
        )
        q["udp_relay_mode"]?.takeIf { it.isNotEmpty() }?.let { out.put("udp_relay_mode", it) }
        return Res(u.frag, out, "quic", true)
    }

    private fun wireguard(link: String): Res {
        val u = parseUrl(link)
        val (host, port) = hostPort(u)
        val q = u.q
        val priv = u.user ?: q["privatekey"] ?: q["secretKey"] ?: ""
        val pub = q["publickey"] ?: q["publicKey"] ?: q["peer_public_key"] ?: ""
        if (priv.isEmpty() || pub.isEmpty()) throw LinkError("wireguard needs a private and a public key")
        val addr = splitList(q["address"] ?: q["ip"], Regex("[,\\s]+")).map { if ('/' in it) it else if (':' in it) "$it/128" else "$it/32" }
        val peer = obj(
            "address" to host, "port" to port, "public_key" to pub,
            "allowed_ips" to arr(listOf("0.0.0.0/0", "::/0")),
        )
        (q["presharedkey"] ?: q["preSharedKey"])?.takeIf { it.isNotEmpty() }?.let { peer.put("pre_shared_key", it) }
        q["reserved"]?.let { r ->
            val nums = splitList(r, Regex("[,\\s]+")).mapNotNull { it.toIntOrNull() }
            if (nums.size == 3) peer.put("reserved", JSONArray(nums))
        }
        val out = obj(
            "type" to "wireguard", "address" to arr(addr.ifEmpty { listOf("172.16.0.2/32") }),
            "private_key" to priv, "peers" to JSONArray().put(peer), "mtu" to (q["mtu"]?.toIntOrNull() ?: 1280),
        )
        return Res(u.frag, out, "udp", false, endpoint = true)
    }

    private fun socks(link: String): Res {
        val u = parseUrl(link)
        val (host, port) = hostPort(u)
        val out = obj("type" to "socks", "server" to host, "server_port" to port, "version" to "5")
        val rawUser = u.user
        if (rawUser != null) {
            var user: String = rawUser
            var pw = u.pass ?: ""
            if (u.pass == null) {
                val d = runCatching { b64s(rawUser) }.getOrNull()
                if (d != null && ':' in d) { user = d.substringBefore(':'); pw = d.substringAfter(':') }
            }
            out.put("username", user)
            out.put("password", pw)
        }
        return Res(u.frag, out, "tcp", false)
    }

    /** A raw sing-box outbound pasted as JSON, kept as singbox://<base64>. */
    private fun rawJson(link: String): Res {
        val o = JSONObject(b64s(link.substring("singbox://".length)))
        val type = o.optString("type")
        if (type.isEmpty()) throw LinkError("the JSON has no outbound type")
        o.remove("tag")
        val ep = type == "wireguard" && o.has("peers")
        val host = o.optString("server").ifEmpty { o.optJSONArray("peers")?.optJSONObject(0)?.optString("address") ?: "" }
        if (!ep && host.isEmpty()) throw LinkError("address or port is missing")
        return Res(o.optString("name").also { o.remove("name") }, o, "json", o.has("tls"), endpoint = ep)
    }

    /** Wraps pasted outbound JSON into a link, or null when the text is not such JSON. */
    fun jsonToLink(text: String): String? = try {
        val o = JSONObject(text.trim())
        if (o.optString("type").isEmpty()) null
        else "singbox://" + Base64.encodeToString(o.toString().toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    } catch (_: Exception) {
        null
    }

    // ───────────────────────── public API ─────────────────────────

    /** Throws [LinkError] for anything that cannot be used. */
    fun parse(linkIn: String): ParsedLink {
        val link = linkIn.trim()
        val scheme = if ("://" in link) link.substringBefore("://").lowercase() else ""
        val r = try {
            when (scheme) {
                "vless" -> vless(link)
                "vmess" -> vmess(link)
                "trojan" -> trojan(link)
                "ss" -> shadowsocks(link)
                "hysteria2", "hy2" -> hysteria2(link)
                "hysteria" -> hysteria1(link)
                "tuic" -> tuic(link)
                "wireguard", "wg" -> wireguard(link)
                "socks5", "socks" -> socks(link)
                "singbox" -> rawJson(link)
                else -> throw LinkError("unknown link type")
            }
        } catch (e: LinkError) {
            throw e
        } catch (e: Exception) {
            throw LinkError("bad link: ${e.message}")
        }
        val type = r.out.optString("type")
        val host = r.out.optString("server").ifEmpty { r.out.optJSONArray("peers")?.optJSONObject(0)?.optString("address") ?: "" }
        val port = if (r.out.has("server_port")) r.out.optInt("server_port") else r.out.optJSONArray("peers")?.optJSONObject(0)?.optInt("port") ?: 0
        val name = r.name.trim().ifEmpty { if (host.isNotEmpty()) "$host:$port" else type }
        var kind = type + (if (r.net in setOf("tcp", "quic", "udp", "json")) "" else "/" + r.net) +
            (if (r.tls && type in setOf("vless", "vmess", "trojan")) "+tls" else "")
        if (r.out.optJSONObject("tls")?.has("reality") == true) kind = kind.replace("+tls", "+reality")
        val udp = type in setOf("hysteria2", "hysteria", "tuic", "wireguard")
        return ParsedLink(name, r.out, r.endpoint, kind, udp)
    }

    /** Every share link inside [text]; a base64 subscription body is decoded first. */
    fun extract(text: String): List<String> {
        var t = text.trim()
        if (t.isNotEmpty() && LINK_RE.find(t.take(2000)) == null) {
            runCatching {
                val d = String(b64d(t), Charsets.UTF_8)
                if (LINK_RE.containsMatchIn(d)) t = d
            }
        }
        val seen = LinkedHashSet<String>()
        for (m in LINK_RE.findAll(t)) seen.add(m.value.trimEnd(',', ';'))
        return seen.toList()
    }
}
