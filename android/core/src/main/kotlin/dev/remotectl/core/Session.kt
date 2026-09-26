package dev.remotectl.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The laptop isn't connected to the relay right now. */
class DeviceOfflineException(name: String) : Exception("$name is offline")

/** The laptop presented a different key than the one pinned at pairing. */
class KeyMismatchException : Exception("Laptop key doesn't match the one saved at pairing. Refusing to connect.")

/** The laptop refused this phone (unpaired or revoked). */
class NotPairedException(reason: String) : Exception("Laptop rejected this phone: $reason")

class RelayRefusedException(code: Int) : Exception(
    when (code) {
        403 -> "Relay refused the credentials for this laptop"
        404 -> "This laptop isn't registered on the relay"
        else -> "Relay refused the connection ($code)"
    },
)

private sealed interface WsEvent {
    data class Text(val text: String) : WsEvent
    class Binary(val bytes: ByteArray) : WsEvent
    data class Closed(val reason: String) : WsEvent
}

/**
 * One authenticated, end-to-end encrypted connection to one laptop. Create it with [open].
 * A phone talks to several laptops by holding several sessions, one per laptop.
 */
class RemoteSession private constructor(
    val device: Device,
    val hello: Res.AuthOk,
    private val ws: WebSocket,
    private val channel: Noise.Channel,
    private val events: Channel<WsEvent>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Res>>()

    private val _pushes = MutableSharedFlow<Res>(extraBufferCapacity = 512)
    /** Unsolicited messages: live stats and terminal output. */
    val pushes: SharedFlow<Res> = _pushes

    private val _closed = MutableStateFlow<String?>(null)
    /** Non-null once the connection has ended, with the reason. */
    val closed: StateFlow<String?> = _closed

    init {
        scope.launch { readLoop() }
    }

    private suspend fun readLoop() {
        var reason = "Connection closed"
        try {
            for (ev in events) {
                when (ev) {
                    is WsEvent.Binary -> {
                        val plain = channel.open(ev.bytes) ?: continue
                        val (id, res) = parseFrame(plain)
                        val waiter = if (id != 0L) pending.remove(id) else null
                        if (waiter != null) waiter.complete(res) else _pushes.emit(res)
                    }
                    is WsEvent.Text -> if (ev.text.contains("peer_left")) { reason = "${device.name} disconnected"; break }
                    is WsEvent.Closed -> { reason = ev.reason; break }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reason = e.message ?: "Connection error"
        } finally {
            finish(reason)
        }
    }

    private fun finish(reason: String) {
        if (!_closed.compareAndSet(null, reason)) return
        pending.values.forEach { it.completeExceptionally(java.io.IOException(reason)) }
        pending.clear()
        runCatching { ws.close(1000, "bye") }
        scope.cancel()
    }

    private fun sendFrame(id: Long, req: Req) {
        val bytes = req.toJson(id).toString().toByteArray(Charsets.UTF_8)
        for (frame in channel.seal(bytes)) ws.send(frame.toByteString())
    }

    /** Sends a request and waits for its response. */
    suspend fun call(req: Req, timeoutMs: Long = 20_000): Res {
        _closed.value?.let { throw java.io.IOException(it) }
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<Res>()
        pending[id] = waiter
        try {
            sendFrame(id, req)
            return withTimeout(timeoutMs) { waiter.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Fire-and-forget, for high-frequency traffic like terminal keystrokes. */
    fun send(req: Req) {
        if (_closed.value == null) sendFrame(0, req)
    }

    fun close() = finish("Closed")

    companion object {
        /**
         * Connects through the relay, runs the Noise handshake, checks the laptop's pinned key,
         * and authenticates. Pass [pairCode] only when pairing a new laptop.
         */
        suspend fun open(
            device: Device,
            identity: KeyPair,
            http: OkHttpClient,
            pairCode: String? = null,
            waitForLaptopMs: Long = 10_000,
        ): RemoteSession {
            val events = Channel<WsEvent>(Channel.UNLIMITED)
            val opened = CompletableDeferred<Unit>()
            val url = "${device.relay.trimEnd('/')}/ws/client/${device.deviceId}?secret=${device.secret}"
            val client = http.newBuilder().pingInterval(20, TimeUnit.SECONDS).build()

            val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
                override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(WsEvent.Text(text)) }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { events.trySend(WsEvent.Binary(bytes.toByteArray())) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    events.trySend(WsEvent.Closed(reason.ifEmpty { "Connection closed" }))
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response != null) opened.completeExceptionally(RelayRefusedException(response.code))
                    else opened.completeExceptionally(t)
                    events.trySend(WsEvent.Closed(t.message ?: "Connection failed"))
                }
            })

            try {
                withTimeout(15_000) { opened.await() }

                // The relay says "peer_joined" once the laptop is in the room.
                val online = withTimeoutOrNull(waitForLaptopMs) {
                    while (true) {
                        val ev = events.receive()
                        if (ev is WsEvent.Text && ev.text.contains("peer_joined")) return@withTimeoutOrNull true
                        if (ev is WsEvent.Closed) throw java.io.IOException(ev.reason)
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                if (online != true) throw DeviceOfflineException(device.name)

                val hs = Noise.Initiator(identity)
                ws.send(hs.writeMessage1().toByteString())
                hs.readMessage2(nextBinary(events))
                ws.send(hs.writeMessage3().toByteString())
                val channel = hs.finish()

                if (!channel.remoteStatic.contentEquals(b64Decode(device.agentPub))) throw KeyMismatchException()

                // Authenticate on the raw channel before handing out a session.
                val authId = 1L
                val authFrames = channel.seal(Req.Auth(pairCode).toJson(authId).toString().toByteArray())
                authFrames.forEach { ws.send(it.toByteString()) }
                val reply = withTimeout(15_000) {
                    while (true) {
                        val plain = channel.open(nextBinary(events)) ?: continue
                        val (id, res) = parseFrame(plain)
                        if (id == authId) return@withTimeout res
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
                return when (reply) {
                    is Res.AuthOk -> RemoteSession(device, reply, ws, channel, events).also { it.nextId.set(2) }
                    is Res.AuthErr -> throw NotPairedException(reply.reason)
                    else -> throw java.io.IOException("Unexpected reply from laptop")
                }
            } catch (t: Throwable) {
                runCatching { ws.close(1000, "failed") }
                events.close()
                throw t
            }
        }

        private suspend fun nextBinary(events: Channel<WsEvent>): ByteArray {
            while (true) {
                when (val ev = events.receive()) {
                    is WsEvent.Binary -> return ev.bytes
                    is WsEvent.Closed -> throw java.io.IOException(ev.reason)
                    is WsEvent.Text -> if (ev.text.contains("peer_left")) throw java.io.IOException("Laptop disconnected")
                }
            }
        }

        /** Pair a new laptop from a scanned invite. Returns the saved [Device] on success. */
        suspend fun pair(invite: Invite, identity: KeyPair, http: OkHttpClient): Pair<Device, RemoteSession> {
            val provisional = Device(
                deviceId = invite.deviceId,
                name = "New laptop",
                relay = invite.relay,
                secret = invite.secret,
                agentPub = b64Encode(invite.agentPub),
            )
            val session = open(provisional, identity, http, pairCode = invite.code)
            return provisional.copy(name = session.hello.hostname) to session
        }
    }
}

/** Which of my laptops are online? One request per relay, however many laptops. */
object Presence {
    suspend fun check(http: OkHttpClient, devices: List<Device>, onError: (String) -> Unit = {}): Map<String, Boolean> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, Boolean>()
            for ((relay, group) in devices.groupBy { it.relay }) {
                val base = relay.trimEnd('/').replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
                for (batch in group.chunked(40)) {
                    val body = kotlinx.serialization.json.buildJsonArray {
                        batch.forEach { d ->
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("device_id", d.deviceId)
                                put("secret", d.secret)
                            })
                        }
                    }.toString()
                    val req = Request.Builder()
                        .url("$base/v1/presence")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                    try {
                        http.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) { onError("presence HTTP ${resp.code} from $base"); return@use }
                            val arr = wireJson.parseToJsonElement(resp.body.string()) as kotlinx.serialization.json.JsonArray
                            arr.forEach {
                                val o = it as kotlinx.serialization.json.JsonObject
                                result[(o["device_id"] as kotlinx.serialization.json.JsonPrimitive).content] =
                                    (o["online"] as kotlinx.serialization.json.JsonPrimitive).content == "true"
                            }
                        }
                    } catch (e: java.io.IOException) {
                        onError("presence failed for $base: ${e.message}")
                    }
                }
            }
            result
        }
}
