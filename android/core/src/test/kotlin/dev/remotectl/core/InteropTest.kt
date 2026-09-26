package dev.remotectl.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Talks to a REAL remotectl agent through a REAL relay. Skipped unless REMOTECTL_INVITE is set:
 *   remotectl pair --relay ws://127.0.0.1:8788   (copy the invite)
 *   remotectl run
 *   REMOTECTL_INVITE='remotectl://p/...' ./gradlew :core:test
 * Optionally REMOTECTL_TERMINAL=1 if the agent has `config set terminal on`.
 */
class InteropTest {
    private val http = OkHttpClient()

    private class MemStorage : VaultStorage {
        var data: String? = null
        override fun read() = data
        override fun write(json: String) { data = json }
    }

    @Test
    fun `pair, then reconnect, then use every feature`() = runBlocking {
        val text = System.getenv("REMOTECTL_INVITE").orEmpty()
        assumeTrue(text.startsWith("remotectl://p/"), "REMOTECTL_INVITE not set; skipping interop test")

        val vault = Vault(MemStorage())
        val invite = Invite.parse(text)

        // 1. Pair: full Noise_XX handshake against Rust `snow`, plus one-time code.
        val (device, first) = RemoteSession.pair(invite, vault.identity, http)
        println("paired with '${device.name}' (${first.hello.os}), terminal=${first.hello.terminalEnabled}")
        vault.upsert(device)
        first.close()

        // 2. The one-time code is spent: reconnecting works by key alone.
        val s = RemoteSession.open(vault.devices.single(), vault.identity, http)

        assertIs<Res.Pong>(s.call(Req.Ping))

        // Live stats: first snapshot is the reply, then pushes arrive on the flow.
        val snap = s.call(Req.StatsStart(1000))
        assertIs<Res.StatsRes>(snap)
        assertTrue(snap.stats.memTotal > 0 && snap.stats.cpuCores.isNotEmpty())
        val pushed = withTimeout(5_000) { s.pushes.first { it is Res.StatsRes } }
        assertIs<Res.StatsRes>(pushed)
        assertIs<Res.Done>(s.call(Req.StatsStop))

        val procs = s.call(Req.Processes)
        assertIs<Res.ProcessesRes>(procs)
        assertTrue(procs.processes.isNotEmpty())

        val apps = s.call(Req.Apps)
        assertIs<Res.AppsRes>(apps)
        println("apps: ${apps.apps.size}")
        assertTrue(apps.apps.isNotEmpty())

        // An id that isn't in the scan must be refused, not executed.
        val bad = s.call(Req.AppLaunch("x\"; do shell script \"id"))
        assertIs<Res.Error>(bad)

        if (System.getenv("REMOTECTL_TERMINAL") == "1") {
            assertIs<Res.Done>(s.call(Req.TermOpen(1, 80, 24)))
            s.send(Req.TermInput(1, "echo kotlin-$((6*7))\n".toByteArray()))
            val out = StringBuilder()
            withTimeout(8_000) {
                s.pushes.first { r ->
                    if (r is Res.TermData) out.append(String(r.data))
                    out.contains("kotlin-42")
                }
            }
            assertTrue(out.contains("kotlin-42"))
            assertIs<Res.Done>(s.call(Req.TermClose(1)))
        } else {
            val denied = s.call(Req.TermOpen(1, 80, 24))
            assertIs<Res.Error>(denied) // terminal is opt-in and off by default
        }
        s.close()

        // 3. Pinned key: an impostor's key must be refused.
        val tampered = vault.devices.single().copy(agentPub = b64Encode(Noise.generateKeyPair().public))
        assertFailsWith<KeyMismatchException> { RemoteSession.open(tampered, vault.identity, http) }

        // 4. A different phone identity without the pair code must be refused by the laptop.
        val stranger = Vault(MemStorage())
        assertFailsWith<NotPairedException> { RemoteSession.open(vault.devices.single(), stranger.identity, http) }

        // 5. Presence sees the laptop online.
        assertEquals(true, Presence.check(http, vault.devices) { println("PRESENCE-ERR $it") }[device.deviceId])
    }
}
