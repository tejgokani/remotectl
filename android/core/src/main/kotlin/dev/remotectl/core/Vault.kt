package dev.remotectl.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A paired laptop. The phone pins [agentPub]; a different key on the wire is refused. */
@Serializable
data class Device(
    val deviceId: String,
    val name: String,
    val relay: String,
    val secret: String,
    val agentPub: String, // base64url
)

@Serializable
private data class VaultData(
    val privateKey: String,
    val publicKey: String,
    val devices: List<Device> = emptyList(),
)

/** Where the vault's JSON lives. The Android app backs this with encrypted storage. */
interface VaultStorage {
    fun read(): String?
    fun write(json: String)
}

/**
 * The phone's identity (one keypair, reused across every laptop) plus its list of paired
 * laptops. This is what makes "one phone, many devices" work: each laptop pins this same
 * public key, and the phone pins each laptop's key.
 */
class Vault(private val storage: VaultStorage) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private var data: VaultData = load()

    private fun load(): VaultData {
        storage.read()?.let { runCatching { return json.decodeFromString<VaultData>(it) } }
        val kp = Noise.generateKeyPair()
        return VaultData(b64Encode(kp.private), b64Encode(kp.public)).also { save(it) }
    }

    private fun save(d: VaultData) {
        data = d
        storage.write(json.encodeToString(d))
    }

    val identity: KeyPair get() = KeyPair(b64Decode(data.privateKey), b64Decode(data.publicKey))

    @get:Synchronized val devices: List<Device> get() = data.devices

    @Synchronized
    fun upsert(device: Device) {
        save(data.copy(devices = data.devices.filter { it.deviceId != device.deviceId } + device))
    }

    @Synchronized
    fun remove(deviceId: String) {
        save(data.copy(devices = data.devices.filter { it.deviceId != deviceId }))
    }

    @Synchronized
    fun rename(deviceId: String, name: String) {
        save(data.copy(devices = data.devices.map { if (it.deviceId == deviceId) it.copy(name = name) else it }))
    }
}
