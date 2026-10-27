@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.ray8

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private var askedBattery = false

@Composable
fun HomeScreen(openConfigs: () -> Unit, openLogs: () -> Unit, openRouting: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val s by SettingsStore.flow.collectAsStateWithLifecycle()
    val st by Engine.status.collectAsStateWithLifecycle()
    val ping by Engine.pingMs.collectAsStateWithLifecycle()
    val rx by Engine.rxBps.collectAsStateWithLifecycle()
    val tx by Engine.txBps.collectAsStateWithLifecycle()
    val rxT by Engine.rxTotal.collectAsStateWithLifecycle()
    val txT by Engine.txTotal.collectAsStateWithLifecycle()
    val conns by Engine.connections.collectAsStateWithLifecycle()
    val exit by Engine.exitInfo.collectAsStateWithLifecycle()
    val speed by Engine.speedBps.collectAsStateWithLifecycle()
    val nodes by NodeStore.nodes.collectAsStateWithLifecycle()
    val allSubs by NodeStore.subs.collectAsStateWithLifecycle()
    val activeId by NodeStore.active.collectAsStateWithLifecycle()
    val reason by Engine.lastReason.collectAsStateWithLifecycle()
    val active = nodes.firstOrNull { it.id == activeId }

    val idle = st.phase == Phase.IDLE
    val live = st.phase == Phase.CONNECTED
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var speedTesting by remember { mutableStateOf(false) }
    var showBattery by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val failedText = stringResource(R.string.ping_failed)

    fun runPing() {
        if (testing || !live) return
        testing = true
        testResult = null
        scope.launch {
            val ms: Int? = withContext(Dispatchers.IO) {
                val api = Engine.api
                if (api != null) api.delay(ConfigBuilder.SELECTOR, s.testUrlOrDefault(), s.timeoutMs())
                else Socks.httpGet(Engine.internalPort, "cp.cloudflare.com", "/generate_204", 8000)?.first?.toInt()
            }
            if (ms != null) Engine.pingMs.value = ms
            testResult = if (ms != null) null else failedText
            testing = false
        }
    }

    fun runSpeed() {
        if (speedTesting || !live) return
        speedTesting = true
        scope.launch {
            val hosts = listOf(
                "speed.cloudflare.com" to "/__down?bytes=2000000",
                "cachefly.cachefly.net" to "/1mb.test",
                "ipv4.download.thinkbroadband.com" to "/1MB.zip",
            )
            Engine.speedBps.value = null
            for ((h, p) in hosts) {
                val r = withContext(Dispatchers.IO) { Socks.download(Engine.internalPort, h, p, 2_000_000, 10_000) } ?: continue
                val (code, bytes, ms) = r
                if (code != 200 || bytes <= 0 || ms <= 0) continue
                Engine.speedBps.value = bytes * 1000 / ms
                break
            }
            if (Engine.speedBps.value == null) testResult = failedText
            speedTesting = false
        }
    }
    LaunchedEffect(st.phase) { if (st.phase != Phase.CONNECTED) testResult = null }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) TunnelService.start(ctx)
    }

    fun toggle() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (idle) {
            if (nodes.isEmpty()) {
                Util.toast(ctx, ctx.getString(R.string.no_configs))
                openConfigs()
                return
            }
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            if (!askedBattery && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                showBattery = true
                return
            }
            val i = if (s.vpnMode) VpnService.prepare(ctx) else null
            if (i != null) vpnLauncher.launch(i) else TunnelService.start(ctx)
        } else if (st.phase != Phase.STOPPING) {
            TunnelService.stop(ctx)
        }
    }

    if (showBattery) {
        AlertDialog(
            onDismissRequest = { showBattery = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.battery_title)) },
            text = { Text(stringResource(R.string.battery_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showBattery = false
                    askedBattery = true
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:" + ctx.packageName)
                            )
                        )
                    }
                }) { Text(stringResource(R.string.battery_allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBattery = false
                    askedBattery = true
                    toggle()
                }) { Text(stringResource(R.string.battery_skip)) }
            }
        )
    }

    val c = MaterialTheme.colorScheme
    val x = LocalExtra.current
    val title = when (st.phase) {
        Phase.IDLE -> R.string.disconnected
        Phase.CONNECTING -> R.string.connecting
        Phase.CONNECTED -> R.string.connected
        Phase.RECONNECTING -> R.string.reconnecting
        Phase.STOPPING -> R.string.stopping
    }
    val statusColor = when (st.phase) {
        Phase.CONNECTED -> x.a
        Phase.IDLE -> c.onSurfaceVariant
        else -> x.busy
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LogoMark(36.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconBtn(Ico.LOGS, openLogs)
        }

        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ConnectButton(st.phase, ::toggle, 158.dp)
            if (live) {
                Box(Modifier.align(Alignment.CenterEnd)) { IconBtn(Ico.REFRESH, { TunnelService.restart(ctx) }) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(statusColor.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = statusColor)
        }
        Spacer(Modifier.height(8.dp))
        if (st.phase == Phase.RECONNECTING || (st.phase == Phase.CONNECTING && st.attempt > 1)) {
            Text(stringResource(R.string.attempt_of, st.attempt, 3), style = MaterialTheme.typography.bodySmall, color = x.busy)
        } else if (idle && st.error != null) {
            Text(st.error!!, style = MaterialTheme.typography.bodySmall, color = c.error, textAlign = TextAlign.Center)
        } else if (idle) {
            Text(stringResource(R.string.tap_to_connect), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        } else if (live) {
            Text(
                exit ?: "", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center
            )
        }
        reason?.let {
            Text(
                stringResource(R.string.last_drop, it), style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)
            )
        }

        // ---- Active config
        Spacer(Modifier.height(16.dp))
        AppCard(Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = openConfigs)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(c.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon8(if (s.autoSelect) Ico.BOLT else Ico.GLOBE, c.primary, 22.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (s.autoSelect) stringResource(R.string.auto_select) else active?.name ?: stringResource(R.string.no_config_selected),
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (s.autoSelect) stringResource(R.string.auto_scope_hint, allSubs.firstOrNull { it.id == s.autoScope }?.name ?: stringResource(R.string.auto_scope_all)) else active?.let { it.kind + "  ·  " + it.addr } ?: stringResource(R.string.add_first),
                        fontSize = 12.sp, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon8(Ico.CHEVRON, c.onSurfaceVariant, 20.dp, Modifier.rotate(-90f))
            }
        }

        // ---- Stats
        Spacer(Modifier.height(12.dp))
        AppCard {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 16.dp)) {
                Stat(Ico.BOLT, stringResource(R.string.ping), ping?.let { stringResource(R.string.ms_fmt, it) } ?: "—", Modifier.weight(1f))
                Stat(Ico.DOWN, stringResource(R.string.download), if (live) fmtRate(rx) else "—", Modifier.weight(1f))
                Stat(Ico.UP, stringResource(R.string.upload), if (live) fmtRate(tx) else "—", Modifier.weight(1f))
                Stat(Ico.GLOBE, stringResource(R.string.link_speed), if (live) speed?.let { fmtRate(it) } ?: "…" else "—", Modifier.weight(1f))
            }
            if (live) {
                Text(
                    stringResource(R.string.session_fmt, fmtBytes(rxT), fmtBytes(txT), conns),
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center
                )
            }
        }
        if (!s.vpnMode) {
            Spacer(Modifier.height(10.dp))
            AppCard {
                Text(
                    stringResource(R.string.proxy_info, "127.0.0.1:${s.socksPort}"),
                    modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace
                )
            }
        }

        if (live) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TonalPill(Ico.BOLT, if (testing) stringResource(R.string.ping_testing) else stringResource(R.string.ping_test), enabled = !testing) { runPing() }
                TonalPill(Ico.DOWN, if (speedTesting) stringResource(R.string.ping_testing) else stringResource(R.string.speed_test), enabled = !speedTesting) { runSpeed() }
            }
            testResult?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
        }

        // ---- Setup
        Spacer(Modifier.height(14.dp))
        AppCard(Modifier.animateContentSize()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(
                    listOf(true to stringResource(R.string.mode_vpn), false to stringResource(R.string.mode_proxy)),
                    s.vpnMode, idle
                ) { v -> if (s.vpnMode != v) SettingsStore.update { it.copy(vpnMode = v) } }
                if (!idle) Hint(stringResource(R.string.locked_hint))
                SwitchRow(stringResource(R.string.auto_select_fastest), s.autoSelect) { TunnelService.setAuto(ctx, it) }
                if (s.autoSelect) {
                    val manualScope = s.autoScope != "all" && allSubs.any { it.id == s.autoScope }
                    Segmented(
                        listOf(false to stringResource(R.string.auto_mode_auto), true to stringResource(R.string.auto_mode_manual)),
                        manualScope,
                    ) { manual ->
                        if (manual == manualScope) return@Segmented
                        if (!manual) TunnelService.setAuto(ctx, true, "all")
                        else if (allSubs.isEmpty()) Util.toast(ctx, ctx.getString(R.string.sub_none))
                        else TunnelService.setAuto(ctx, true, allSubs.first().id)
                    }
                    if (manualScope) {
                        Chips(allSubs.map { it.id to it.name.take(18) }, s.autoScope, scroll = true) { id -> TunnelService.setAuto(ctx, true, id) }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Stat(ico: Ico, label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon8(ico, LocalExtra.current.a, 18.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
    }
}


/** Big round power button with soft expanding rings while the link is up or being set up. */
@Composable
fun ConnectButton(phase: Phase, onClick: () -> Unit, diameter: Dp = 158.dp) {
    val c = MaterialTheme.colorScheme
    val x = LocalExtra.current
    val live = phase == Phase.CONNECTED
    val busy = phase == Phase.CONNECTING || phase == Phase.RECONNECTING || phase == Phase.STOPPING

    val inf = rememberInfiniteTransition(label = "hero")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "rings")
    val breathe by inf.animateFloat(
        0.95f, 1.04f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe"
    )
    val glow by animateFloatAsState(if (live) 1f else if (busy) 0.75f else 0f, tween(500), label = "glow")
    val liveA by animateFloatAsState(if (live) 1f else 0f, tween(450), label = "liveA")
    val busyA by animateFloatAsState(if (busy) 1f else 0f, tween(300), label = "busyA")
    val ring = if (busy) x.busy else x.a
    val fg by animateColorAsState(
        when { live -> Color.White; busy -> Color(0xFF2B1E00); else -> c.onSurfaceVariant }, label = "fg"
    )

    Box(Modifier.size(diameter * 1.5f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (glow > 0.01f) {
                val outer = this.size.minDimension / 2f
                val core = outer / 1.5f
                drawCircle(
                    Brush.radialGradient(listOf(ring.copy(alpha = 0.26f * glow), Color.Transparent), radius = outer),
                    radius = outer
                )
                for (i in 0 until 3) {
                    val p = (t + i / 3f) % 1f
                    drawCircle(
                        color = ring.copy(alpha = (1f - p) * 0.30f * glow),
                        radius = core + (outer - core) * p,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }
        Box(
            Modifier.size(diameter).scale(if (busy) breathe else 1f).clip(CircleShape)
                .background(c.surfaceContainerHigh).border(1.dp, c.outlineVariant, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.fillMaxSize().alpha(liveA).background(x.gradient))
            Box(Modifier.fillMaxSize().alpha(busyA).background(x.busy))
            Icon8(Ico.POWER, fg, diameter * 0.40f, strokeW = 2.3f)
        }
    }
}

