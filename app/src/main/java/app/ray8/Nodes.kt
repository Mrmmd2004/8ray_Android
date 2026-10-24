package app.ray8

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One saved config. [link] is the source of truth, everything else is derived from it or user data. */
data class Node(
    val id: String,
    val name: String,
    val link: String,
    val proto: String,
    val kind: String,
    val addr: String,
    val sub: String = "",
    /** null = not tested, -1 = failed, otherwise milliseconds. */
    val delay: Int? = null,
    val fav: Boolean = false,
    /** Id of another config this one is dialled through (proxy chain), or empty. */
    val detour: String = "",
    val udp: Boolean = false,
)

data class Sub(
    val id: String,
    val name: String,
    val url: String,
    val updated: Long = 0L,
    val upload: Long = -1L,
    val download: Long = -1L,
    val total: Long = -1L,
    /** Expiry, seconds since epoch; -1 = unknown or never. */
    val expire: Long = -1L,
    val auto: Boolean = true,
    val error: String = "",
)

data class AddResult(val added: Int, val duplicates: Int, val bad: Int, val errors: List<String>)

object NodeStore {
    private lateinit var file: File
    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    private val _subs = MutableStateFlow<List<Sub>>(emptyList())
    private val _active = MutableStateFlow("")
    val nodes: StateFlow<List<Node>> = _nodes
    val subs: StateFlow<List<Sub>> = _subs
    val active: StateFlow<String> = _active

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.applicationContext.filesDir, "configs.json")
        load()
    }

    // ───────────────────────── file ─────────────────────────

    private fun nodeJson(n: Node) = JSONObject()
        .put("id", n.id).put("name", n.name).put("link", n.link).put("proto", n.proto).put("kind", n.kind)
        .put("addr", n.addr).put("sub", n.sub).put("fav", n.fav).put("detour", n.detour).put("udp", n.udp)
        .also { if (n.delay != null) it.put("delay", n.delay) }

    private fun nodeFrom(o: JSONObject): Node? {
        val id = o.optString("id")
        val link = o.optString("link")
        if (id.isEmpty() || link.isEmpty()) return null
        return Node(
            id = id, name = o.optString("name").ifEmpty { id }, link = link, proto = o.optString("proto"),
            kind = o.optString("kind"), addr = o.optString("addr"), sub = o.optString("sub"),
            delay = if (o.has("delay")) o.optInt("delay") else null, fav = o.optBoolean("fav"),
            detour = o.optString("detour"), udp = o.optBoolean("udp"),
        )
    }

    private fun subJson(s: Sub) = JSONObject()
        .put("id", s.id).put("name", s.name).put("url", s.url).put("updated", s.updated).put("upload", s.upload)
        .put("download", s.download).put("total", s.total).put("expire", s.expire).put("auto", s.auto).put("error", s.error)

    private fun subFrom(o: JSONObject): Sub? {
        val id = o.optString("id")
        val url = o.optString("url")
        if (id.isEmpty() || url.isEmpty()) return null
        return Sub(
            id, o.optString("name").ifEmpty { url }, url, o.optLong("updated"), o.optLong("upload", -1), o.optLong("download", -1),
            o.optLong("total", -1), o.optLong("expire", -1), o.optBoolean("auto", true), o.optString("error"),
        )
    }

    private fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("nodes", JSONArray().also { a -> _nodes.value.forEach { a.put(nodeJson(it)) } })
        o.put("subs", JSONArray().also { a -> _subs.value.forEach { a.put(subJson(it)) } })
        o.put("active", _active.value)
        return o
    }

    private fun fromJson(o: JSONObject) {
        val ns = ArrayList<Node>()
        o.optJSONArray("nodes")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { x -> nodeFrom(x)?.let(ns::add) } }
        val ss = ArrayList<Sub>()
        o.optJSONArray("subs")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { x -> subFrom(x)?.let(ss::add) } }
        _nodes.value = ns.distinctBy { it.id }
        _subs.value = ss.distinctBy { it.id }
        val act = o.optString("active")
        _active.value = if (_nodes.value.any { it.id == act }) act else _nodes.value.firstOrNull()?.id ?: ""
    }

    private fun load() {
        try {
            if (file.exists()) fromJson(JSONObject(file.readText()))
        } catch (_: Exception) {
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            saveNow()
        }
    }

    @Synchronized
    fun saveNow() {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(toJson().toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
        }
    }

    // ───────────────────────── queries ─────────────────────────

    fun get(id: String): Node? = _nodes.value.firstOrNull { it.id == id }
    fun activeNode(): Node? = get(_active.value)
    fun sub(id: String): Sub? = _subs.value.firstOrNull { it.id == id }

    private fun newId(taken: Set<String>): String {
        while (true) {
            val id = UUID.randomUUID().toString().replace("-", "").take(8)
            if (id !in taken) return id
        }
    }

    // ───────────────────────── configs ─────────────────────────

    @Synchronized
    fun setActive(id: String) {
        if (get(id) != null) {
            _active.value = id
            scheduleSave()
        }
    }

    @Synchronized
    fun addLinks(links: List<String>, sub: String = ""): AddResult {
        val have = _nodes.value.map { it.link }.toHashSet()
        val ids = _nodes.value.map { it.id }.toHashSet()
        val fresh = ArrayList<Node>()
        var dup = 0
        var bad = 0
        val errors = ArrayList<String>()
        for (raw in links) {
            val ln = raw.trim()
            if (ln.isEmpty()) continue
            if (ln in have) { dup++; continue }
            val p = try {
                Links.parse(ln)
            } catch (e: LinkError) {
                bad++
                if (errors.size < 5) errors.add(e.message ?: "bad link")
                continue
            }
            have.add(ln)
            val id = newId(ids)
            ids.add(id)
            fresh.add(Node(id, p.name, ln, p.proto, p.kind, "${p.host}:${p.port}".trim(':'), sub, null, false, "", p.udp))
        }
        if (fresh.isNotEmpty()) {
            _nodes.value = _nodes.value + fresh
            if (_active.value.isEmpty()) _active.value = fresh.first().id
            scheduleSave()
        }
        return AddResult(fresh.size, dup, bad, errors)
    }

    private fun mutate(id: String, f: (Node) -> Node) {
        _nodes.value = _nodes.value.map { if (it.id == id) f(it) else it }
        scheduleSave()
    }

    @Synchronized fun rename(id: String, name: String) = mutate(id) { it.copy(name = name.trim().ifEmpty { it.name }) }
    @Synchronized fun setFav(id: String, fav: Boolean) = mutate(id) { it.copy(fav = fav) }

    @Synchronized
    fun setDetour(id: String, detour: String) {
        // A chain may not loop back to itself.
        var cur = detour
        var steps = 0
        while (cur.isNotEmpty() && steps < 8) {
            if (cur == id) return
            cur = get(cur)?.detour ?: ""
            steps++
        }
        mutate(id) { it.copy(detour = detour) }
    }

    /** Replaces the share link of a config (edit as text). Returns an error message or null. */
    @Synchronized
    fun replaceLink(id: String, newLink: String): String? {
        val ln = newLink.trim()
        val p = try { Links.parse(ln) } catch (e: LinkError) { return e.message ?: "bad link" }
        mutate(id) { it.copy(link = ln, name = p.name, proto = p.proto, kind = p.kind, addr = "${p.host}:${p.port}", udp = p.udp, delay = null) }
        return null
    }

    @Synchronized
    fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        _nodes.value = _nodes.value.filter { it.id !in ids }.map { if (it.detour in ids) it.copy(detour = "") else it }
        if (_active.value in ids) _active.value = _nodes.value.firstOrNull()?.id ?: ""
        scheduleSave()
    }

    @Synchronized
    fun setDelays(map: Map<String, Int>) {
        if (map.isEmpty()) return
        _nodes.value = _nodes.value.map { n -> map[n.id]?.let { n.copy(delay = it) } ?: n }
        scheduleSave()
    }

    @Synchronized
    fun clearDelays() {
        _nodes.value = _nodes.value.map { it.copy(delay = null) }
        scheduleSave()
    }

    @Synchronized
    fun sortByDelay() {
        fun key(n: Node): Int = when {
            n.delay != null && n.delay >= 0 -> n.delay
            n.delay == null -> Int.MAX_VALUE - 1
            else -> Int.MAX_VALUE
        }
        _nodes.value = _nodes.value.sortedBy { key(it) }
        scheduleSave()
    }

    @Synchronized
    fun removeFailed(): Int {
        val dead = _nodes.value.filter { it.delay == -1 && it.id != _active.value }.map { it.id }.toSet()
        remove(dead)
        return dead.size
    }

    @Synchronized
    fun removeAll() {
        _nodes.value = emptyList()
        _active.value = ""
        scheduleSave()
    }

    // ───────────────────────── subscriptions ─────────────────────────

    @Synchronized
    fun addSub(url: String, name: String = ""): Sub {
        _subs.value.firstOrNull { it.url == url }?.let { return it }
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "subscription"
        val s = Sub(newId(_subs.value.map { it.id }.toSet()), name.ifBlank { host }, url)
        _subs.value = _subs.value + s
        scheduleSave()
        return s
    }

    @Synchronized
    fun updateSub(s: Sub) {
        _subs.value = _subs.value.map { if (it.id == s.id) s else it }
        scheduleSave()
    }

    @Synchronized
    fun removeSub(id: String, withNodes: Boolean) {
        _subs.value = _subs.value.filter { it.id != id }
        if (withNodes) remove(_nodes.value.filter { it.sub == id }.map { it.id }.toSet())
        else _nodes.value = _nodes.value.map { if (it.sub == id) it.copy(sub = "") else it }
        scheduleSave()
    }

    /** Replaces the configs of one subscription by a fresh list. Returns (added, removed). */
    @Synchronized
    fun applySub(subId: String, links: List<String>): Pair<Int, Int> {
        val wanted = links.map { it.trim() }.toHashSet()
        val mine = _nodes.value.filter { it.sub == subId }
        val gone = mine.filter { it.link !in wanted && it.id != _active.value }.map { it.id }.toSet()
        if (gone.isNotEmpty()) remove(gone)
        val have = _nodes.value.map { it.link }.toHashSet()
        val res = addLinks(links.filter { it.trim() !in have }, subId)
        return res.added to gone.size
    }

    // ───────────────────────── backup ─────────────────────────

    fun exportJson(withSettings: Boolean): JSONObject {
        val o = toJson()
        o.put("format", "8ray-backup")
        o.put("version", 1)
        if (withSettings) o.put("settings", SettingsStore.exportJson())
        return o
    }

    /** Merges (default) or replaces the saved configs with a backup. Returns the number of configs added. */
    @Synchronized
    fun importJson(o: JSONObject, replace: Boolean): Int {
        val backupNodes = ArrayList<Node>()
        o.optJSONArray("nodes")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { x -> nodeFrom(x)?.let(backupNodes::add) } }
        val backupSubs = ArrayList<Sub>()
        o.optJSONArray("subs")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { x -> subFrom(x)?.let(backupSubs::add) } }
        if (replace) {
            fromJson(o)
        } else {
            val haveLinks = _nodes.value.map { it.link }.toHashSet()
            val ids = _nodes.value.map { it.id }.toHashSet()
            val subIds = _subs.value.map { it.id }.toHashSet()
            val subUrls = _subs.value.map { it.url }.toHashSet()
            val subMap = HashMap<String, String>()
            val newSubs = ArrayList<Sub>()
            for (s in backupSubs) {
                if (s.url in subUrls) {
                    subMap[s.id] = _subs.value.first { it.url == s.url }.id
                } else {
                    val id = if (s.id in subIds) newId(subIds) else s.id
                    subIds.add(id)
                    subMap[s.id] = id
                    newSubs.add(s.copy(id = id))
                }
            }
            val add = ArrayList<Node>()
            for (n in backupNodes) {
                if (n.link in haveLinks) continue
                haveLinks.add(n.link)
                val id = if (n.id in ids) newId(ids) else n.id
                ids.add(id)
                add.add(n.copy(id = id, sub = subMap[n.sub] ?: "", detour = ""))
            }
            _subs.value = _subs.value + newSubs
            _nodes.value = _nodes.value + add
            if (_active.value.isEmpty()) _active.value = _nodes.value.firstOrNull()?.id ?: ""
        }
        o.optJSONObject("settings")?.let { SettingsStore.importJson(it) }
        scheduleSave()
        return backupNodes.size
    }
}
