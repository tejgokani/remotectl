package dev.remotectl.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.remotectl.core.AppInfo
import dev.remotectl.core.Device
import dev.remotectl.core.Invite
import dev.remotectl.core.Presence
import dev.remotectl.core.ProcessInfo
import dev.remotectl.core.RemoteSession
import dev.remotectl.core.Req
import dev.remotectl.core.Res
import dev.remotectl.core.Stats
import dev.remotectl.core.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

sealed interface ConnState {
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Failed(val reason: String) : ConnState
}

/** Everything the device screen shows about the laptop that's currently open. */
data class DeviceUi(
    val device: Device,
    val conn: ConnState = ConnState.Connecting,
    val terminalEnabled: Boolean = false,
    val terminalOpen: Boolean = false,
    val stats: Stats? = null,
    val processes: List<ProcessInfo> = emptyList(),
    val apps: List<AppInfo>? = null,
)

sealed interface PairState {
    data object Idle : PairState
    data object Working : PairState
    data class Done(val name: String) : PairState
    data class Error(val message: String) : PairState
}

private const val TERM_ID = 1

/**
 * Owns the phone's vault and at most one live laptop session at a time. Switching laptops
 * closes the current session and opens the next; each laptop is its own relay room, so there
 * is no limit on how many laptops one phone can have paired.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val vault = Vault(EncryptedVaultStorage(app))
    private val http = OkHttpClient()

    private val _devices = MutableStateFlow(vault.devices)
    val devices: StateFlow<List<Device>> = _devices

    private val _presence = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** deviceId -> online. A missing key means "don't know yet". */
    val presence: StateFlow<Map<String, Boolean>> = _presence

    private val _active = MutableStateFlow<DeviceUi?>(null)
    val active: StateFlow<DeviceUi?> = _active

    private val _pairing = MutableStateFlow<PairState>(PairState.Idle)
    val pairing: StateFlow<PairState> = _pairing

    /** An invite that arrived via a link; the UI shows the pairing sheet pre-filled with it. */
    val pendingInvite = MutableStateFlow<String?>(null)

    /** One-line notices for the snackbar. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Raw terminal bytes from the laptop, consumed by the terminal view. */
    val termOutput: SharedFlow<ByteArray> get() = _termOutput
    private val _termOutput = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)

    private var session: RemoteSession? = null
    private var sessionJob: Job? = null

    private fun update(f: (DeviceUi) -> DeviceUi) = _active.update { it?.let(f) }

    // ---- laptop list ---------------------------------------------------------------------

    suspend fun refreshPresence() {
        val current = vault.devices
        if (current.isEmpty()) return
        val result = Presence.check(http, current)
        _presence.value = result
    }

    fun forget(deviceId: String) {
        if (_active.value?.device?.deviceId == deviceId) closeActive()
        vault.remove(deviceId)
        _devices.value = vault.devices
    }

    fun rename(deviceId: String, name: String) {
        vault.rename(deviceId, name.trim().ifEmpty { return })
        _devices.value = vault.devices
    }

    // ---- pairing -------------------------------------------------------------------------

    fun pair(inviteText: String) {
        _pairing.value = PairState.Working
        viewModelScope.launch {
            try {
                val invite = Invite.parse(inviteText)
                val (device, s) = RemoteSession.pair(invite, vault.identity, http)
                s.close()
                vault.upsert(device)
                _devices.value = vault.devices
                _pairing.value = PairState.Done(device.name)
                refreshPresence()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _pairing.value = PairState.Error(e.message ?: "Pairing failed")
            }
        }
    }

    fun resetPairing() {
        _pairing.value = PairState.Idle
        pendingInvite.value = null
    }

    // ---- the open laptop -----------------------------------------------------------------

    fun open(deviceId: String) {
        val device = vault.devices.firstOrNull { it.deviceId == deviceId } ?: return
        closeActive()
        _active.value = DeviceUi(device)
        sessionJob = viewModelScope.launch {
            try {
                val s = RemoteSession.open(device, vault.identity, http)
                session = s
                update { it.copy(conn = ConnState.Connected, terminalEnabled = s.hello.terminalEnabled) }
                launch { s.pushes.collect(::onPush) }
                launch {
                    val reason = s.closed.filterNotNull().first()
                    update { it.copy(conn = ConnState.Failed(reason), terminalOpen = false) }
                }
                (s.call(Req.StatsStart(2000)) as? Res.StatsRes)?.let { r -> update { it.copy(stats = r.stats) } }
                refreshApps()
                refreshProcesses()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(conn = ConnState.Failed(e.message ?: "Couldn't connect")) }
            }
        }
    }

    fun closeActive() {
        sessionJob?.cancel()
        sessionJob = null
        session?.let { s ->
            runCatching { s.send(Req.StatsStop) }
            s.close()
        }
        session = null
        _active.value = null
    }

    private fun onPush(res: Res) {
        when (res) {
            is Res.StatsRes -> update { it.copy(stats = res.stats) }
            is Res.TermData -> _termOutput.tryEmit(res.data)
            is Res.TermExit -> {
                update { it.copy(terminalOpen = false) }
                messages.tryEmit("Terminal closed")
            }
            else -> {}
        }
    }

    private fun act(label: String, req: Req, then: (suspend () -> Unit)? = null) {
        val s = session ?: return
        viewModelScope.launch {
            try {
                when (val r = s.call(req)) {
                    is Res.Done -> { messages.emit(label); then?.invoke() }
                    is Res.Error -> messages.emit(r.message)
                    else -> {}
                }
            } catch (e: Exception) {
                messages.emit(e.message ?: "Failed")
            }
        }
    }

    fun lock() = act("Screen locked", Req.Lock)

    fun launchApp(app: AppInfo) = act("Opening ${app.name}", Req.AppLaunch(app.id)) {
        delay(1500); refreshApps()
    }

    fun quitApp(app: AppInfo, force: Boolean) = act(
        if (force) "Force quit ${app.name}" else "Quitting ${app.name}",
        Req.AppQuit(app.id, force),
    ) { delay(1500); refreshApps() }

    fun refreshApps() {
        val s = session ?: return
        viewModelScope.launch {
            (runCatching { s.call(Req.Apps) }.getOrNull() as? Res.AppsRes)?.let { r -> update { it.copy(apps = r.apps) } }
        }
    }

    fun refreshProcesses() {
        val s = session ?: return
        viewModelScope.launch {
            (runCatching { s.call(Req.Processes) }.getOrNull() as? Res.ProcessesRes)?.let { r ->
                update { it.copy(processes = r.processes) }
            }
        }
    }

    // ---- terminal ------------------------------------------------------------------------

    fun openTerminal(cols: Int, rows: Int) {
        val s = session ?: return
        viewModelScope.launch {
            try {
                when (val r = s.call(Req.TermOpen(TERM_ID, cols, rows))) {
                    is Res.Done -> update { it.copy(terminalOpen = true) }
                    is Res.Error -> messages.emit(r.message)
                    else -> {}
                }
            } catch (e: Exception) {
                messages.emit(e.message ?: "Couldn't open terminal")
            }
        }
    }

    fun termInput(bytes: ByteArray) { session?.send(Req.TermInput(TERM_ID, bytes)) }
    fun termResize(cols: Int, rows: Int) { session?.send(Req.TermResize(TERM_ID, cols, rows)) }
    fun closeTerminal() {
        session?.send(Req.TermClose(TERM_ID))
        update { it.copy(terminalOpen = false) }
    }

    override fun onCleared() {
        closeActive()
    }
}
