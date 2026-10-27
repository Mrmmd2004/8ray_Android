package app.ray8

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(LocaleHelper.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            val s by SettingsStore.flow.collectAsStateWithLifecycle()
            val dark = isDark(s)
            DisposableEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(transparent, transparent) { dark },
                    navigationBarStyle = SystemBarStyle.auto(transparent, transparent) { dark }
                )
                onDispose { }
            }
            AppTheme(s, dark) { Root(onLanguageChanged = { recreate() }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A config link tapped in a browser or chat, or text shared to this app. */
    private fun handle(i: Intent?) {
        i ?: return
        val text = when (i.action) {
            Intent.ACTION_VIEW -> i.dataString
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!text.isNullOrBlank()) Pending.text.value = text
    }
}

private const val HOME = 0
private const val CONFIGS = 1
private const val SETTINGS = 2
private const val LOGS = 3
private const val ABOUT = 4
private const val SPLIT = 5
private const val ROUTING = 6
private const val SUBS = 7

@Composable
private fun Root(onLanguageChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stack by rememberSaveable { mutableStateOf("0") }
    val screen = stack.split(',').last().toInt()
    fun go(n: Int) { if (n != screen) stack = if (n <= SETTINGS) n.toString() else "$stack,$n" }
    fun back() {
        val parts = stack.split(',')
        stack = if (parts.size > 1) parts.dropLast(1).joinToString(",") else HOME.toString()
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    // Subscriptions refresh themselves when they are older than the chosen number of hours.
    LaunchedEffect(Unit) {
        val s = SettingsStore.current
        if (s.subAutoUpdate && NodeStore.subs.value.isNotEmpty()) withContext(Dispatchers.IO) { Subs.updateAll(true, s.subHours) }
    }

    BackHandler(enabled = stack != "0") {
        if (screen <= SETTINGS) stack = HOME.toString() else back()
    }

    val pending by Pending.text.collectAsStateWithLifecycle()
    pending?.let { text ->
        AlertDialog(
            onDismissRequest = { Pending.text.value = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.import_title)) },
            text = { Text(text.take(300), maxLines = 6, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    Pending.text.value = null
                    scope.launch {
                        Util.toast(ctx, Importer.importText(ctx, text))
                        go(CONFIGS)
                    }
                }) { Text(stringResource(R.string.add)) }
            },
            dismissButton = { TextButton(onClick = { Pending.text.value = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    val glow = LocalExtra.current.a
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(glow.copy(alpha = 0.10f), Color.Transparent), endY = 900f))
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                        label = "nav"
                    ) { sc ->
                        Box(Modifier.fillMaxSize().then(if (sc <= SETTINGS) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
                        when (sc) {
                            HOME -> HomeScreen(openConfigs = { go(CONFIGS) }, openLogs = { go(LOGS) }, openRouting = { go(ROUTING) })
                            CONFIGS -> ConfigsScreen(
                                openSubs = { go(SUBS) }, openRouting = { go(ROUTING) }, openLogs = { go(LOGS) },
                                openAbout = { go(ABOUT) }, onLanguageChanged = onLanguageChanged,
                            )
                            SETTINGS -> SettingsScreen(
                                openLogs = { go(LOGS) }, openAbout = { go(ABOUT) }, openSplit = { go(SPLIT) },
                                openRouting = { go(ROUTING) }, openSubs = { go(SUBS) }, onLanguageChanged = onLanguageChanged,
                            )
                            LOGS -> LogsScreen(back = ::back)
                            ABOUT -> AboutScreen(back = ::back)
                            SPLIT -> SplitScreen(back = ::back)
                            ROUTING -> RoutingScreen(back = ::back)
                            SUBS -> SubsScreen(back = ::back)
                        }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = screen <= SETTINGS,
                    enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
                ) { BottomBar(screen) { go(it) } }
            }
        }
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(26.dp)
    Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(c.surfaceContainer).border(1.dp, c.outlineVariant, shape).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val items = listOf(
                Triple(HOME, Ico.HOME, R.string.nav_home),
                Triple(CONFIGS, Ico.LIST, R.string.configs),
                Triple(SETTINGS, Ico.SETTINGS, R.string.settings),
            )
            items.forEach { (id, ico, label) ->
                val sel = id == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .background(if (sel) c.primary.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { onSelect(id) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon8(ico, if (sel) c.primary else c.onSurfaceVariant, 22.dp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(label), fontSize = 11.sp, maxLines = 1,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (sel) c.primary else c.onSurfaceVariant
                    )
                }
            }
        }
    }
}
