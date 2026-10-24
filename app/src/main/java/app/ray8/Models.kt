package app.ray8

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.Locale

const val DEFAULT_SOCKS_PORT = 10808
const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

enum class Phase { IDLE, CONNECTING, CONNECTED, RECONNECTING, STOPPING }

data class Status(val phase: Phase = Phase.IDLE, val attempt: Int = 0, val error: String? = null)

/** Process-wide connection state shared by the service, the tile and the UI. */
object Engine {
    val status = MutableStateFlow(Status())
    val pingMs = MutableStateFlow<Int?>(null)
    val rxBps = MutableStateFlow(0L)
    val txBps = MutableStateFlow(0L)
    /** Bytes moved in the current session. */
    val rxTotal = MutableStateFlow(0L)
    val txTotal = MutableStateFlow(0L)
    val connections = MutableStateFlow(0)
    val exitInfo = MutableStateFlow<String?>(null)
    /** Measured download speed through the tunnel in bytes/s (null = not measured yet). */
    val speedBps = MutableStateFlow<Long?>(null)
    val logs = MutableStateFlow<List<String>>(emptyList())
    /** Why the link last dropped (shown under the connect button); null = nothing dropped yet. */
    val lastReason = MutableStateFlow<String?>(null)
    /** True while the clash API of the running core answers (live switching, real delay, counters). */
    val apiReady = MutableStateFlow(false)
    /** Local port of a plain SOCKS5 inbound without auth, used by the app itself (probes, updates). */
    @Volatile var internalPort = 0
    /** Clash API of the running core and the configs it contains (null / empty while idle). */
    @Volatile var api: ClashApi? = null
    @Volatile var tags: Set<String> = emptySet()

    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")

    fun log(line: String) {
        val clean = ansi.replace(line, "").trimEnd()
        if (clean.isEmpty()) return
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
        logs.value = (logs.value + "$stamp  $clean").takeLast(500)
    }

    fun resetLive() {
        pingMs.value = null
        rxBps.value = 0
        txBps.value = 0
        rxTotal.value = 0
        txTotal.value = 0
        connections.value = 0
        exitInfo.value = null
        speedBps.value = null
        apiReady.value = false
        internalPort = 0
        api = null
        tags = emptySet()
    }
}

data class Settings(
    val language: String = "en",
    val theme: String = "dark",
    val dynamicColor: Boolean = false,
    val vpnMode: Boolean = true,
    val autoStart: Boolean = false,
    val keepAwake: Boolean = true,

    // Which config is used
    val autoSelect: Boolean = false,      // urltest group: always the fastest of the best configs
    val autoCount: Int = 20,              // how many configs the auto group tests
    val autoSwitch: Boolean = true,       // move to another config when the current one is dead
    val pingFails: Int = 3,               // failed probes in a row before the link counts as dead
    val pingIntervalSec: Int = 10,        // seconds between two probes
    val testUrl: String = DEFAULT_TEST_URL,
    val testTimeoutMs: Int = 8000,
    val testWorkers: Int = 8,

    // Local proxy
    val socksPort: Int = DEFAULT_SOCKS_PORT,   // mixed (SOCKS5 + HTTP) inbound
    val allowLan: Boolean = false,
    val proxyUser: String = "",
    val proxyPass: String = "",

    // TUN
    val ipv6: Boolean = false,
    val tunStack: String = "gvisor",      // gvisor | mixed | system
    val tunMtu: Int = 1500,
    val blockQuic: Boolean = true,
    val sniffing: Boolean = true,

    // DNS
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "",           // empty = the DNS server of the Wi-Fi / mobile network

    // Routing
    val bypassLan: Boolean = true,
    val bypassGeo: String = "none",       // none | ir | cn | ru
    val routeProxy: String = "",
    val routeDirect: String = "",
    val routeBlock: String = "",

    // Outbound tuning
    val mux: Boolean = false,
    val muxProtocol: String = "h2mux",
    val muxMax: Int = 4,
    val fragment: Boolean = false,        // split the TLS hello into pieces
    val recordFragment: Boolean = false,
    val logLevel: String = "warn",

    // Split tunneling
    val splitMode: String = "off",
    val splitApps: Set<String> = emptySet(),

    // Subscriptions / list
    val subAutoUpdate: Boolean = true,
    val subHours: Int = 24,
    val sortMode: String = "manual",      // manual | delay | name
)

fun Settings.pingLimit() = pingFails.coerceIn(1, 20)
fun Settings.pingEverySec() = pingIntervalSec.coerceIn(3, 120)
fun Settings.mtu() = tunMtu.coerceIn(1280, 9000)
fun Settings.workers() = testWorkers.coerceIn(1, 32)
fun Settings.timeoutMs() = testTimeoutMs.coerceIn(1000, 30000)
fun Settings.autoN() = autoCount.coerceIn(2, 100)
fun Settings.muxN() = muxMax.coerceIn(1, 32)
fun Settings.testUrlOrDefault() = testUrl.trim().ifEmpty { DEFAULT_TEST_URL }

object SettingsStore {
    private lateinit var prefs: SharedPreferences
    private val _flow = MutableStateFlow(Settings())
    val flow: StateFlow<Settings> = _flow
    val current: Settings get() = _flow.value

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _flow.value = load()
    }

    fun update(f: (Settings) -> Settings) {
        val next = sanitize(f(_flow.value))
        _flow.value = next
        save(next)
    }

    private fun sanitize(s: Settings): Settings {
        val port = if (s.socksPort in 1024..65535) s.socksPort else DEFAULT_SOCKS_PORT
        return s.copy(socksPort = port)
    }

    /** Persian UI on a Persian phone, English otherwise. */
    fun defaultLanguage(): String = if (Locale.getDefault().language == "fa") "fa" else "en"

    private fun load(): Settings {
        val d = Settings(language = defaultLanguage())
        fun str(k: String, def: String) = prefs.getString(k, def) ?: def
        fun int(k: String, def: Int) = prefs.getInt(k, def)
        fun bool(k: String, def: Boolean) = prefs.getBoolean(k, def)
        return sanitize(
            Settings(
                language = str("language", d.language),
                theme = str("theme", d.theme),
                dynamicColor = bool("dynamicColor", d.dynamicColor),
                vpnMode = bool("vpnMode", d.vpnMode),
                autoStart = bool("autoStart", d.autoStart),
                keepAwake = bool("keepAwake", d.keepAwake),
                autoSelect = bool("autoSelect", d.autoSelect),
                autoCount = int("autoCount", d.autoCount),
                autoSwitch = bool("autoSwitch", d.autoSwitch),
                pingFails = int("pingFails", d.pingFails),
                pingIntervalSec = int("pingIntervalSec", d.pingIntervalSec),
                testUrl = str("testUrl", d.testUrl),
                testTimeoutMs = int("testTimeoutMs", d.testTimeoutMs),
                testWorkers = int("testWorkers", d.testWorkers),
                socksPort = int("socksPort", d.socksPort),
                allowLan = bool("allowLan", d.allowLan),
                proxyUser = str("proxyUser", d.proxyUser),
                proxyPass = str("proxyPass", d.proxyPass),
                ipv6 = bool("ipv6", d.ipv6),
                tunStack = str("tunStack", d.tunStack).takeIf { it in setOf("gvisor", "mixed", "system") } ?: d.tunStack,
                tunMtu = int("tunMtu", d.tunMtu),
                blockQuic = bool("blockQuic", d.blockQuic),
                sniffing = bool("sniffing", d.sniffing),
                remoteDns = str("remoteDns", d.remoteDns),
                directDns = str("directDns", d.directDns),
                bypassLan = bool("bypassLan", d.bypassLan),
                bypassGeo = str("bypassGeo", d.bypassGeo).takeIf { it in Geo.codes + "none" } ?: d.bypassGeo,
                routeProxy = str("routeProxy", d.routeProxy),
                routeDirect = str("routeDirect", d.routeDirect),
                routeBlock = str("routeBlock", d.routeBlock),
                mux = bool("mux", d.mux),
                muxProtocol = str("muxProtocol", d.muxProtocol).takeIf { it in setOf("h2mux", "smux", "yamux") } ?: d.muxProtocol,
                muxMax = int("muxMax", d.muxMax),
                fragment = bool("fragment", d.fragment),
                recordFragment = bool("recordFragment", d.recordFragment),
                logLevel = str("logLevel", d.logLevel).takeIf { it in setOf("error", "warn", "info", "debug") } ?: d.logLevel,
                splitMode = str("splitMode", d.splitMode),
                splitApps = prefs.getStringSet("splitApps", emptySet()) ?: emptySet(),
                subAutoUpdate = bool("subAutoUpdate", d.subAutoUpdate),
                subHours = int("subHours", d.subHours),
                sortMode = str("sortMode", d.sortMode),
            )
        )
    }

    private fun save(s: Settings) {
        prefs.edit()
            .putString("language", s.language).putString("theme", s.theme)
            .putBoolean("dynamicColor", s.dynamicColor).putBoolean("vpnMode", s.vpnMode)
            .putBoolean("autoStart", s.autoStart).putBoolean("keepAwake", s.keepAwake)
            .putBoolean("autoSelect", s.autoSelect).putInt("autoCount", s.autoCount)
            .putBoolean("autoSwitch", s.autoSwitch).putInt("pingFails", s.pingFails)
            .putInt("pingIntervalSec", s.pingIntervalSec).putString("testUrl", s.testUrl)
            .putInt("testTimeoutMs", s.testTimeoutMs).putInt("testWorkers", s.testWorkers)
            .putInt("socksPort", s.socksPort).putBoolean("allowLan", s.allowLan)
            .putString("proxyUser", s.proxyUser).putString("proxyPass", s.proxyPass)
            .putBoolean("ipv6", s.ipv6).putString("tunStack", s.tunStack).putInt("tunMtu", s.tunMtu)
            .putBoolean("blockQuic", s.blockQuic).putBoolean("sniffing", s.sniffing)
            .putString("remoteDns", s.remoteDns).putString("directDns", s.directDns)
            .putBoolean("bypassLan", s.bypassLan).putString("bypassGeo", s.bypassGeo)
            .putString("routeProxy", s.routeProxy).putString("routeDirect", s.routeDirect)
            .putString("routeBlock", s.routeBlock)
            .putBoolean("mux", s.mux).putString("muxProtocol", s.muxProtocol).putInt("muxMax", s.muxMax)
            .putBoolean("fragment", s.fragment).putBoolean("recordFragment", s.recordFragment)
            .putString("logLevel", s.logLevel)
            .putString("splitMode", s.splitMode).putStringSet("splitApps", HashSet(s.splitApps))
            .putBoolean("subAutoUpdate", s.subAutoUpdate).putInt("subHours", s.subHours)
            .putString("sortMode", s.sortMode)
            .apply()
    }

    /** Every stored setting as JSON, for the backup file. */
    fun exportJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in prefs.all) {
            when (v) {
                is String -> o.put(k, v)
                is Int -> o.put(k, v)
                is Boolean -> o.put(k, v)
                is Set<*> -> o.put(k, org.json.JSONArray(v.map { it.toString() }))
                else -> {}
            }
        }
        return o
    }

    /** Restores a backup written by [exportJson]. Unknown keys are ignored. */
    fun importJson(o: JSONObject) {
        val known = prefs.all.keys + save0Keys
        val e = prefs.edit()
        for (k in o.keys()) {
            if (k !in known) continue
            when (val v = o.get(k)) {
                is String -> e.putString(k, v)
                is Int -> e.putInt(k, v)
                is Boolean -> e.putBoolean(k, v)
                is org.json.JSONArray -> e.putStringSet(k, (0 until v.length()).map { v.optString(it) }.toSet())
                else -> {}
            }
        }
        e.apply()
        _flow.value = load()
    }

    private val save0Keys = setOf(
        "language", "theme", "dynamicColor", "vpnMode", "autoStart", "keepAwake", "autoSelect", "autoCount",
        "autoSwitch", "pingFails", "pingIntervalSec", "testUrl", "testTimeoutMs", "testWorkers", "socksPort",
        "allowLan", "proxyUser", "proxyPass", "ipv6", "tunStack", "tunMtu", "blockQuic", "sniffing", "remoteDns",
        "directDns", "bypassLan", "bypassGeo", "routeProxy", "routeDirect", "routeBlock", "mux", "muxProtocol",
        "muxMax", "fragment", "recordFragment", "logLevel", "splitMode", "splitApps", "subAutoUpdate", "subHours",
        "sortMode",
    )
}

/** Countries that have a "go direct" rule set. */
object Geo {
    val codes = listOf("ir", "cn", "ru")

    /** (geoip file, geosite file) published by SagerNet as sing-box rule sets. */
    fun files(cc: String): Pair<String, String> = when (cc) {
        "ir" -> "geoip-ir.srs" to "geosite-category-ir.srs"
        "cn" -> "geoip-cn.srs" to "geosite-cn.srs"
        "ru" -> "geoip-ru.srs" to "geosite-category-ru.srs"
        else -> "" to ""
    }

    fun urls(file: String): List<String> {
        val repo = if (file.startsWith("geoip")) "sing-geoip" else "sing-geosite"
        return listOf(
            "https://raw.githubusercontent.com/SagerNet/$repo/rule-set/$file",
            "https://cdn.jsdelivr.net/gh/SagerNet/$repo@rule-set/$file",
        )
    }
}

// ───────────────────────── formatting ─────────────────────────

fun fmtRate(b: Long): String = when {
    b < 0 -> "—"
    b >= 1_048_576 -> String.format(Locale.US, "%.1f MB/s", b / 1_048_576.0)
    b >= 1024 -> String.format(Locale.US, "%.0f KB/s", b / 1024.0)
    else -> "$b B/s"
}

fun fmtBytes(b: Long): String = when {
    b < 0 -> "—"
    b >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", b / 1_073_741_824.0)
    b >= 1_048_576 -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
    b >= 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    else -> "$b B"
}

/** Country code ("DE") to its flag emoji. */
fun flagOf(cc: String): String {
    val c = cc.trim().uppercase(Locale.US)
    if (c.length != 2 || c.any { it !in 'A'..'Z' }) return "🌐"
    return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
}
