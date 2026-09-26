package dev.remotectl.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.remotectl.app.AppViewModel
import dev.remotectl.app.PairState
import dev.remotectl.core.Device
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Two screens: the list of laptops, and one laptop. `current == null` means the list. */
@Composable
fun RootScreen(vm: AppViewModel) {
    var current by rememberSaveable { mutableStateOf<String?>(null) }
    val id = current
    if (id == null) {
        DevicesScreen(vm, onOpen = { current = it })
    } else {
        BackHandler { current = null }
        DeviceScreen(vm, id, onBack = { current = null }, onSwitch = { current = it })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesScreen(vm: AppViewModel, onOpen: (String) -> Unit) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val presence by vm.presence.collectAsStateWithLifecycle()
    val pendingInvite by vm.pendingInvite.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var showAdd by rememberSaveable { mutableStateOf(false) }

    // Keep the online dots fresh while this screen is visible.
    LaunchedEffect(devices) {
        while (true) {
            vm.refreshPresence()
            delay(15_000)
        }
    }
    LaunchedEffect(pendingInvite) { if (pendingInvite != null) showAdd = true }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Your laptops") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showAdd = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add laptop") })
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; vm.refreshPresence(); refreshing = false } },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            if (devices.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(devices, key = { it.deviceId }) { d ->
                        DeviceCard(d, presence[d.deviceId], onOpen = { onOpen(d.deviceId) }, vm = vm)
                    }
                }
            }
        }
    }

    if (showAdd) AddDeviceSheet(vm, prefill = pendingInvite, onDismiss = { showAdd = false; vm.resetPairing() })
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No laptops yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "On the laptop, run  remotectl pair  and scan the QR code it shows.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceCard(device: Device, online: Boolean?, onOpen: () -> Unit, vm: AppViewModel) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PresenceDot(online)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (online) { true -> "Online"; false -> "Offline"; null -> "Checking…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (online == true) Online else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Forget") }, onClick = { menu = false; forgetting = true })
                }
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(device.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename laptop") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(device.deviceId, text); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text("Forget ${device.name}?") },
            text = { Text("This phone will stop controlling it. To fully revoke access, also run  remotectl unpair  on the laptop.") },
            confirmButton = { TextButton(onClick = { vm.forget(device.deviceId); forgetting = false }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun PresenceDot(online: Boolean?, size: Int = 10) {
    Box(
        Modifier.size(size.dp).background(if (online == true) Online else MaterialTheme.colorScheme.outline, CircleShape),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDeviceSheet(vm: AppViewModel, prefill: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(prefill.orEmpty()) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(pairing) {
        if (pairing is PairState.Done) { delay(900); onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Add a laptop", style = MaterialTheme.typography.titleLarge)
            Text(
                "On the laptop, run  remotectl pair . It shows a QR code for a few minutes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    scanError = null
                    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { it.rawValue?.let { v -> text = v; vm.pair(v) } }
                        .addOnFailureListener { scanError = it.message ?: "Scan failed" }
                },
                enabled = pairing !is PairState.Working,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan QR code") }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Or paste the invite") },
                placeholder = { Text("remotectl://p/…") },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.pair(text) },
                enabled = text.isNotBlank() && pairing !is PairState.Working,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pair") }

            when (val p = pairing) {
                PairState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Pairing…")
                }
                is PairState.Done -> Text("Paired with ${p.name}", color = Online)
                is PairState.Error -> Text(p.message, color = MaterialTheme.colorScheme.error)
                PairState.Idle -> {}
            }
            scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SnackbarHost(snackbar)
        }
    }
}
