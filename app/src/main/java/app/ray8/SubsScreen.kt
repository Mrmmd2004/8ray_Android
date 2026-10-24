package app.ray8

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SubsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val subs by NodeStore.subs.collectAsStateWithLifecycle()
    val nodes by NodeStore.nodes.collectAsStateWithLifecycle()
    val s by SettingsStore.flow.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Sub?>(null) }
    var deleting by remember { mutableStateOf<Sub?>(null) }
    var qrFor by remember { mutableStateOf<Sub?>(null) }

    fun update(id: String?) {
        if (busy) return
        scope.launch {
            busy = true
            if (id == null) {
                val n = Subs.updateAll(false, 1)
                Util.toast(ctx, ctx.getString(R.string.subs_updated_n, n))
            } else {
                val r = Subs.update(id)
                Util.toast(ctx, if (r.error == null) ctx.getString(R.string.sub_updated, NodeStore.sub(id)?.name ?: "", r.added) else ctx.getString(R.string.sub_failed, r.error))
            }
            busy = false
        }
    }

    Page(stringResource(R.string.subscriptions), back, actions = {
        IconBtn(Ico.REFRESH, { update(null) }, enabled = !busy && subs.isNotEmpty())
        IconBtn(Ico.PLUS, { adding = true })
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Banner(stringResource(R.string.subs_note))
            Group(stringResource(R.string.auto_update)) {
                SwitchRow(stringResource(R.string.sub_auto_update), s.subAutoUpdate) { v -> SettingsStore.update { it.copy(subAutoUpdate = v) } }
                NumberRow(stringResource(R.string.sub_hours), s.subHours) { v -> SettingsStore.update { it.copy(subHours = v) } }
            }
            if (subs.isEmpty()) Hint(stringResource(R.string.no_subs))
            subs.forEach { sb ->
                AppCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(sb.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sb.url, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.sub_configs, nodes.count { it.sub == sb.id }) + "  ·  " +
                                if (sb.updated > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(sb.updated)) else stringResource(R.string.never_updated),
                            fontSize = 12.sp, color = c.onSurfaceVariant,
                        )
                        if (sb.total > 0) {
                            val used = (sb.upload.coerceAtLeast(0) + sb.download.coerceAtLeast(0))
                            LinearProgressIndicator(progress = { (used.toFloat() / sb.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.sub_usage, fmtBytes(used), fmtBytes(sb.total)), fontSize = 12.sp, color = c.onSurfaceVariant)
                        }
                        if (sb.expire > 0) {
                            val days = (sb.expire * 1000 - System.currentTimeMillis()) / 86_400_000L
                            Text(
                                if (days >= 0) stringResource(R.string.sub_expires, days.toInt()) else stringResource(R.string.sub_expired),
                                fontSize = 12.sp, color = if (days < 3) c.error else c.onSurfaceVariant,
                            )
                        }
                        if (sb.error.isNotEmpty()) Hint(sb.error, error = true)
                        SwitchRow(stringResource(R.string.sub_auto), sb.auto) { v -> NodeStore.updateSub(sb.copy(auto = v)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TonalPill(Ico.REFRESH, stringResource(R.string.update), enabled = !busy) { update(sb.id) }
                            TonalPill(Ico.EDIT, stringResource(R.string.edit)) { editing = sb }
                            TonalPill(Ico.TRASH, stringResource(R.string.delete), danger = true) { deleting = sb }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TonalPill(Ico.QR, "QR") { qrFor = sb }
                            TonalPill(Ico.SHARE, stringResource(R.string.share)) { Util.share(ctx, sb.url) }
                            TonalPill(Ico.COPY, stringResource(R.string.copy)) { Util.copy(ctx, "url", sb.url) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (adding) TextDialog(stringResource(R.string.add_subscription), stringResource(R.string.add_sub_hint), "", { adding = false }) { text ->
        adding = false
        scope.launch {
            busy = true
            Util.toast(ctx, Importer.importText(ctx, text))
            busy = false
        }
    }
    qrFor?.let { sb -> QrDialog(sb.name, sb.url) { qrFor = null } }
    editing?.let { sb ->
        var name by remember(sb.id) { mutableStateOf(sb.name) }
        var url by remember(sb.id) { mutableStateOf(sb.url) }
        AlertDialog(
            onDismissRequest = { editing = null }, shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), containerColor = c.surfaceContainer,
            title = { Text(stringResource(R.string.edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextRow(stringResource(R.string.name), name) { name = it }
                    TextRow("URL", url, singleLine = false) { url = it }
                }
            },
            confirmButton = { TextButton({ NodeStore.updateSub(sb.copy(name = name.trim().ifEmpty { sb.name }, url = url.trim().ifEmpty { sb.url })); editing = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { sb ->
        AlertDialog(
            onDismissRequest = { deleting = null }, shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), containerColor = c.surfaceContainer,
            title = { Text(sb.name) }, text = { Text(stringResource(R.string.sub_delete_q)) },
            confirmButton = { TextButton({ NodeStore.removeSub(sb.id, true); deleting = null }) { Text(stringResource(R.string.delete_with_configs), color = c.error) } },
            dismissButton = {
                Row {
                    TextButton({ NodeStore.removeSub(sb.id, false); deleting = null }) { Text(stringResource(R.string.keep_configs)) }
                    TextButton({ deleting = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}
