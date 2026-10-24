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
import androidx.compose.ui.Modifier
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

/** State of the manual form: every field by key, plus the current error. */
private class FormState {
    val f = mutableStateMapOf<String, String>()
    var err by mutableStateOf<String?>(null)
    fun v(k: String, d: String): String = f[k] ?: d
    fun put(k: String, value: String) { f[k] = value; err = null }
}

@Composable
private fun FText(st: FormState, label: String, k: String, hint: String) {
    TextRow(label, st.v(k, ""), hint) { st.put(k, it) }
}

@Composable
private fun FChoice(st: FormState, label: String, k: String, opts: List<String>) {
    Label(label)
    Chips(opts.map { it to it }, st.v(k, opts[0]), scroll = true) { st.put(k, it) }
}

@Composable
private fun FSwitch(st: FormState, label: String, k: String) {
    SwitchRow(label, st.v(k, "0") == "1") { st.put(k, if (it) "1" else "0") }
}

@Composable
private fun TransportFields(st: FormState) {
    FChoice(st, "Transport", "net", listOf("tcp", "ws", "grpc", "httpupgrade", "http"))
    val net = st.v("net", "tcp")
    if (net == "ws" || net == "httpupgrade" || net == "http") {
        FText(st, "Host", "wshost", "")
        FText(st, "Path", "path", "/")
    }
    if (net == "grpc") FText(st, "Service name", "service", "")
}

@Composable
private fun SecurityFields(st: FormState, opts: List<String>) {
    FChoice(st, "Security", "sec", opts)
    val sec = st.v("sec", opts[0])
    if (sec != "none") {
        FText(st, "SNI", "sni", "")
        FChoice(st, "Fingerprint", "fp", FPS)
        FText(st, "ALPN", "alpn", "h2,http/1.1")
        FSwitch(st, "Allow insecure", "insecure")
    }
    if (sec == "reality") {
        FText(st, "Public key (pbk)", "pbk", "")
        FText(st, "Short ID (sid)", "sid", "")
    }
}

/** Manual entry: a form for every supported protocol, or free text (share links, subscription URL, sing-box JSON). */
@Composable
fun ManualDialog(onDismiss: () -> Unit, onOk: (String) -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf("vless") }
    val st = remember { FormState() }
    var text by remember { mutableStateOf("") }
    val missing = stringResource(R.string.manual_missing)
    val protoOptions = PROTOS.map { it.first to if (it.first == "text") stringResource(R.string.manual_text) else it.second }
    val pass = stringResource(R.string.manual_pass)
    val user = stringResource(R.string.manual_user)

    AlertDialog(
        onDismissRequest = onDismiss, shape = RoundedCornerShape(28.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(stringResource(R.string.add_manual), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Chips(protoOptions, mode, scroll = true) { mode = it; st.err = null }
                if (mode == "text") {
                    Hint(stringResource(R.string.add_manual_hint))
                    TextRow("", text, "", false, false) { text = it; st.err = null }
                    TonalPill(Ico.CLIP, stringResource(R.string.paste)) { text = Util.paste(ctx) }
                } else {
                    FText(st, stringResource(R.string.name), "name", "")
                    FText(st, stringResource(R.string.manual_address), "host", "")
                    TextRow(stringResource(R.string.manual_port), st.v("port", "")) { st.put("port", it.filter { c -> c.isDigit() }.take(5)) }
                    when (mode) {
                        "vless" -> {
                            FText(st, "UUID", "uuid", "")
                            FChoice(st, "Flow", "flow", listOf("none", "xtls-rprx-vision"))
                            TransportFields(st)
                            SecurityFields(st, listOf("none", "tls", "reality"))
                        }
                        "vmess" -> {
                            FText(st, "UUID", "uuid", "")
                            FChoice(st, "Cipher", "cipher", listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero"))
                            FText(st, "Alter ID", "aid", "0")
                            TransportFields(st)
                            SecurityFields(st, listOf("none", "tls"))
                        }
                        "trojan" -> {
                            FText(st, pass, "password", "")
                            TransportFields(st)
                            SecurityFields(st, listOf("tls", "none", "reality"))
                        }
                        "ss" -> {
                            FChoice(st, "Method", "method", SS_METHODS)
                            FText(st, pass, "password", "")
                            FChoice(st, "Plugin", "plugin", listOf("none", "obfs-local", "v2ray-plugin"))
                            if (st.v("plugin", "none") != "none") FText(st, "Plugin options", "popts", "obfs=http;obfs-host=example.com")
                        }
                        "hy2" -> {
                            FText(st, pass, "password", "")
                            FText(st, "SNI", "sni", "")
                            FText(st, "ALPN", "alpn", "h3")
                            FSwitch(st, "Allow insecure", "insecure")
                            FText(st, "Obfs password (salamander)", "obfspw", "")
                            FText(st, "Port hopping", "mport", "5000-6000")
                            FText(st, "Up Mbps", "up", "")
                            FText(st, "Down Mbps", "down", "")
                        }
                        "hy1" -> {
                            FText(st, "Auth", "auth", "")
                            FText(st, "SNI", "sni", "")
                            FText(st, "ALPN", "alpn", "h3")
                            FSwitch(st, "Allow insecure", "insecure")
                            FText(st, "Obfs", "obfspw", "")
                            FText(st, "Up Mbps", "up", "100")
                            FText(st, "Down Mbps", "down", "100")
                        }
                        "tuic" -> {
                            FText(st, "UUID", "uuid", "")
                            FText(st, pass, "password", "")
                            FChoice(st, "Congestion control", "cc", listOf("bbr", "cubic", "new_reno"))
                            FChoice(st, "UDP relay mode", "udprelay", listOf("default", "native", "quic"))
                            FText(st, "SNI", "sni", "")
                            FText(st, "ALPN", "alpn", "h3")
                            FSwitch(st, "Allow insecure", "insecure")
                        }
                        "wg" -> {
                            FText(st, "Private key", "privkey", "")
                            FText(st, "Peer public key", "pubkey", "")
                            FText(st, "Pre-shared key", "psk", "")
                            FText(st, "Local address", "wgaddr", "172.16.0.2/32")
                            FText(st, "Reserved", "reserved", "1,2,3")
                            FText(st, "MTU", "mtu", "1280")
                        }
                        "socks" -> {
                            FText(st, user, "user", "")
                            FText(st, pass, "pass", "")
                        }
                        "http" -> {
                            FText(st, user, "user", "")
                            FText(st, pass, "pass", "")
                            FSwitch(st, stringResource(R.string.manual_tls), "tls")
                        }
                    }
                }
                val e = st.err
                if (e != null) Hint(e, true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (mode == "text") {
                    onOk(text)
                } else {
                    val built: String? = try {
                        ManualBuilder.build(mode, st.f)
                    } catch (e: IllegalArgumentException) {
                        st.err = missing
                        null
                    }
                    if (built != null) {
                        val bad: String? = try {
                            Links.parse(built)
                            null
                        } catch (e: LinkError) {
                            e.message ?: missing
                        }
                        if (bad != null) st.err = bad else onOk(built)
                    }
                }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
