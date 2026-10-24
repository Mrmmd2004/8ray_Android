package app.ray8

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** Downloads the sing-box rule sets of a country into the app folder. Returns the number of files that are present. */
object RuleSets {
    suspend fun download(ctx: android.content.Context, geo: String): Int = withContext(Dispatchers.IO) {
        val (ip, site) = Geo.files(geo)
        var ok = 0
        for (file in listOf(ip, site)) {
            if (file.isEmpty()) continue
            val target = ConfigBuilder.ruleFile(ctx, file)
            var fetched = false
            val via = if (Engine.status.value.phase == Phase.CONNECTED) Engine.internalPort else 0
            val attempts = ArrayList<Pair<String, Int>>()
            for (u in Geo.urls(file)) { attempts.add(u to 0); if (via > 0) attempts.add(u to via) }
            for ((url, port) in attempts) {
                try {
                    val u = URL(url)
                    val c = (if (port > 0) u.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))) else u.openConnection()) as HttpURLConnection
                    c.connectTimeout = 12_000
                    c.readTimeout = 25_000
                    c.instanceFollowRedirects = true
                    val bytes = try {
                        if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }
                    } finally {
                        c.disconnect()
                    }
                    // A rule set always starts with the "SRS" magic bytes.
                    if (bytes != null && bytes.size > 16 && bytes[0] == 'S'.code.toByte() && bytes[1] == 'R'.code.toByte() && bytes[2] == 'S'.code.toByte()) {
                        val tmp = java.io.File(target.path + ".tmp")
                        tmp.writeBytes(bytes)
                        target.delete()
                        tmp.renameTo(target)
                        fetched = true
                        break
                    }
                } catch (_: Exception) {
                }
            }
            if (fetched || target.length() > 0) ok++
        }
        ok
    }
}

@Composable
fun RoutingScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by SettingsStore.flow.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun up(f: (Settings) -> Settings) = SettingsStore.update(f)

    fun fetch() {
        if (busy || s.bypassGeo == "none") return
        busy = true
        status = ctx.getString(R.string.downloading)
        scope.launch {
            val n = RuleSets.download(ctx, s.bypassGeo)
            status = if (n > 0) ctx.getString(R.string.rules_ready, n) else ctx.getString(R.string.rules_failed)
            busy = false
        }
    }
    // Choosing a country downloads its lists the first time.
    LaunchedEffect(s.bypassGeo) {
        if (s.bypassGeo != "none") {
            if (ConfigBuilder.ruleSetsReady(ctx, s.bypassGeo)) status = ctx.getString(R.string.rules_have) else fetch()
        } else status = ""
    }

    Page(stringResource(R.string.routing), back) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Banner(stringResource(R.string.routing_note))
            Group(stringResource(R.string.sec_bypass)) {
                SwitchRow(stringResource(R.string.bypass_lan), s.bypassLan) { v -> up { it.copy(bypassLan = v) } }
                Field(stringResource(R.string.bypass_for)) {
                    Chips(
                        listOf("none" to stringResource(R.string.geo_none), "ir" to stringResource(R.string.geo_ir), "cn" to stringResource(R.string.geo_cn), "ru" to stringResource(R.string.geo_ru)),
                        s.bypassGeo
                    ) { v -> up { it.copy(bypassGeo = v) } }
                }
                if (s.bypassGeo != "none") {
                    Hint(stringResource(R.string.geo_hint))
                    if (status.isNotEmpty()) Hint(status)
                    Row { TonalPill(Ico.REFRESH, stringResource(R.string.update_rules), enabled = !busy) { fetch() } }
                }
                SwitchRow(stringResource(R.string.block_quic), s.blockQuic) { v -> up { it.copy(blockQuic = v) } }
                SwitchRow(stringResource(R.string.sniffing), s.sniffing) { v -> up { it.copy(sniffing = v) } }
            }
            Group(stringResource(R.string.sec_rules)) {
                Hint(stringResource(R.string.rules_syntax))
                TextRow(stringResource(R.string.rule_proxy), s.routeProxy, "openai.com\ndomain:example.org\n1.2.3.0/24", singleLine = false) { v -> up { it.copy(routeProxy = v) } }
                TextRow(stringResource(R.string.rule_direct), s.routeDirect, "digikala.com\nkeyword:bank", singleLine = false) { v -> up { it.copy(routeDirect = v) } }
                TextRow(stringResource(R.string.rule_block), s.routeBlock, "ads.example.com", singleLine = false) { v -> up { it.copy(routeBlock = v) } }
            }
            Group(stringResource(R.string.sec_dns)) {
                Field(stringResource(R.string.remote_dns)) {
                    Chips(
                        listOf(
                            "https://1.1.1.1/dns-query" to "Cloudflare DoH", "https://8.8.8.8/dns-query" to "Google DoH",
                            "https://9.9.9.9/dns-query" to "Quad9 DoH", "tcp://1.1.1.1" to "Cloudflare TCP",
                        ),
                        s.remoteDns
                    ) { v -> up { it.copy(remoteDns = v) } }
                }
                TextRow(stringResource(R.string.remote_dns_custom), s.remoteDns, "https://dns.example/dns-query") { v -> up { it.copy(remoteDns = v) } }
                TextRow(stringResource(R.string.direct_dns), s.directDns, stringResource(R.string.direct_dns_hint)) { v -> up { it.copy(directDns = v) } }
                Hint(stringResource(R.string.dns_note))
            }
            Hint(stringResource(R.string.restart_note))
            Spacer(Modifier.height(24.dp))
        }
    }
}
