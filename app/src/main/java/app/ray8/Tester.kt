package app.ray8

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * Delay tests. "Real delay" sends one request through the config itself:
 *  - VPN on: through the running core (clash API), no extra work;
 *  - VPN off: through a short-lived sing-box instance that has no inbound (a few seconds, no VPN icon).
 * Configs that sing-box cannot start, and UDP ones when libbox is missing, fall back to a TCP ping.
 */
object Tester {
    val busy = MutableStateFlow(false)
    val done = MutableStateFlow(0)
    val total = MutableStateFlow(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val token = Any()

    /** Stops a running test (the VPN session calls this before it takes the core over). */
    fun cancel() {
        job?.cancel()
        job = null
        SingBox.stopIf(token)
        SingBox.quiet = false
        busy.value = false
    }

    fun testReal(ctx: Context, ids: List<String>) = launch { runReal(ctx.applicationContext, ids) }

    fun testTcp(ids: List<String>) = launch { runTcp(ids) }

    private fun launch(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        done.value = 0
        job = scope.launch {
            try {
                block()
            } finally {
                busy.value = false
            }
        }
    }

    // ───────────────────────── TCP ping ─────────────────────────

    fun tcpPing(host: String, port: Int, timeoutMs: Int = 4000): Int {
        val t0 = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            maxOf(1, ((System.nanoTime() - t0) / 1_000_000).toInt())
        } catch (_: Exception) {
            -1
        }
    }

    private suspend fun runTcp(ids: List<String>) {
        val nodes = ids.mapNotNull { NodeStore.get(it) }.filter { !it.udp }
        total.value = nodes.size
        val s = SettingsStore.current
        val gate = Semaphore(s.workers() * 2)
        val pending = HashMap<String, Int>()
        withContext(Dispatchers.IO) {
            nodes.map { n ->
                async {
                    gate.withPermit {
                        if (!currentCoroutineContext().isActive) return@withPermit
                        val host = n.addr.substringBeforeLast(':').trim('[', ']')
                        val port = n.addr.substringAfterLast(':').toIntOrNull() ?: 0
                        val ms = if (port > 0) tcpPing(host, port) else -1
                        synchronized(pending) {
                            pending[n.id] = ms
                            done.value = done.value + 1
                            if (pending.size >= 8 || done.value >= total.value) {
                                NodeStore.setDelays(HashMap(pending))
                                pending.clear()
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        synchronized(pending) { NodeStore.setDelays(HashMap(pending)); pending.clear() }
    }

    // ───────────────────────── real delay ─────────────────────────

    private suspend fun runReal(ctx: Context, ids: List<String>) {
        val s = SettingsStore.current
        val nodes = ids.mapNotNull { NodeStore.get(it) }
        if (nodes.isEmpty()) return
        total.value = nodes.size
        val phase = Engine.status.value.phase

        // 1) VPN is up: ask the running core.
        val live = Engine.api
        if (phase == Phase.CONNECTED && live != null) {
            val inCore = nodes.filter { it.id in Engine.tags }
            val rest = nodes.filter { it.id !in Engine.tags }
            measure(live, inCore, s)
            if (rest.isNotEmpty()) tcpFallback(rest)
            return
        }
        if (phase != Phase.IDLE || !SingBox.available) {
            tcpFallback(nodes)
            return
        }

        // 2) VPN is off: a throw-away core without inbounds.
        val port = withContext(Dispatchers.IO) { Net.freePort() }
        val secret = UUID.randomUUID().toString().replace("-", "")
        val built = try {
            ConfigBuilder.buildTest(ctx, s, nodes, port, secret)
        } catch (_: ConfigBuilder.NoConfig) {
            tcpFallback(nodes)
            return
        }
        SingBox.quiet = true
        val err = withContext(Dispatchers.IO) { SingBox.start(ctx, null, built.json, token) { true } }
        if (err != null) {
            SingBox.quiet = false
            Engine.log("Test core could not start: $err")
            tcpFallback(nodes)
            return
        }
        try {
            val api = ClashApi(port, secret)
            var up = false
            for (i in 0 until 25) {
                if (withContext(Dispatchers.IO) { api.ready() }) { up = true; break }
                delay(200)
            }
            if (!up) {
                tcpFallback(nodes)
                return
            }
            measure(api, nodes.filter { it.id in built.tags }, s)
            val skipped = nodes.filter { it.id !in built.tags }
            if (skipped.isNotEmpty()) tcpFallback(skipped)
        } finally {
            SingBox.stopIf(token)
            SingBox.quiet = false
        }
    }

    private suspend fun measure(api: ClashApi, nodes: List<Node>, s: Settings) {
        val gate = Semaphore(s.workers())
        val pending = HashMap<String, Int>()
        val url = s.testUrlOrDefault()
        val timeout = s.timeoutMs()
        withContext(Dispatchers.IO) {
            nodes.map { n ->
                async {
                    gate.withPermit {
                        if (!currentCoroutineContext().isActive) return@withPermit
                        val ms = api.delay(n.id, url, timeout) ?: -1
                        synchronized(pending) {
                            pending[n.id] = ms
                            done.value = done.value + 1
                            if (pending.size >= 5 || done.value >= total.value) {
                                NodeStore.setDelays(HashMap(pending))
                                pending.clear()
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        synchronized(pending) { NodeStore.setDelays(HashMap(pending)); pending.clear() }
    }

    private suspend fun tcpFallback(nodes: List<Node>) {
        val tcpable = nodes.filter { !it.udp }
        val before = done.value
        runTcp(tcpable.map { it.id })
        // runTcp reset the total to its own list; keep the bar sensible
        done.value = before + tcpable.size
    }
}
