package app.ray8

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun SettingsScreen(
    openLogs: () -> Unit, openAbout: () -> Unit, openSplit: () -> Unit, openRouting: () -> Unit,
    openSubs: () -> Unit, onLanguageChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val s by SettingsStore.flow.collectAsStateWithLifecycle()
    val st by Engine.status.collectAsStateWithLifecycle()
    val idle = st.phase == Phase.IDLE
    var totalsTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    fun up(f: (Settings) -> Settings) = SettingsStore.update(f)

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { readUri(ctx, uri) }.getOrDefault("") }
            val n = if (text.contains("8ray-backup")) runCatching { NodeStore.importJson(JSONObject(text), false) }.getOrNull() else null
            Util.toast(ctx, if (n != null) ctx.getString(R.string.backup_restored, n) else ctx.getString(R.string.backup_bad))
            if (n != null) onLanguageChanged()
        }
    }
    val backupSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(NodeStore.exportJson(true).toString(2).toByteArray()) }
            }.onSuccess { Util.toast(ctx, ctx.getString(R.string.backup_saved)) }
                .onFailure { Util.toast(ctx, ctx.getString(R.string.backup_bad)) }
        }
    }
    val splitLabel = stringResource(R.string.split_tunnel) +
        if (s.splitMode != "off" && s.splitApps.isNotEmpty()) "  ·  " + stringResource(R.string.split_summary, s.splitApps.size) else ""

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Text(
            stringResource(R.string.settings), Modifier.padding(top = 8.dp, bottom = 4.dp),
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        )

        Group(stringResource(R.string.sec_main)) {
            ActionRow(Ico.REFRESH, stringResource(R.string.subscriptions)) { openSubs() }
            ActionRow(Ico.LIST, splitLabel) { openSplit() }
            ActionRow(Ico.GLOBE, stringResource(R.string.routing)) { openRouting() }
        }

        Group(stringResource(R.string.sec_connection)) {
            Chips(
                listOf(true to stringResource(R.string.mode_vpn), false to stringResource(R.string.mode_proxy)),
                s.vpnMode, enabled = idle
            ) { v -> if (s.vpnMode != v) up { it.copy(vpnMode = v) } }
            if (!idle) Hint(stringResource(R.string.locked_hint))
            SwitchRow(stringResource(R.string.auto_select_fastest), s.autoSelect) { v -> TunnelService.setAuto(ctx, v) }
            SwitchRow(stringResource(R.string.auto_switch), s.autoSwitch) { v -> up { it.copy(autoSwitch = v) } }
        }

        Group(stringResource(R.string.sec_general)) {
            Field(stringResource(R.string.language)) {
                Chips(listOf("en" to "English", "fa" to "فارسی"), s.language) { v ->
                    if (v != s.language) { up { it.copy(language = v) }; onLanguageChanged() }
                }
            }
            Field(stringResource(R.string.theme)) {
                Chips(
                    listOf("system" to stringResource(R.string.theme_system), "dark" to stringResource(R.string.theme_dark), "light" to stringResource(R.string.theme_light)),
                    s.theme
                ) { v -> up { it.copy(theme = v) } }
            }
            SwitchRow(stringResource(R.string.dynamic_color), s.dynamicColor) { v -> up { it.copy(dynamicColor = v) } }
            SwitchRow(stringResource(R.string.auto_start), s.autoStart) { v -> up { it.copy(autoStart = v) } }
            SwitchRow(stringResource(R.string.keep_awake), s.keepAwake) { v -> up { it.copy(keepAwake = v) } }
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            ActionRow(Ico.BOLT, stringResource(if (ignoring) R.string.battery_row_ok else R.string.battery_row_ask)) {
                if (ignoring) Util.toast(ctx, ctx.getString(R.string.battery_already))
                else runCatching {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
                }
            }
            ActionRow(Ico.LOCK, stringResource(R.string.always_on_vpn)) {
                runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS)) }
            }
        }

        Group(stringResource(R.string.sec_proxy)) {
            NumberRow(stringResource(R.string.socks_port), s.socksPort, { v -> up { it.copy(socksPort = v) } })
            Hint(stringResource(R.string.port_note))
            SwitchRow(stringResource(R.string.allow_lan), s.allowLan) { v -> up { it.copy(allowLan = v) } }
            if (s.allowLan) {
                TextRow(stringResource(R.string.proxy_user), s.proxyUser) { v -> up { it.copy(proxyUser = v) } }
                TextRow(stringResource(R.string.proxy_pass), s.proxyPass) { v -> up { it.copy(proxyPass = v) } }
                Hint(stringResource(R.string.lan_warn), error = s.proxyUser.isEmpty() || s.proxyPass.isEmpty())
            }
        }

        Group(stringResource(R.string.sec_vpn)) {
            SwitchRow(stringResource(R.string.ipv6), s.ipv6) { v -> up { it.copy(ipv6 = v) } }
            Field(stringResource(R.string.tun_stack)) {
                Chips(listOf("gvisor" to "gVisor", "mixed" to "Mixed", "system" to "System"), s.tunStack) { v -> up { it.copy(tunStack = v) } }
            }
            NumberRow(stringResource(R.string.tun_mtu), s.tunMtu) { v -> up { it.copy(tunMtu = v) } }
            SwitchRow(stringResource(R.string.block_quic), s.blockQuic) { v -> up { it.copy(blockQuic = v) } }
            SwitchRow(stringResource(R.string.sniffing), s.sniffing) { v -> up { it.copy(sniffing = v) } }
        }

        Group(stringResource(R.string.sec_core)) {
            Field(stringResource(R.string.log_level)) {
                Chips(listOf("error" to "error", "warn" to "warn", "info" to "info", "debug" to "debug"), s.logLevel) { v -> up { it.copy(logLevel = v) } }
            }
            SwitchRow(stringResource(R.string.fragment), s.fragment) { v -> up { it.copy(fragment = v) } }
            if (s.fragment) SwitchRow(stringResource(R.string.record_fragment), s.recordFragment) { v -> up { it.copy(recordFragment = v) } }
            Hint(stringResource(R.string.fragment_note))
            SwitchRow(stringResource(R.string.mux), s.mux) { v -> up { it.copy(mux = v) } }
            if (s.mux) {
                Field(stringResource(R.string.mux_protocol)) {
                    Chips(listOf("h2mux" to "h2mux", "smux" to "smux", "yamux" to "yamux"), s.muxProtocol) { v -> up { it.copy(muxProtocol = v) } }
                }
                NumberRow(stringResource(R.string.mux_max), s.muxMax) { v -> up { it.copy(muxMax = v) } }
            }
            Hint(stringResource(R.string.mux_note))
            Hint(stringResource(R.string.restart_note))
        }

        Group(stringResource(R.string.sec_health)) {
            NumberRow(stringResource(R.string.auto_count), s.autoCount) { v -> up { it.copy(autoCount = v) } }
            NumberRow(stringResource(R.string.ping_fails), s.pingFails) { v -> up { it.copy(pingFails = v) } }
            NumberRow(stringResource(R.string.ping_interval), s.pingIntervalSec) { v -> up { it.copy(pingIntervalSec = v) } }
            TextRow(stringResource(R.string.test_url), s.testUrl, DEFAULT_TEST_URL) { v -> up { it.copy(testUrl = v) } }
            NumberRow(stringResource(R.string.test_timeout), s.testTimeoutMs) { v -> up { it.copy(testTimeoutMs = v) } }
            NumberRow(stringResource(R.string.test_workers), s.testWorkers) { v -> up { it.copy(testWorkers = v) } }
        }

        Group(stringResource(R.string.sec_traffic)) {
            val rx = remember(totalsTick) { Totals.rx(ctx) }
            val tx = remember(totalsTick) { Totals.tx(ctx) }
            Text(stringResource(R.string.totals_fmt, fmtBytes(rx), fmtBytes(tx)))
            ActionRow(Ico.TRASH, stringResource(R.string.reset_totals), danger = true) { Totals.reset(ctx); totalsTick++ }
        }
        Group(stringResource(R.string.sec_more)) {
            ActionRow(Ico.DOWN, stringResource(R.string.backup_save)) { backupSaver.launch("8ray-backup.json") }
            ActionRow(Ico.UP, stringResource(R.string.backup_restore)) { restorePicker.launch("*/*") }
            ActionRow(Ico.LOGS, stringResource(R.string.open_logs)) { openLogs() }
        }

        if (!idle) Hint(stringResource(R.string.restart_note))

        Group(stringResource(R.string.about)) {
            ActionRow(Ico.INFO, stringResource(R.string.app_name) + "  ·  v" + BuildConfig.VERSION_NAME) { openAbout() }
        }
        Spacer(Modifier.height(28.dp))
    }
}
