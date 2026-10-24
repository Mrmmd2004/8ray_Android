package app.ray8

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Foreground service that owns the whole session:
 *   apps -> TUN -> sing-box -> your V2Ray config -> server
 * In proxy-only mode the TUN is skipped and only the local SOCKS5 / HTTP port is served.
 */
class TunnelService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var statsJob: Job? = null
    private var tun: ParcelFileDescriptor? = null
    private var foreground = false
    private var stopping = false
    @Volatile private var coreHung = false
    @Volatile private var pendingStart = false
    @Volatile private var networkChanged = false
    @Volatile private var restartRequested = false
    @Volatile private var coreDied = false
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var lastNet: Network? = null
    @Volatile private var connectedAt = 0L
    @Volatile private var lastRxAt = 0L
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val token = Any()
    private var lastNotif = ""

    // Session values that stay the same across core restarts
    private var clashPort = 0
    private var internalPort = 0
    private var secret = ""
    private var useRuleSets = true
    private var sessionRx = 0L
    private var sessionTx = 0L

    private enum class Start { OK, FAIL, FATAL }
    private enum class Exit { DEAD, NETWORK, RESTART, CORE }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleHelper.wrap(base))

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            while (isActive) {
                if (foreground) {
                    val text = textFor(Engine.status.value)
                    if (text != lastNotif) {
                        lastNotif = text
                        nm.notify(NOTIF_ID, notification(text))
                    }
                }
                delay(1500)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_RESTART -> if (Engine.status.value.phase == Phase.CONNECTED) restartRequested = true
            else -> startSession()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopSession()
    }

    override fun onDestroy() {
        scope.cancel()
        teardownBlocking()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- session

    private fun startSession() {
        promote()
        if (stopping) {
            // A disconnect is still closing the old core; start again as soon as it is done.
            pendingStart = true
            Engine.status.value = Status(Phase.CONNECTING, 1)
            return
        }
        if (job?.isActive == true) return
        Tester.cancel()
        Engine.logs.value = emptyList()
        Engine.lastReason.value = null
        Engine.resetLive()
        Engine.status.value = Status(Phase.CONNECTING, 1)
        networkChanged = false
        restartRequested = false
        coreDied = false
        sessionRx = 0
        sessionTx = 0
        useRuleSets = true
        watchNetwork()
        job = scope.launch { run() }
    }

    private fun stopSession() {
        if (stopping) return
        stopping = true
        // Instant disconnect: the app shows "disconnected" and the notification goes away at once;
        // the core and the TUN are closed right behind it (normally well under a second).
        job?.cancel()
        statsJob?.cancel()
        Engine.resetLive()
        Engine.status.value = Status(Phase.IDLE)
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.launch {
            try {
                val stopped = stopCoreBounded(3000)
                withTimeoutOrNull(1500) { job?.join() }
                unwatchNetwork()
                releaseLocks()
                Totals.add(this@TunnelService, sessionRx, sessionTx)
                sessionRx = 0
                sessionTx = 0
                if (pendingStart && stopped) {
                    // The user tapped Connect while the old core was closing: carry on with a new session.
                    pendingStart = false
                    stopping = false
                    startSession()
                } else {
                    pendingStart = false
                    stopSelf()
                    if (!stopped) {
                        Engine.log("The core did not stop in time: restarting the app process")
                        // The service (and this scope) is gone after stopSelf(), so the kill is posted outside it.
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            { android.os.Process.killProcess(android.os.Process.myPid()) }, 600
                        )
                    }
                }
            } finally {
                stopping = false
            }
        }
    }

    private suspend fun finish(error: String?) {
        teardown()
        Totals.add(this, sessionRx, sessionTx)
        Engine.resetLive()
        Engine.status.value = Status(Phase.IDLE, error = error)
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun run() {
        val base = SettingsStore.current
        acquireWake(base)
        if (!SingBox.available) { finish(getString(R.string.no_core)); return }
        if (NodeStore.nodes.value.isEmpty()) { finish(getString(R.string.no_configs)); return }
        val busy = withContext(Dispatchers.IO) { Net.portOpen(base.socksPort) }
        if (busy) { finish(getString(R.string.port_busy, base.socksPort)); return }

        withContext(Dispatchers.IO) {
            clashPort = Net.freePort()
            internalPort = Net.freePort()
            while (internalPort == clashPort) internalPort = Net.freePort()
        }
        secret = UUID.randomUUID().toString().replace("-", "")
        Engine.internalPort = internalPort
        SingBox.onStop = { coreDied = true }

        var failures = 0
        var everUp = false
        while (currentCoroutineContext().isActive) {
            Engine.status.value = Status(if (everUp) Phase.RECONNECTING else Phase.CONNECTING, failures + 1)
            coreDied = false
            when (startCore()) {
                Start.OK -> {
                    everUp = true
                    failures = 0
                    networkChanged = false
                    connectedAt = SystemClock.elapsedRealtime()
                    lastRxAt = connectedAt
                    Engine.status.value = Status(Phase.CONNECTED)
                    Engine.log("Connected: ${activeName()}")
                    scope.launch { fetchExit() }
                    if (SettingsStore.current.autoSelect) scope.launch { kickAuto() }
                    val why = monitor()
                    Engine.pingMs.value = null
                    Engine.exitInfo.value = null
                    Engine.speedBps.value = null
                    Engine.apiReady.value = false
                    Engine.api = null
                    withContext(Dispatchers.IO) { SingBox.stopIf(token) }
                    delay(800)
                    when (why) {
                        Exit.NETWORK -> Engine.log("The network changed: restarting the core")
                        Exit.RESTART -> Engine.log("Restarting the core")
                        Exit.CORE -> Engine.lastReason.value = getString(R.string.reason_core)
                        Exit.DEAD -> {}
                    }
                }
                Start.FATAL -> return
                Start.FAIL -> {
                    failures++
                    Engine.lastReason.value = getString(R.string.reason_start, failures)
                    if (failures >= MAX_TRIES) {
                        finish(getString(R.string.connect_failed))
                        return
                    }
                    delay(1500)
                }
            }
        }
    }

    // ------------------------------------------------------------------- core

    private suspend fun startCore(): Start {
        val s = SettingsStore.current
        val built = try {
            ConfigBuilder.build(
                this, s, NodeStore.nodes.value, NodeStore.active.value, s.vpnMode,
                clashPort, secret, internalPort, useRuleSets,
            )
        } catch (e: ConfigBuilder.NoConfig) {
            finish(getString(R.string.no_usable_config))
            return Start.FATAL
        }
        if (s.vpnMode && tun == null && !withContext(Dispatchers.IO) { bringUpTun(s) }) {
            finish(getString(R.string.vpn_iface_failed))
            return Start.FATAL
        }
        val err = withContext(Dispatchers.IO) { SingBox.start(this@TunnelService, tun, built.json, token) { protect(it) } }
        if (err != null) {
            Engine.log("sing-box: $err")
            if (built.usedRuleSets) {
                // A broken or half downloaded rule set must never keep the VPN from starting.
                useRuleSets = false
                Engine.log("Starting again without the country rule sets")
                return startCore()
            }
            return Start.FAIL
        }
        val api = ClashApi(clashPort, secret)
        var ready = false
        for (i in 0 until 30) {
            if (!currentCoroutineContext().isActive) return Start.FAIL
            if (coreDied) return Start.FAIL
            ready = withContext(Dispatchers.IO) { api.ready() }
            if (ready) break
            delay(200)
        }
        if (ready) {
            Engine.api = api
            Engine.tags = built.tags
            Engine.apiReady.value = true
        } else {
            Engine.log("Clash API did not answer: live switching and exact counters are off")
        }
        startStats()
        Engine.log("sing-box running (${if (s.vpnMode) "VPN" else "proxy only"}, mixed port ${s.socksPort})")
        return Start.OK
    }

    private fun activeName(): String =
        if (SettingsStore.current.autoSelect) getString(R.string.auto_select) else NodeStore.activeNode()?.name ?: "—"

    /**
     * Returns when the link has to be rebuilt. A dead config is first replaced by the next best one
     * through the clash API (no restart, the VPN stays up); only when nothing is left to try, or the
     * API is missing, the whole core is restarted.
     */
    private suspend fun monitor(): Exit {
        var fails = 0
        val tried = HashSet<String>()
        var dnsAtStart = Net.systemDns(this)
        while (currentCoroutineContext().isActive) {
            val s = SettingsStore.current
            if (restartRequested) { restartRequested = false; return Exit.RESTART }
            if (coreDied) { Engine.log("The core stopped"); return Exit.CORE }
            if (networkChanged) {
                networkChanged = false
                val dnsNow = withContext(Dispatchers.IO) { Net.systemDns(this@TunnelService) }
                if (s.directDns.isBlank() && dnsNow != null && dnsNow != dnsAtStart) return Exit.NETWORK
                dnsAtStart = dnsNow
                Engine.log("Network changed: dropping old connections")
                withContext(Dispatchers.IO) { Engine.api?.closeAll() }
            }
            val limit = s.pingLimit()
            val timeout = s.timeoutMs()
            val ms: Int? = withContext(Dispatchers.IO) {
                val api = Engine.api
                if (api != null) api.delay(ConfigBuilder.SELECTOR, s.testUrlOrDefault(), timeout)
                else Socks.httpGet(internalPort, "cp.cloudflare.com", "/generate_204", timeout)?.first?.toInt()
            }
            val now = SystemClock.elapsedRealtime()
            val recentData = Engine.rxBps.value > 0 || now - lastRxAt < 15_000
            if (ms != null) {
                fails = 0
                tried.clear()
                Engine.pingMs.value = ms
                if (!s.autoSelect) NodeStore.active.value.takeIf { it.isNotEmpty() }?.let { NodeStore.setDelays(mapOf(it to ms)) }
            } else if (recentData) {
                fails = 0
                Engine.pingMs.value = null
            } else {
                fails++
                Engine.pingMs.value = null
                Engine.log("Ping failed ($fails/$limit), last data ${(now - lastRxAt) / 1000} s ago")
                if (s.autoSelect) kickAuto()
                if (fails >= limit) {
                    val cur = NodeStore.active.value
                    if (cur.isNotEmpty() && !s.autoSelect) {
                        tried.add(cur)
                        NodeStore.setDelays(mapOf(cur to -1))
                    }
                    val next = if (s.autoSwitch && !s.autoSelect && Engine.api != null) nextNode(tried) else null
                    if (next != null) {
                        Engine.log("Config is dead, switching to \"${next.name}\"")
                        val ok = withContext(Dispatchers.IO) { Engine.api?.select(ConfigBuilder.SELECTOR, next.id) == true }
                        if (ok) {
                            NodeStore.setActive(next.id)
                            Engine.lastReason.value = getString(R.string.reason_switched, next.name)
                            fails = 0
                            scope.launch { fetchExit() }
                            continue
                        }
                    }
                    Engine.lastReason.value = getString(R.string.reason_pings, limit)
                    return Exit.DEAD
                }
            }
            // Wait between probes, but react to a restart request or a network change within half a second.
            repeat(s.pingEverySec() * 2) {
                if (restartRequested || networkChanged || coreDied) return@repeat
                delay(500)
            }
        }
        return Exit.DEAD
    }

    /** Auto mode: tests every config of the group now, so the best working one is used at once. */
    private suspend fun kickAuto() {
        val s = SettingsStore.current
        val api = Engine.api ?: return
        val ok = withContext(Dispatchers.IO) { api.groupTest(ConfigBuilder.AUTO, s.testUrlOrDefault(), s.timeoutMs()) }
        Engine.log(if (ok) "Auto: tested the group, the best config is in use" else "Auto: no config of the group answered")
    }

    /** Best config that has not been tried yet in this round: lowest known delay first, untested next. */
    private fun nextNode(tried: Set<String>): Node? =
        NodeStore.nodes.value
            .filter { it.id !in tried && it.id in Engine.tags && it.delay != -1 }
            .minByOrNull { it.delay ?: (Int.MAX_VALUE - 1) }

    private suspend fun fetchExit() {
        val port = internalPort
        if (port <= 0) return
        Engine.exitInfo.value = null
        val r = withContext(Dispatchers.IO) { Socks.httpGet(port, "www.cloudflare.com", "/cdn-cgi/trace", 15_000) } ?: return
        val map = r.second.lines().mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 } }.associate { it[0] to it[1].trim() }
        val ip = map["ip"] ?: return
        val loc = map["loc"]
        Engine.exitInfo.value = if (loc != null) "${flagOf(loc)} $ip ($loc)" else ip
    }

    // ---------------------------------------------------------------- network

    /**
     * Watches the physical default network. This app is excluded from its own VPN, so the default
     * network it sees is the real Wi-Fi / mobile one, not the TUN.
     */
    private fun watchNetwork() {
        if (netCb != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val prev = lastNet
                lastNet = network
                if (prev != null && prev != network &&
                    Engine.status.value.phase == Phase.CONNECTED &&
                    SystemClock.elapsedRealtime() - connectedAt > 3000
                ) {
                    Engine.lastReason.value = getString(R.string.reason_network)
                    networkChanged = true
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Exception) {
            Engine.log("Network watcher unavailable: ${e.message}")
        }
    }

    private fun unwatchNetwork() {
        val cb = netCb ?: return
        netCb = null
        lastNet = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    // -------------------------------------------------------------------- tun

    private fun bringUpTun(s: Settings): Boolean {
        val b = Builder()
            .setSession("8ray")
            .setMtu(s.mtu())
            .addAddress("198.18.0.1", 32)
            .addRoute("0.0.0.0", 0)
        if (s.ipv6) {
            b.addAddress("fc00::1", 128)
            b.addRoute("::", 0)
        }
        // sing-box answers every DNS query that arrives on the TUN, whatever server the phone asks.
        b.addDnsServer("1.1.1.1")
        b.addDnsServer("8.8.8.8")
        if (s.ipv6) runCatching { b.addDnsServer("2606:4700:4700::1111") }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

        val apps = s.splitApps
        try {
            if (s.splitMode == "include" && apps.isNotEmpty()) {
                apps.forEach { runCatching { b.addAllowedApplication(it) } }
            } else {
                // The core's own sockets must never loop back into the tunnel.
                b.addDisallowedApplication(packageName)
                if (s.splitMode == "exclude") apps.forEach { runCatching { b.addDisallowedApplication(it) } }
            }
        } catch (e: Exception) {
            Engine.log("Split tunneling error: ${e.message}")
        }
        val fd = try { b.establish() } catch (e: Exception) { Engine.log("TUN error: ${e.message}"); null } ?: return false
        tun = fd
        Engine.log("TUN up: stack=${s.tunStack}, mtu=${s.mtu()}, ipv6=${s.ipv6}")
        return true
    }

    // ------------------------------------------------------------------ stats

    private fun startStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var lastUp = -1L
            var lastDown = -1L
            val uid = android.os.Process.myUid()
            var lastTx = android.net.TrafficStats.getUidTxBytes(uid)
            var lastRx = android.net.TrafficStats.getUidRxBytes(uid)
            while (isActive) {
                delay(1000)
                val api = Engine.api
                val t = if (api != null) withContext(Dispatchers.IO) { api.traffic() } else null
                if (t != null) {
                    if (lastUp < 0 || t.up < lastUp || t.down < lastDown) { lastUp = t.up; lastDown = t.down }
                    val dTx = t.up - lastUp
                    val dRx = t.down - lastDown
                    lastUp = t.up
                    lastDown = t.down
                    sessionTx += dTx
                    sessionRx += dRx
                    Engine.txBps.value = dTx
                    Engine.rxBps.value = dRx
                    Engine.txTotal.value = sessionTx
                    Engine.rxTotal.value = sessionRx
                    Engine.connections.value = t.connections
                    if (dRx > 0) lastRxAt = SystemClock.elapsedRealtime()
                } else {
                    // Fallback: this app's own network counters (the core runs under the same uid).
                    val tx = android.net.TrafficStats.getUidTxBytes(uid)
                    val rx = android.net.TrafficStats.getUidRxBytes(uid)
                    if (tx >= 0 && rx >= 0 && lastTx >= 0 && lastRx >= 0) {
                        Engine.txBps.value = (tx - lastTx).coerceAtLeast(0)
                        Engine.rxBps.value = (rx - lastRx).coerceAtLeast(0)
                        if (rx > lastRx) lastRxAt = SystemClock.elapsedRealtime()
                    }
                    lastTx = tx
                    lastRx = rx
                }
            }
        }
    }

    // --------------------------------------------------------------- teardown

    private suspend fun teardown() {
        unwatchNetwork()
        stopCoreBounded(4000)
        releaseLocks()
    }

    /**
     * Closes the TUN and stops the core on a plain thread and waits at most [ms]. Returns false when the
     * core did not finish in time (the native close can block), so callers never hang on it.
     */
    private suspend fun stopCoreBounded(ms: Long): Boolean {
        if (coreHung) return false
        val done = CompletableDeferred<Unit>()
        Thread({
            try { stopTunSide() } finally { done.complete(Unit) }
        }, "8ray-stop").start()
        val ok = withTimeoutOrNull(ms) { done.await() } != null
        if (!ok) coreHung = true
        return ok
    }

    /** Closes the TUN first (the VPN key icon disappears), then stops the core. Safe to call twice. */
    private fun stopTunSide() {
        statsJob?.cancel()
        statsJob = null
        SingBox.onStop = null
        val t = tun
        tun = null
        runCatching { t?.close() }
        runCatching { SingBox.stopIf(token) }
    }

    private fun releaseLocks() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        wifi?.let { if (it.isHeld) it.release() }
        wifi = null
    }

    /** Used from onDestroy (main thread): never wait more than a moment. */
    private fun teardownBlocking() {
        unwatchNetwork()
        if (!coreHung) {
            val t = Thread({ stopTunSide() }, "8ray-stop")
            t.start()
            runCatching { t.join(1500) }
        }
        releaseLocks()
    }

    private fun acquireWake(s: Settings) {
        if (!s.keepAwake) return
        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "8ray:core").apply {
            setReferenceCounted(false)
            acquire()
        }
        // Without this, Wi-Fi may go to power save when the screen is off and the tunnel stalls.
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "8ray:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    // ----------------------------------------------------------- notification

    private fun textFor(st: Status): String = when (st.phase) {
        Phase.CONNECTED -> {
            val rx = Engine.rxBps.value
            val tx = Engine.txBps.value
            getString(R.string.connected) + " · " + activeName() + "  ↓ " + fmtRate(rx) + "  ↑ " + fmtRate(tx)
        }
        Phase.CONNECTING -> getString(R.string.connecting)
        Phase.RECONNECTING -> getString(R.string.reconnecting)
        Phase.STOPPING -> getString(R.string.stopping)
        Phase.IDLE -> getString(R.string.disconnected)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat), getString(R.string.disconnect), stop
                ).build()
            )
            .build()
    }

    private fun promote() {
        if (foreground) return
        val n = notification(getString(R.string.connecting))
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
        foreground = true
    }

    companion object {
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 8
        private const val MAX_TRIES = 3
        const val ACTION_STOP = "app.ray8.STOP"
        const val ACTION_RESTART = "app.ray8.RESTART"

        private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, TunnelService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TunnelService::class.java).setAction(ACTION_STOP))
        }

        fun restart(ctx: Context) {
            ctx.startService(Intent(ctx, TunnelService::class.java).setAction(ACTION_RESTART))
        }

        /**
         * Makes [id] the config in use. While the VPN is up it is switched live through the clash API
         * (no reconnect); without the API the core is restarted.
         */
        fun select(ctx: Context, id: String) {
            val app = ctx.applicationContext
            NodeStore.setActive(id)
            if (SettingsStore.current.autoSelect) SettingsStore.update { it.copy(autoSelect = false) }
            if (Engine.status.value.phase != Phase.CONNECTED) return
            val api = Engine.api
            if (api == null || id !in Engine.tags) {
                restart(app)
                return
            }
            bg.launch {
                if (api.select(ConfigBuilder.SELECTOR, id)) {
                    Engine.pingMs.value = null
                    Engine.log("Switched to \"${NodeStore.get(id)?.name}\"")
                } else {
                    restart(app)
                }
            }
        }

        /** Turns "auto select fastest" on or off; the core is rebuilt because the group is part of the config. */
        fun setAuto(ctx: Context, on: Boolean) {
            SettingsStore.update { it.copy(autoSelect = on) }
            if (Engine.status.value.phase == Phase.CONNECTED) restart(ctx.applicationContext)
        }
    }
}

/** All-time traffic counters (every finished session adds to them). */
object Totals {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("totals", Context.MODE_PRIVATE)

    fun add(ctx: Context, rx: Long, tx: Long) {
        if (rx <= 0 && tx <= 0) return
        val p = prefs(ctx)
        p.edit().putLong("rx", p.getLong("rx", 0) + rx.coerceAtLeast(0)).putLong("tx", p.getLong("tx", 0) + tx.coerceAtLeast(0)).apply()
    }

    fun rx(ctx: Context) = prefs(ctx).getLong("rx", 0)
    fun tx(ctx: Context) = prefs(ctx).getLong("tx", 0)
    fun reset(ctx: Context) = prefs(ctx).edit().clear().apply()
}
