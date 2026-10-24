package app.ray8

import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private val PROTOS = listOf(
    "vless" to "VLESS", "vmess" to "VMess", "trojan" to "Trojan", "ss" to "Shadowsocks",
    "hy2" to "Hysteria2", "hy1" to "Hysteria", "tuic" to "TUIC", "wg" to "WireGuard",
    "socks" to "SOCKS5", "http" to "HTTP", "text" to "Link / JSON",
)
private val SS_METHODS = listOf(
    "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
    "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305", "none",
)
private val FPS = listOf("none", "chrome", "firefox", "safari", "ios", "android", "edge", "random")

/** Builds a share link (or sing-box outbound JSON wrapped as a link) from the manual form. */
private object ManualBuilder {
    fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private fun hostFmt(h: String) = if (':' in h && !h.startsWith("[")) "[$h]" else h

    private fun query(vararg p: Pair<String, String?>) =
        p.filter { !it.second.isNullOrEmpty() }.joinToString("&") { it.first + "=" + enc(it.second!!) }
            .let { if (it.isEmpty()) "" else "?$it" }

    private fun frag(name: String) = if (name.isBlank()) "" else "#" + enc(name.trim())

    /** Returns the link, or throws IllegalArgumentException with a short message. */
    fun build(proto: String, f: Map<String, String>): String {
        fun g(k: String) = (f[k] ?: "").trim()
        val host = g("host")
        val port = g("port").toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) throw IllegalArgumentException("addr")
        fun need(vararg ks: String) { if (ks.any { g(it).isEmpty() }) throw IllegalArgumentException("need") }
        val hp = "${hostFmt(host)}:$port"
        val name = frag(f["name"] ?: "")
        val on = { k: String -> if (g(k) == "1") "1" else null }

        fun netTls(defaultSec: String): Array<Pair<String, String?>> {
            val net = g("net").ifEmpty { "tcp" }
            val sec = g("sec").ifEmpty { defaultSec }
            val tls = sec != "none"
            return arrayOf<Pair<String, String?>>(
                "type" to net.takeIf { it != "tcp" },
                "host" to g("wshost").takeIf { net in setOf("ws", "httpupgrade", "http") },
                "path" to g("path").takeIf { net in setOf("ws", "httpupgrade", "http") },
                "serviceName" to g("service").takeIf { net == "grpc" },
                "security" to sec,
                "sni" to g("sni").takeIf { tls },
                "fp" to g("fp").takeIf { tls && it != "none" },
                "alpn" to g("alpn").takeIf { tls },
                "allowInsecure" to on("insecure").takeIf { tls },
                "pbk" to g("pbk").takeIf { sec == "reality" },
                "sid" to g("sid").takeIf { sec == "reality" },
            )
        }

        return when (proto) {
            "vless" -> {
                need("uuid")
                "vless://${enc(g("uuid"))}@$hp" + query(
                    "encryption" to "none", "flow" to g("flow").takeIf { it != "none" }, *netTls("none")
                ) + name
            }
            "vmess" -> {
                need("uuid")
                "vmess://${enc(g("uuid"))}@$hp" + query(
                    "encryption" to g("cipher").ifEmpty { "auto" }, "aid" to g("aid").takeIf { it.isNotEmpty() && it != "0" }, *netTls("none")
                ) + name
            }
            "trojan" -> {
                need("password")
                "trojan://${enc(g("password"))}@$hp" + query(*netTls("tls")) + name
            }
            "ss" -> {
                need("password")
                val m = g("method").ifEmpty { SS_METHODS[0] }
                val ui = Base64.encodeToString("$m:${g("password")}".toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
                val pl = g("plugin")
                val plugin = if (pl.isEmpty() || pl == "none") null else pl + (if (g("popts").isNotEmpty()) ";" + g("popts") else "")
                "ss://$ui@$hp" + query("plugin" to plugin) + name
            }
            "hy2" -> {
                need("password")
                val obfs = g("obfspw")
                "hysteria2://${enc(g("password"))}@$hp" + query(
                    "sni" to g("sni"), "insecure" to on("insecure"), "alpn" to g("alpn"),
                    "obfs" to if (obfs.isNotEmpty()) "salamander" else null, "obfs-password" to obfs,
                    "up" to g("up"), "down" to g("down"), "mport" to g("mport"),
                ) + name
            }
            "hy1" -> {
                "hysteria://$hp" + query(
                    "auth" to g("auth"), "peer" to g("sni"), "insecure" to on("insecure"), "alpn" to g("alpn"),
                    "upmbps" to g("up"), "downmbps" to g("down"), "obfsParam" to g("obfspw"),
                ) + name
            }
            "tuic" -> {
                need("uuid", "password")
                "tuic://${enc(g("uuid"))}:${enc(g("password"))}@$hp" + query(
                    "sni" to g("sni"), "alpn" to g("alpn"), "allow_insecure" to on("insecure"),
                    "congestion_control" to g("cc").ifEmpty { "bbr" }, "udp_relay_mode" to g("udprelay").takeIf { it != "default" },
                ) + name
            }
            "wg" -> {
                need("privkey", "pubkey")
                "wireguard://${enc(g("privkey"))}@$hp" + query(
                    "publickey" to g("pubkey"), "presharedkey" to g("psk"), "address" to g("wgaddr"),
                    "reserved" to g("reserved"), "mtu" to g("mtu"),
                ) + name
            }
            "socks" -> {
                val user = g("user")
                val auth = if (user.isNotEmpty()) enc(user) + (if (g("pass").isNotEmpty()) ":" + enc(g("pass")) else "") + "@" else ""
                "socks5://$auth$hp" + name
            }
            "http" -> {
                val o = JSONObject().put("type", "http").put("server", host).put("server_port", port)
                if (g("user").isNotEmpty()) o.put("username", g("user")).put("password", g("pass"))
                if (g("tls") == "1") o.put("tls", JSONObject().put("enabled", true).put("server_name", host))
                if ((f["name"] ?: "").isNotBlank()) o.put("name", f["name"]!!.trim())
                Links.jsonToLink(o.toString()) ?: throw IllegalArgumentException("need")
            }
            else -> throw IllegalArgumentException("need")
        }
    }
}

/** Manual entry: a form for every supported protocol, or free text (share links, subscription URL, sing-box JSON). */
@Composable
fun ManualDialog(onDismiss: () -> Unit, onOk: (String) -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf("vless") }
    val f = remember { mutableStateMapOf<String, String>() }
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val missing = stringResource(R.string.manual_missing)

    fun v(k: String, d: String = "") = f[k] ?: d
    @Composable fun T(label: String, k: String, hint: String = "") = TextRow(label, v(k), hint) { f[k] = it; err = null }
    @Composable fun Pw(label: String, k: String) = TextRow(label, v(k)) { f[k] = it; err = null }
    @Composable fun Ch(label: String, k: String, opts: List<String>, d: String = opts[0]) {
        Label(label)
        Chips(opts.map { it to it }, v(k, d), scroll = true) { f[k] = it; err = null }
    }
    @Composable fun Sw(label: String, k: String) = SwitchRow(label, v(k) == "1") { f[k] = if (it) "1" else "0"; err = null }

    @Composable fun Transport() {
        Ch("Transport", "net", listOf("tcp", "ws", "grpc", "httpupgrade", "http"))
        val net = v("net", "tcp")
        if (net in setOf("ws", "httpupgrade", "http")) { T("Host", "wshost"); T("Path", "path", "/") }
        if (net == "grpc") T("Service name", "service")
    }
    @Composable fun Security(opts: List<String>) {
        Ch("Security", "sec", opts)
        val sec = v("sec", opts[0])
        if (sec != "none") {
            T("SNI", "sni")
            Ch("Fingerprint", "fp", FPS)
            T("ALPN", "alpn", "h2,http/1.1")
            Sw("Allow insecure", "insecure")
        }
        if (sec == "reality") { T("Public key (pbk)", "pbk"); T("Short ID (sid)", "sid") }
    }

    AlertDialog(
        onDismissRequest = onDismiss, shape = RoundedCornerShape(28.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(stringResource(R.string.add_manual), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Chips(PROTOS.map { it.first to if (it.first == "text") stringResource(R.string.manual_text) else it.second }, mode, scroll = true) { mode = it; err = null }
                if (mode == "text") {
                    Hint(stringResource(R.string.add_manual_hint))
                    TextRow("", text, singleLine = false) { text = it; err = null }
                    TonalPill(Ico.CLIP, stringResource(R.string.paste)) { text = Util.paste(ctx) }
                } else {
                    T(stringResource(R.string.name), "name")
                    T(stringResource(R.string.manual_address), "host")
                    TextRow(stringResource(R.string.manual_port), v("port")) { f["port"] = it.filter { c -> c.isDigit() }.take(5); err = null }
                    when (mode) {
                        "vless" -> {
                            T("UUID", "uuid")
                            Ch("Flow", "flow", listOf("none", "xtls-rprx-vision"))
                            Transport(); Security(listOf("none", "tls", "reality"))
                        }
                        "vmess" -> {
                            T("UUID", "uuid")
                            Ch("Cipher", "cipher", listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero"))
                            T("Alter ID", "aid", "0")
                            Transport(); Security(listOf("none", "tls"))
                        }
                        "trojan" -> {
                            Pw(stringResource(R.string.manual_pass), "password")
                            Transport(); Security(listOf("tls", "none", "reality"))
                        }
                        "ss" -> {
                            Ch("Method", "method", SS_METHODS)
                            Pw(stringResource(R.string.manual_pass), "password")
                            Ch("Plugin", "plugin", listOf("none", "obfs-local", "v2ray-plugin"))
                            if (v("plugin", "none") != "none") T("Plugin options", "popts", "obfs=http;obfs-host=example.com")
                        }
                        "hy2" -> {
                            Pw(stringResource(R.string.manual_pass), "password")
                            T("SNI", "sni"); T("ALPN", "alpn", "h3"); Sw("Allow insecure", "insecure")
                            T("Obfs password (salamander)", "obfspw")
                            T("Port hopping", "mport", "5000-6000")
                            T("Up Mbps", "up"); T("Down Mbps", "down")
                        }
                        "hy1" -> {
                            T("Auth", "auth")
                            T("SNI", "sni"); T("ALPN", "alpn", "h3"); Sw("Allow insecure", "insecure")
                            T("Obfs", "obfspw")
                            T("Up Mbps", "up", "100"); T("Down Mbps", "down", "100")
                        }
                        "tuic" -> {
                            T("UUID", "uuid")
                            Pw(stringResource(R.string.manual_pass), "password")
                            Ch("Congestion control", "cc", listOf("bbr", "cubic", "new_reno"))
                            Ch("UDP relay mode", "udprelay", listOf("default", "native", "quic"))
                            T("SNI", "sni"); T("ALPN", "alpn", "h3"); Sw("Allow insecure", "insecure")
                        }
                        "wg" -> {
                            T("Private key", "privkey")
                            T("Peer public key", "pubkey")
                            T("Pre-shared key", "psk")
                            T("Local address", "wgaddr", "172.16.0.2/32")
                            T("Reserved", "reserved", "1,2,3")
                            T("MTU", "mtu", "1280")
                        }
                        "socks", "http" -> {
                            T(stringResource(R.string.manual_user), "user")
                            Pw(stringResource(R.string.manual_pass), "pass")
                            if (mode == "http") Sw(stringResource(R.string.manual_tls), "tls")
                        }
                    }
                }
                err?.let { Hint(it, error = true) }
            }
        },
        confirmButton = {
            TextButton({
                if (mode == "text") { onOk(text); return@TextButton }
                try {
                    val link = ManualBuilder.build(mode, f)
                    try { Links.parse(link) } catch (e: LinkError) { err = e.message; return@TextButton }
                    onOk(link)
                } catch (_: IllegalArgumentException) {
                    err = missing
                }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
