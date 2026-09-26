package dev.remotectl.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.remotectl.app.AppViewModel
import dev.remotectl.app.ConnState
import dev.remotectl.app.DeviceUi
import dev.remotectl.app.requireAuth
import dev.remotectl.core.AppInfo
import dev.remotectl.core.Stats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(vm: AppViewModel, deviceId: String, onBack: () -> Unit, onSwitch: (String) -> Unit) {
    val ui by vm.active.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val presence by vm.presence.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Opening (or switching to) a laptop connects to it; leaving the screen disconnects.
    LaunchedEffect(deviceId) { vm.open(deviceId) }
    DisposableEffect(Unit) { onDispose { vm.closeActive() } }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val current = ui?.takeIf { it.device.deviceId == deviceId }
    val name = current?.device?.name ?: devices.firstOrNull { it.deviceId == deviceId }?.name.orEmpty()
    var switcher by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    // Tap the name to jump to another laptop without going back to the list.
                    Box {
                        Row(
                            Modifier.clickable { switcher = true }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Icon(Icons.Filled.KeyboardArrowDown, "Switch laptop")
                        }
                        DropdownMenu(expanded = switcher, onDismissRequest = { switcher = false }) {
                            devices.forEach { d ->
                                DropdownMenuItem(
                                    leadingIcon = { PresenceDot(if (d.deviceId == deviceId) true else presence[d.deviceId]) },
                                    text = { Text(d.name, fontWeight = if (d.deviceId == deviceId) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { switcher = false; if (d.deviceId != deviceId) onSwitch(d.deviceId) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ConnectionBanner(current, name, onRetry = { vm.open(deviceId) })
            PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                listOf("Overview", "Apps", "Terminal").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            if (current == null || current.conn !is ConnState.Connected) {
                Box(Modifier.fillMaxSize())
            } else when (tab) {
                0 -> OverviewTab(current, vm)
                1 -> AppsTab(current, vm)
                else -> TerminalTab(current, vm)
            }
        }
    }
}

@Composable
private fun ConnectionBanner(ui: DeviceUi?, name: String, onRetry: () -> Unit) {
    when (val c = ui?.conn ?: ConnState.Connecting) {
        ConnState.Connecting -> Column {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Connecting to $name…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ConnState.Connected -> {}
        is ConnState.Failed -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.reason, color = MaterialTheme.colorScheme.onErrorContainer)
                if (c.reason.contains("offline")) {
                    Text(
                        "Make sure the laptop is awake, online, and running the remotectl agent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                TextButton(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Overview
// ---------------------------------------------------------------------------------------

@Composable
private fun OverviewTab(ui: DeviceUi, vm: AppViewModel) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = vm::lock, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Lock, null)
            Spacer(Modifier.width(8.dp))
            Text("Lock screen")
        }
        val s = ui.stats
        if (s == null) {
            Text("Waiting for stats…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            StatsCards(s)
        }
        ProcessCard(ui, vm)
    }
}

@Composable
private fun StatCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun Meter(fraction: Float) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        trackColor = MaterialTheme.colorScheme.outlineVariant,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
private fun StatsCards(s: Stats) {
    Text("${s.hostname} · ${s.os} · up ${uptime(s.uptimeSecs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    StatCard("CPU") {
        Text(pct(s.cpuPercent), style = MaterialTheme.typography.headlineMedium)
        Meter(s.cpuPercent / 100f)
        // one bar per core
        Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            s.cpuCores.forEach { c ->
                Box(
                    Modifier.weight(1f).fillMaxHeight(((c / 100f).coerceIn(0.04f, 1f)))
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                )
            }
        }
        if (s.loadAvg.size == 3) {
            Text("Load ${"%.2f".format(s.loadAvg[0])}  ${"%.2f".format(s.loadAvg[1])}  ${"%.2f".format(s.loadAvg[2])}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    StatCard("Memory") {
        Text("${bytes(s.memUsed)} of ${bytes(s.memTotal)}", style = MaterialTheme.typography.titleLarge)
        Meter(if (s.memTotal > 0) s.memUsed.toFloat() / s.memTotal else 0f)
        if (s.swapTotal > 0) Text("Swap ${bytes(s.swapUsed)} of ${bytes(s.swapTotal)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    s.battery?.let { b ->
        StatCard("Battery") {
            Text("${b.percent}%${if (b.charging) "  ·  plugged in" else ""}", style = MaterialTheme.typography.titleLarge)
            Meter(b.percent / 100f)
        }
    }
    StatCard("Storage") {
        s.disks.forEach { d ->
            val used = d.total - d.available
            Text("${d.mount}  ·  ${bytes(d.available)} free of ${bytes(d.total)}", style = MaterialTheme.typography.bodyMedium)
            Meter(if (d.total > 0) used.toFloat() / d.total else 0f)
        }
    }
    StatCard("Network (since boot)") {
        Text("↓ ${bytes(s.netRxBytes)}    ↑ ${bytes(s.netTxBytes)}", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ProcessCard(ui: DeviceUi, vm: AppViewModel) {
    StatCard("Busiest processes") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = vm::refreshProcesses) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        ui.processes.take(10).forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pct(p.cpuPercent), Modifier.width(56.dp), color = MaterialTheme.colorScheme.primary)
                Text(bytes(p.memBytes), Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Apps
// ---------------------------------------------------------------------------------------

@Composable
private fun AppsTab(ui: DeviceUi, vm: AppViewModel) {
    val activity = LocalContext.current as FragmentActivity
    var query by rememberSaveable { mutableStateOf("") }
    val apps = ui.apps

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = vm::refreshApps) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        if (apps == null) {
            Text("Loading apps…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val shown = apps.filter { it.name.contains(query, ignoreCase = true) }
                .sortedWith(compareByDescending<AppInfo> { it.running }.thenBy { it.name.lowercase() })
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.id }) { app -> AppRow(app, vm, activity) }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppInfo, vm: AppViewModel, activity: FragmentActivity) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PresenceDot(app.running, size = 8)
        Spacer(Modifier.width(12.dp))
        Text(app.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (app.running) {
            TextButton(onClick = { vm.quitApp(app, force = false) }) { Text("Quit") }
            TextButton(onClick = { activity.requireAuth("Force quit ${app.name}") { vm.quitApp(app, force = true) } }) {
                Text("Force", color = MaterialTheme.colorScheme.error)
            }
        } else {
            OutlinedButton(onClick = { vm.launchApp(app) }) { Text("Open") }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Terminal
// ---------------------------------------------------------------------------------------

@Composable
private fun TerminalTab(ui: DeviceUi, vm: AppViewModel) {
    val activity = LocalContext.current as FragmentActivity
    var unlocked by rememberSaveable(ui.device.deviceId) { mutableStateOf(false) }

    when {
        !ui.terminalEnabled -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Terminal is off on this laptop", style = MaterialTheme.typography.titleMedium)
            Text(
                "It's opt-in for safety. To turn it on, run this on the laptop, then reopen it here:",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("remotectl config set terminal on", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        !unlocked -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Remote terminal", style = MaterialTheme.typography.titleMedium)
            Text("Runs commands as you on ${ui.device.name}. Confirm it's you first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { activity.requireAuth("Open terminal on ${ui.device.name}") { unlocked = true } }) { Text("Unlock terminal") }
        }
        else -> TerminalScreen(ui, vm)
    }
}
