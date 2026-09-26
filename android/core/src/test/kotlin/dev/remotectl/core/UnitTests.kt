package dev.remotectl.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class UnitTests {
    private class MemStorage : VaultStorage {
        var data: String? = null
        override fun read() = data
        override fun write(json: String) { data = json }
    }

    @Test
    fun `invite decodes the layout the Rust agent produces`() {
        // Built the same way as Invite::encode in crates/protocol: [2][id 8][secret 24][pub 32][code 10][relay]
        val bytes = byteArrayOf(2) +
            ByteArray(8) { it.toByte() } + ByteArray(24) { (it + 100).toByte() } +
            ByteArray(32) { (it + 200).toByte() } + "ABCDEFGH23".toByteArray() +
            "wss://relay.example.workers.dev".toByteArray()
        val text = "remotectl://p/" + b64Encode(bytes)
        val inv = Invite.parse(text)
        assertEquals("0001020304050607", inv.deviceId)
        assertEquals(48, inv.secret.length)
        assertEquals("ABCDEFGH23", inv.code)
        assertEquals("wss://relay.example.workers.dev", inv.relay)
        assertContentEquals(ByteArray(32) { (it + 200).toByte() }, inv.agentPub)
    }

    @Test
    fun `invite rejects garbage`() {
        assertFailsWith<IllegalArgumentException> { Invite.parse("https://example.com") }
        assertFailsWith<IllegalArgumentException> { Invite.parse("remotectl://p/AAAA") }
        assertFailsWith<IllegalArgumentException> { Invite.parse("remotectl://p/!!!notbase64!!!") }
    }

    @Test
    fun `frames from the agent parse`() {
        val (id, ok) = parseFrame("""{"id":1,"type":"auth_ok","hostname":"Work-MacBook","os":"macos","terminal_enabled":true}""".toByteArray())
        assertEquals(1L, id)
        assertEquals(Res.AuthOk("Work-MacBook", "macos", true), ok)

        assertIs<Res.Pong>(parseFrame("""{"id":5,"type":"pong"}""".toByteArray()).second)

        val stats = """{"id":0,"type":"stats","hostname":"h","os":"macOS 26","uptime_secs":90,"cpu_percent":12.5,
            "cpu_cores":[1.0,2.0],"load_avg":[1.0,2.0,3.0],"mem_total":100,"mem_used":50,"swap_total":0,"swap_used":0,
            "disks":[{"mount":"/","total":10,"available":4}],"net_rx_bytes":7,"net_tx_bytes":8,"battery":{"percent":50,"charging":false}}"""
        val s = (parseFrame(stats.toByteArray()).second as Res.StatsRes).stats
        assertEquals(12.5f, s.cpuPercent)
        assertEquals(50, s.battery?.percent)
        assertEquals("/", s.disks.single().mount)

        val noBattery = stats.replace(""","battery":{"percent":50,"charging":false}""", "")
        assertNull((parseFrame(noBattery.toByteArray()).second as Res.StatsRes).stats.battery)

        val apps = parseFrame("""{"id":9,"type":"apps","apps":[{"id":"com.apple.calculator","name":"Calculator","running":true}]}""".toByteArray()).second
        assertEquals("Calculator", (apps as Res.AppsRes).apps.single().name)

        val term = parseFrame("""{"id":0,"type":"term_data","term_id":1,"data":"aGk"}""".toByteArray()).second as Res.TermData
        assertEquals("hi", String(term.data))

        assertIs<Res.Unknown>(parseFrame("""{"id":0,"type":"from_the_future"}""".toByteArray()).second)
    }

    @Test
    fun `requests serialize the way the agent expects`() {
        assertEquals("""{"id":4,"type":"app_quit","app_id":"x","force":true}""", Req.AppQuit("x", true).toJson(4).toString())
        assertEquals("""{"id":1,"type":"auth","pair_code":"ABC"}""", Req.Auth("ABC").toJson(1).toString())
        assertEquals("""{"id":1,"type":"auth"}""", Req.Auth(null).toJson(1).toString())
        assertEquals("""{"id":2,"type":"stats_start","interval_ms":1000}""", Req.StatsStart(1000).toJson(2).toString())
        assertEquals("""{"id":0,"type":"term_input","term_id":1,"data":"aGk"}""", Req.TermInput(1, "hi".toByteArray()).toJson(0).toString())
    }

    @Test
    fun `one phone identity is reused across many laptops and survives restart`() {
        val storage = MemStorage()
        val v1 = Vault(storage)
        val key = v1.identity.public.copyOf()
        v1.upsert(Device("aaaa", "Work", "wss://r", "s1", "pk1"))
        v1.upsert(Device("bbbb", "Home", "wss://r", "s2", "pk2"))
        v1.upsert(Device("aaaa", "Work renamed", "wss://r", "s1", "pk1")) // re-pair replaces, no duplicate
        assertEquals(2, v1.devices.size)

        val v2 = Vault(storage) // "app restart"
        assertContentEquals(key, v2.identity.public)
        assertEquals(listOf("Work renamed", "Home").sorted(), v2.devices.map { it.name }.sorted())
        v2.remove("aaaa")
        assertEquals(listOf("bbbb"), Vault(storage).devices.map { it.deviceId })
        assertNotEquals(0, key.count { it != 0.toByte() })
    }

    @Test
    fun `x25519 public key derivation matches generated pair`() {
        val kp = Noise.generateKeyPair()
        assertContentEquals(kp.public, Noise.publicFromPrivate(kp.private))
    }
}
