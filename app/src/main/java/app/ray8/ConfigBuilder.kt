package app.ray8

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Builds the sing-box configuration that libbox runs. */
object ConfigBuilder {
    const val SELECTOR = "proxy"
    const val AUTO = "auto"

    class Built(val json: String, val activeTag: String, val usedRuleSets: Boolean, val tags: Set<String>)

    class NoConfig : Exception("no usable config")

    private class Prepared(val node: Node, val parsed: ParsedLink, val json: JSONObject)

    // ───────────────────────── rule files ─────────────────────────

    fun ruleDir(ctx: Context) = File(ctx.filesDir, "rules").apply { mkdirs() }

    fun coreLogFile(ctx: Context) = File(ctx.filesDir, "core.log")

    fun ruleFile(ctx: Context, name: String) = File(ruleDir(ctx), name)

    fun ruleSetsReady(ctx: Context, geo: String): Boolean {
        val (ip, site) = Geo.files(geo)
        return ip.isNotEmpty() && (ruleFile(ctx, ip).length() > 0 || ruleFile(ctx, site).length() > 0)
    }

    // ───────────────────────── outbounds ─────────────────────────

    private fun tune(s: Settings, o: JSONObject) {
        val type = o.optString("type")
        val tls = o.optJSONObject("tls")
        if (s.fragment && tls != null && tls.optBoolean("enabled") && !tls.has("reality") && type in setOf("vless", "vmess", "trojan")) {
            tls.put("fragment", true)
            if (s.recordFragment) tls.put("record_fragment", true)
        }
        if (s.mux && type in setOf("vless", "vmess", "trojan", "shadowsocks") && !o.has("flow")) {
            o.put("multiplex", JSONObject().put("enabled", true).put("protocol", s.muxProtocol).put("max_connections", s.muxN()).put("padding", false))
        }
    }

    private fun prepare(s: Settings, nodes: List<Node>): List<Prepared> {
        val out = ArrayList<Prepared>()
        val ids = nodes.map { it.id }.toHashSet()
        for (n in nodes) {
            val p = try {
                Links.parse(n.link)
            } catch (e: LinkError) {
                Engine.log("Skipped \"${n.name}\": ${e.message}")
                continue
            }
            val o = JSONObject(p.json.toString())
            o.put("tag", n.id)
            tune(s, o)
            if (n.detour.isNotEmpty() && n.detour != n.id && n.detour in ids) o.put("detour", n.detour)
            out.add(Prepared(n, p, o))
        }
        // A chain whose first hop failed to parse must not point at a missing tag.
        val okIds = out.map { it.node.id }.toHashSet()
        for (p in out) if (p.json.has("detour") && p.json.getString("detour") !in okIds) p.json.remove("detour")
        return out
    }

    /** The configs to put into the config file: all of them plus whatever the chains need. */
    private fun withDetours(nodes: List<Node>): List<Node> {
        val all = NodeStore.nodes.value.associateBy { it.id }
        val out = LinkedHashMap<String, Node>()
        for (n in nodes) out[n.id] = n
        var changed = true
        var guard = 0
        while (changed && guard++ < 8) {
            changed = false
            for (n in out.values.toList()) {
                val d = n.detour
                if (d.isNotEmpty() && d !in out) all[d]?.let { out[d] = it; changed = true }
            }
        }
        return out.values.toList()
    }

    // ───────────────────────── DNS ─────────────────────────

    private val DNS_URL = Regex("^(https|tls|tcp|udp|h3|quic)://(.*)$", RegexOption.IGNORE_CASE)

    private fun dnsServer(tag: String, specIn: String, detour: String?, resolver: String?): JSONObject {
        val spec = specIn.trim().ifEmpty { "https://1.1.1.1/dns-query" }
        var type = if (detour != null) "tcp" else "udp"
        var rest = spec
        DNS_URL.find(spec)?.let { type = it.groupValues[1].lowercase(); rest = it.groupValues[2] }
        var path: String? = null
        rest.indexOf('/').let { if (it >= 0) { path = rest.substring(it); rest = rest.substring(0, it) } }
        var host = rest
        var port: Int? = null
        if (rest.startsWith("[") && ']' in rest) {
            host = rest.substring(1, rest.indexOf(']'))
            port = rest.substringAfter("]:", "").toIntOrNull()
        } else if (rest.count { it == ':' } == 1) {
            host = rest.substringBefore(':')
            port = rest.substringAfter(':').toIntOrNull()
        }
        val o = JSONObject().put("type", type).put("tag", tag).put("server", host)
        if (port != null) o.put("server_port", port)
        if (type == "https" || type == "h3") o.put("path", path ?: "/dns-query")
        if (detour != null) o.put("detour", detour)
        if (resolver != null && !Net.isIp(host)) o.put("domain_resolver", resolver)
        return o
    }

    // ───────────────────────── user rules ─────────────────────────

    private class Lines {
        val domain = ArrayList<String>()
        val suffix = ArrayList<String>()
        val keyword = ArrayList<String>()
        val regex = ArrayList<String>()
        val cidr = ArrayList<String>()
        val isEmpty get() = domain.isEmpty() && suffix.isEmpty() && keyword.isEmpty() && regex.isEmpty() && cidr.isEmpty()

        /** One rule per kind of match (inside one rule the kinds would be combined with AND). */
        fun rules(extra: JSONObject.() -> Unit): List<JSONObject> {
            val out = ArrayList<JSONObject>()
            fun add(key: String, list: List<String>) {
                if (list.isNotEmpty()) out.add(JSONObject().put(key, JSONArray(list)).also { it.extra() })
            }
            add("domain", domain); add("domain_suffix", suffix); add("domain_keyword", keyword)
            add("domain_regex", regex); add("ip_cidr", cidr)
            return out
        }

        val hasDomains get() = domain.isNotEmpty() || suffix.isNotEmpty() || keyword.isNotEmpty() || regex.isNotEmpty()
    }

    private fun parseLines(text: String): Lines {
        val l = Lines()
        for (raw in text.split('\n', ',', ';', ' ', '\t')) {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val low = t.lowercase()
            when {
                low.startsWith("full:") -> l.domain.add(t.substring(5))
                low.startsWith("domain:") -> l.suffix.add(t.substring(7))
                low.startsWith("keyword:") -> l.keyword.add(t.substring(8))
                low.startsWith("regexp:") -> l.regex.add(t.substring(7))
                low.startsWith("geoip:") || low.startsWith("geosite:") || low.startsWith("ext:") -> {}
                t.startsWith("*.") -> l.suffix.add(t.substring(2))
                '/' in t && Net.isIp(t.substringBefore('/')) -> l.cidr.add(t)
                Net.isIp(t) -> l.cidr.add(if (':' in t) "$t/128" else "$t/32")
                else -> l.suffix.add(t.trimStart('.'))
            }
        }
        return l
    }

    private fun routeTo(outbound: String): JSONObject.() -> Unit = { put("action", "route"); put("outbound", outbound) }

    // ───────────────────────── main config ─────────────────────────

    /**
     * @param tun add a TUN inbound (VPN mode); the descriptor itself is handed over by the service
     * @param nodes every config that can be selected (all of them, so the running core can switch live)
     */
    fun build(
        ctx: Context, s: Settings, nodes: List<Node>, activeId: String, tun: Boolean,
        clashPort: Int, secret: String, internalPort: Int, useRuleSets: Boolean,
    ): Built {
        val prepared = prepare(s, withDetours(nodes))
        if (prepared.isEmpty()) throw NoConfig()
        runCatching { coreLogFile(ctx).delete() }
        val active = prepared.firstOrNull { it.node.id == activeId } ?: prepared.first()

        val outbounds = JSONArray()
        val endpoints = JSONArray()
        val tags = ArrayList<String>()
        for (p in prepared) {
            (if (p.parsed.endpoint) endpoints else outbounds).put(p.json)
            tags.add(p.node.id)
        }

        val selectable = ArrayList<String>()
        if (s.autoSelect) {
            val scoped = if (s.autoScope != "all") prepared.filter { it.node.sub == s.autoScope } else prepared
            val base = scoped.ifEmpty { prepared }
            val pool = base
                .filter { it.node.delay != -1 }
                .sortedBy { it.node.delay ?: Int.MAX_VALUE }
                .take(s.autoN())
                .ifEmpty { base.take(s.autoN()) }
            outbounds.put(
                JSONObject().put("type", "urltest").put("tag", AUTO)
                    .put("outbounds", JSONArray(pool.map { it.node.id }))
                    .put("url", s.testUrlOrDefault()).put("interval", "5m").put("tolerance", 50)
            )
            selectable.add(AUTO)
        }
        selectable.addAll(tags)
        outbounds.put(
            JSONObject().put("type", "selector").put("tag", SELECTOR)
                .put("outbounds", JSONArray(selectable))
                .put("default", if (s.autoSelect) AUTO else active.node.id)
                .put("interrupt_exist_connections", true)
        )
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        // ---- user rules
        val block = parseLines(s.routeBlock)
        val viaProxy = parseLines(s.routeProxy)
        val direct = parseLines(s.routeDirect)

        // ---- rule sets (country lists, downloaded by the app, never by sing-box)
        val ruleSets = JSONArray()
        val geoTags = ArrayList<String>()
        val geoSiteTags = ArrayList<String>()
        if (s.bypassGeo != "none" && useRuleSets) {
            val (ipFile, siteFile) = Geo.files(s.bypassGeo)
            for ((file, isSite) in listOf(ipFile to false, siteFile to true)) {
                val f = ruleFile(ctx, file)
                if (file.isEmpty() || f.length() == 0L) continue
                val tag = file.removeSuffix(".srs")
                ruleSets.put(JSONObject().put("type", "local").put("tag", tag).put("format", "binary").put("path", f.path))
                geoTags.add(tag)
                if (isSite) geoSiteTags.add(tag)
            }
        }

        // ---- DNS
        val dnsServers = JSONArray()
            .put(dnsServer("dns-remote", s.remoteDns, SELECTOR, "dns-direct"))
            .put(dnsServer("dns-direct", Net.directDns(ctx, s).let { if (Net.isIp(it) || DNS_URL.containsMatchIn(it)) it else "1.1.1.1" }, null, null))
        val dnsRules = JSONArray()
        if (direct.hasDomains) for (r in direct.rules { put("action", "route"); put("server", "dns-direct") }.filter { !it.has("ip_cidr") }) dnsRules.put(r)
        if (s.bypassGeo != "none") dnsRules.put(JSONObject().put("domain_suffix", JSONArray(listOf(s.bypassGeo))).put("action", "route").put("server", "dns-direct"))
        if (geoSiteTags.isNotEmpty()) dnsRules.put(JSONObject().put("rule_set", JSONArray(geoSiteTags)).put("action", "route").put("server", "dns-direct"))
        val dns = JSONObject().put("servers", dnsServers).put("rules", dnsRules).put("final", "dns-remote")
            .put("strategy", if (s.ipv6) "prefer_ipv4" else "ipv4_only")

        // ---- inbounds
        val inbounds = JSONArray()
        if (tun) {
            val addr = JSONArray().put("198.18.0.1/32")
            if (s.ipv6) addr.put("fc00::1/128")
            inbounds.put(
                JSONObject().put("type", "tun").put("tag", "tun-in").put("address", addr).put("mtu", s.mtu())
                    .put("auto_route", true).put("stack", s.tunStack)
            )
        }
        val mixed = JSONObject().put("type", "mixed").put("tag", "mixed-in")
            .put("listen", if (s.allowLan) "0.0.0.0" else "127.0.0.1").put("listen_port", s.socksPort)
        if (s.proxyUser.isNotEmpty() && s.proxyPass.isNotEmpty()) {
            mixed.put("users", JSONArray().put(JSONObject().put("username", s.proxyUser).put("password", s.proxyPass)))
        }
        inbounds.put(mixed)
        inbounds.put(JSONObject().put("type", "socks").put("tag", "internal").put("listen", "127.0.0.1").put("listen_port", internalPort))

        // ---- routing rules
        val rules = JSONArray()
        if (s.sniffing) rules.put(JSONObject().put("action", "sniff"))
        rules.put(JSONObject().put("port", 53).put("action", "hijack-dns"))
        if (s.sniffing) rules.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        // QUIC is refused at once, so apps fall back to TCP immediately instead of waiting for a
        // UDP relay that a TCP based protocol cannot give them.
        if (s.blockQuic && !active.parsed.udp && !s.autoSelect) {
            rules.put(JSONObject().put("network", "udp").put("port", 443).put("action", "reject"))
        }
        for (r in block.rules { put("action", "reject") }) rules.put(r)
        for (r in viaProxy.rules(routeTo(SELECTOR))) rules.put(r)
        if (s.bypassLan) {
            rules.put(JSONObject().put("ip_is_private", true).put("action", "route").put("outbound", "direct"))
            rules.put(JSONObject().put("domain_suffix", JSONArray(listOf("local", "lan"))).put("action", "route").put("outbound", "direct"))
        }
        for (r in direct.rules(routeTo("direct"))) rules.put(r)
        if (s.bypassGeo != "none") {
            rules.put(JSONObject().put("domain_suffix", JSONArray(listOf(s.bypassGeo))).put("action", "route").put("outbound", "direct"))
            if (geoTags.isNotEmpty()) rules.put(JSONObject().put("rule_set", JSONArray(geoTags)).put("action", "route").put("outbound", "direct"))
        }

        val route = JSONObject().put("rules", rules).put("final", SELECTOR)
            .put("auto_detect_interface", true).put("default_domain_resolver", "dns-direct")
        if (ruleSets.length() > 0) route.put("rule_set", ruleSets)

        val root = JSONObject()
            .put("log", JSONObject().put("level", s.logLevel).put("timestamp", false).put("output", coreLogFile(ctx).path))
            .put("dns", dns)
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("route", route)
            .put("experimental", JSONObject().put("clash_api", JSONObject().put("external_controller", "127.0.0.1:$clashPort").put("secret", secret)))
        if (endpoints.length() > 0) root.put("endpoints", endpoints)
        return Built(root.toString(), if (s.autoSelect) AUTO else active.node.id, ruleSets.length() > 0, tags.toSet())
    }

    // ───────────────────────── test config ─────────────────────────

    /** A config without any inbound: only the clash API, used to measure delays while the VPN is off. */
    fun buildTest(ctx: Context, s: Settings, nodes: List<Node>, clashPort: Int, secret: String): Built {
        val prepared = prepare(s, withDetours(nodes))
        if (prepared.isEmpty()) throw NoConfig()
        val outbounds = JSONArray()
        val endpoints = JSONArray()
        for (p in prepared) (if (p.parsed.endpoint) endpoints else outbounds).put(p.json)
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
        val directDns = Net.directDns(ctx, s).let { if (Net.isIp(it) || DNS_URL.containsMatchIn(it)) it else "1.1.1.1" }
        val root = JSONObject()
            .put("log", JSONObject().put("level", "error"))
            .put("dns", JSONObject().put("servers", JSONArray().put(dnsServer("dns-direct", directDns, null, null))).put("final", "dns-direct")
                .put("strategy", "ipv4_only"))
            .put("inbounds", JSONArray())
            .put("outbounds", outbounds)
            .put("route", JSONObject().put("final", "direct").put("auto_detect_interface", true).put("default_domain_resolver", "dns-direct"))
            .put("experimental", JSONObject().put("clash_api", JSONObject().put("external_controller", "127.0.0.1:$clashPort").put("secret", secret)))
        if (endpoints.length() > 0) root.put("endpoints", endpoints)
        return Built(root.toString(), "", false, prepared.map { it.node.id }.toSet())
    }
}
