@file:OptIn(ExperimentalMaterial3Api::class)

package app.ray8

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun readUri(ctx: Context, uri: android.net.Uri): String =
    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

@Composable
fun ConfigsScreen(openSubs: () -> Unit, openRouting: () -> Unit, openLogs: () -> Unit, openAbout: () -> Unit, onLanguageChanged: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val s by SettingsStore.flow.collectAsStateWithLifecycle()
    val nodes by NodeStore.nodes.collectAsStateWithLifecycle()
    val subs by NodeStore.subs.collectAsStateWithLifecycle()
    val activeId by NodeStore.active.collectAsStateWithLifecycle()
    val busy by Tester.busy.collectAsStateWithLifecycle()
    val done by Tester.done.collectAsStateWithLifecycle()
    val total by Tester.total.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var addMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    var qrFor by remember { mutableStateOf<Node?>(null) }
    var editFor by remember { mutableStateOf<Node?>(null) }
    var jsonFor by remember { mutableStateOf<Node?>(null) }
    var working by remember { mutableStateOf(false) }

    fun importText(text: String) {
        scope.launch {
            working = true
            val msg = Importer.importText(ctx, text)
            working = false
            Util.toast(ctx, msg)
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { importText(it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { readUri(ctx, uri) }.getOrDefault("") }
            if (text.contains("8ray-backup")) {
                val n = runCatching { NodeStore.importJson(JSONObject(text), false) }.getOrNull()
                Util.toast(ctx, if (n != null) ctx.getString(R.string.backup_restored, n) else ctx.getString(R.string.backup_bad))
                if (n != null) onLanguageChanged()
            } else importText(text)
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(NodeStore.exportJson(true).toString(2).toByteArray()) }
            }.onSuccess { Util.toast(ctx, ctx.getString(R.string.backup_saved)) }
                .onFailure { Util.toast(ctx, ctx.getString(R.string.backup_bad)) }
        }
    }

    val shown = remember(nodes, query, filter, s.sortMode) {
        var l = nodes.filter {
            when (filter) {
                "all" -> true
                "fav" -> it.fav
                "manual" -> it.sub.isEmpty()
                else -> it.sub == filter
            }
        }
        if (query.isNotBlank()) l = l.filter { it.name.contains(query, true) || it.addr.contains(query, true) || it.kind.contains(query, true) }
        when (s.sortMode) {
            "delay" -> l.sortedBy { n -> n.delay?.let { if (it >= 0) it else Int.MAX_VALUE } ?: (Int.MAX_VALUE - 1) }
            "name" -> l.sortedBy { it.name.lowercase() }
            else -> l
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.configs), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconBtn(Ico.SCAN, {
                    scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setCaptureActivity(PortraitCaptureActivity::class.java).setOrientationLocked(false).setPrompt(""))
                })
                IconBtn(Ico.BOLT, {
                    if (busy) Tester.cancel() else Tester.testReal(ctx, shown.map { it.id })
                }, tint = if (busy) c.error else c.onSurface)
                Box {
                    IconBtn(Ico.PLUS, { addMenu = true })
                    DropdownMenu(addMenu, { addMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_clipboard)) }, onClick = {
                            addMenu = false
                            importText(Util.paste(ctx))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_file)) }, onClick = { addMenu = false; picker.launch("*/*") })
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_manual)) }, onClick = { addMenu = false; manual = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.subscriptions)) }, onClick = { addMenu = false; openSubs() })
                    }
                }
                Box {
                    IconBtn(Ico.MENU, { menuOpen = true })
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.routing)) }, onClick = { menuOpen = false; openRouting() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.subscriptions)) }, onClick = { menuOpen = false; openSubs() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.backup_save)) }, onClick = { menuOpen = false; exporter.launch("8ray-backup.json") })
                        DropdownMenuItem(text = { Text(stringResource(R.string.backup_restore)) }, onClick = { menuOpen = false; picker.launch("*/*") })
                        DropdownMenuItem(text = { Text(stringResource(R.string.open_logs)) }, onClick = { menuOpen = false; openLogs() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.about)) }, onClick = { menuOpen = false; openAbout() })
                    }
                }
                Box {
                    IconBtn(Ico.MORE, { moreMenu = true })
                    DropdownMenu(moreMenu, { moreMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.test_tcp)) }, onClick = { moreMenu = false; Tester.testTcp(shown.map { it.id }) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.sort_delay)) }, onClick = { moreMenu = false; NodeStore.sortByDelay() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.sort_by)) }, onClick = {
                            moreMenu = false
                            SettingsStore.update { it.copy(sortMode = when (it.sortMode) { "manual" -> "delay"; "delay" -> "name"; else -> "manual" }) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.remove_failed)) }, onClick = {
                            moreMenu = false
                            Util.toast(ctx, ctx.getString(R.string.removed_n, NodeStore.removeFailed()))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.export_links)) }, onClick = {
                            moreMenu = false
                            Util.copy(ctx, "8ray", shown.joinToString("\n") { it.link })
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete_all)) }, onClick = { moreMenu = false; confirmAll = true })
                    }
                }
            }
        }

        if (busy) {
            LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
            Hint(stringResource(R.string.testing_progress, done, total))
            Spacer(Modifier.height(6.dp))
        }
        if (working) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        }

        SearchField(query, { query = it }, stringResource(R.string.search))
        Spacer(Modifier.height(8.dp))
        if (nodes.isNotEmpty()) {
            val opts = buildList {
                add("all" to stringResource(R.string.filter_all) + " (${nodes.size})")
                if (nodes.any { it.fav }) add("fav" to "★")
                if (subs.isNotEmpty() && nodes.any { it.sub.isEmpty() }) add("manual" to stringResource(R.string.filter_manual))
                subs.forEach { sb -> add(sb.id to sb.name.take(18)) }
            }
            if (opts.size > 1) {
                Chips(opts, filter) { filter = it }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (nodes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon8(Ico.GLOBE, c.outline, 48.dp)
                    Text(stringResource(R.string.empty_configs), color = c.onSurfaceVariant, textAlign = TextAlign.Center)
                    TonalPill(Ico.CLIP, stringResource(R.string.add_clipboard)) { importText(Util.paste(ctx)) }
                    TonalPill(Ico.SCAN, stringResource(R.string.scan_qr)) {
                        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setCaptureActivity(PortraitCaptureActivity::class.java).setOrientationLocked(false).setPrompt(""))
                    }
                    TonalPill(Ico.LIST, stringResource(R.string.add_subscription)) { openSubs() }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { n ->
                    NodeRow(
                        n, n.id == activeId && !s.autoSelect,
                        onSelect = { TunnelService.select(ctx, n.id) },
                        onTest = { Tester.testReal(ctx, listOf(n.id)) },
                        onQr = { qrFor = n }, onEdit = { editFor = n }, onJson = { jsonFor = n },
                    )
                }
            }
        }
    }

    if (manual) TextDialog(stringResource(R.string.add_manual), stringResource(R.string.add_manual_hint), "", { manual = false }) {
        manual = false
        importText(it)
    }
    qrFor?.let { QrDialog(it.name, it.link) { qrFor = null } }
    editFor?.let { EditDialog(it) { editFor = null } }
    jsonFor?.let { n ->
        val text = remember(n.id) { runCatching { Links.parse(n.link).json.toString(2) }.getOrDefault("") }
        AlertDialog(
            onDismissRequest = { jsonFor = null }, shape = RoundedCornerShape(28.dp), containerColor = c.surfaceContainer,
            title = { Text(n.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } },
            confirmButton = { TextButton({ Util.copy(ctx, "json", text); jsonFor = null }) { Text(stringResource(R.string.copy)) } },
            dismissButton = { TextButton({ jsonFor = null }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false }, shape = RoundedCornerShape(28.dp), containerColor = c.surfaceContainer,
        title = { Text(stringResource(R.string.delete_all)) }, text = { Text(stringResource(R.string.delete_all_warn)) },
        confirmButton = { TextButton({ NodeStore.removeAll(); confirmAll = false }) { Text(stringResource(R.string.confirm), color = c.error) } },
        dismissButton = { TextButton({ confirmAll = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun delayColor(d: Int?): Color {
    val c = MaterialTheme.colorScheme
    val x = LocalExtra.current
    return when {
        d == null -> c.onSurfaceVariant
        d < 0 -> c.error
        d < 400 -> x.a
        d < 1000 -> x.busy
        else -> c.error
    }
}

@Composable
private fun NodeRow(n: Node, selected: Boolean, onSelect: () -> Unit, onTest: () -> Unit, onQr: () -> Unit, onEdit: () -> Unit, onJson: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) c.primary.copy(alpha = 0.12f) else c.surfaceContainer)
            .border(1.dp, if (selected) c.primary.copy(alpha = 0.55f) else c.outlineVariant, shape)
            .clickable(onClick = onSelect).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (selected) c.primary else c.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            if (selected) Icon8(Ico.CHECK, c.onPrimary, 14.dp, strokeW = 2.8f)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (n.fav) { Icon8(Ico.STAR, LocalExtra.current.busy, 14.dp); Spacer(Modifier.width(4.dp)) }
                Text(n.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                n.kind + "  ·  " + n.addr + if (n.detour.isNotEmpty()) "  ⛓" else "",
                fontSize = 11.sp, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            when (val d = n.delay) { null -> "—"; -1 -> "✕"; else -> "$d ms" },
            color = delayColor(n.delay), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Box {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                Icon8(Ico.MORE, c.onSurfaceVariant, 20.dp)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.test_one)) }, onClick = { menu = false; onTest() })
                DropdownMenuItem(text = { Text(stringResource(R.string.copy_link)) }, onClick = { menu = false; Util.copy(ctx, "link", n.link) })
                DropdownMenuItem(text = { Text(stringResource(R.string.share_link)) }, onClick = { menu = false; Util.share(ctx, n.link) })
                DropdownMenuItem(text = { Text(stringResource(R.string.show_qr)) }, onClick = { menu = false; onQr() })
                DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text(stringResource(R.string.show_json)) }, onClick = { menu = false; onJson() })
                DropdownMenuItem(
                    text = { Text(stringResource(if (n.fav) R.string.unfavorite else R.string.favorite)) },
                    onClick = { menu = false; NodeStore.setFav(n.id, !n.fav) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = c.error) },
                    onClick = { menu = false; NodeStore.remove(setOf(n.id)) },
                )
            }
        }
    }
}

@Composable
fun TextDialog(title: String, hint: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss, shape = RoundedCornerShape(28.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(hint)
                TextRow("", t, singleLine = false) { t = it }
                TonalPill(Ico.CLIP, stringResource(R.string.paste)) { t = Util.paste(ctx) }
            }
        },
        confirmButton = { TextButton({ onOk(t) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun QrDialog(title: String, text: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val bmp = remember(text) { Util.qr(text) }
    AlertDialog(
        onDismissRequest = onDismiss, shape = RoundedCornerShape(28.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)))
                else Text(stringResource(R.string.qr_too_long))
            }
        },
        confirmButton = {
            Row {
                TextButton({ Util.copy(ctx, "link", text) }) { Text(stringResource(R.string.copy)) }
                TextButton({ Util.share(ctx, text) }) { Text(stringResource(R.string.share)) }
                TextButton(onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}

@Composable
private fun EditDialog(n: Node, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val nodes by NodeStore.nodes.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(n.name) }
    var link by remember { mutableStateOf(n.link) }
    var detour by remember { mutableStateOf(n.detour) }
    var err by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss, shape = RoundedCornerShape(28.dp), containerColor = c.surfaceContainer,
        title = { Text(stringResource(R.string.edit), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextRow(stringResource(R.string.name), name) { name = it }
                TextRow(stringResource(R.string.link), link, singleLine = false, isError = err != null) { link = it; err = null }
                err?.let { Hint(it, error = true) }
                Label(stringResource(R.string.chain_through))
                Hint(stringResource(R.string.chain_hint))
                val dn = nodes.firstOrNull { it.id == detour }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceContainerHigh)
                        .clickable { pick = true }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(dn?.name ?: stringResource(R.string.chain_none), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (dn != null) Text("✕", Modifier.clickable { detour = "" }.padding(start = 8.dp), color = c.error)
                }
            }
        },
        confirmButton = {
            TextButton({
                if (link.trim() != n.link) {
                    val e = NodeStore.replaceLink(n.id, link)
                    if (e != null) { err = e; return@TextButton }
                }
                NodeStore.rename(n.id, name)
                NodeStore.setDetour(n.id, detour)
                if (Engine.status.value.phase == Phase.CONNECTED) Util.toast(ctx, ctx.getString(R.string.apply_next))
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (pick) AlertDialog(
        onDismissRequest = { pick = false }, shape = RoundedCornerShape(28.dp), containerColor = c.surfaceContainer,
        title = { Text(stringResource(R.string.chain_through)) },
        text = {
            LazyColumn(Modifier.height(320.dp)) {
                items(nodes.filter { it.id != n.id }, key = { it.id }) { o ->
                    Text(
                        o.name, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { detour = o.id; pick = false }.padding(12.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = { TextButton({ pick = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
