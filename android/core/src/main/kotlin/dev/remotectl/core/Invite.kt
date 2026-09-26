package dev.remotectl.core

import java.util.Base64

/**
 * What a QR code carries. Binary layout, then base64url:
 * `[ver:1][device_id:8][secret:24][agent_pub:32][code:10][relay: rest, UTF-8]`
 * Must stay in lockstep with `Invite` in crates/protocol/src/lib.rs.
 */
data class Invite(
    val relay: String,
    val deviceId: String,   // 16 hex chars
    val secret: String,     // 48 hex chars
    val agentPub: ByteArray, // 32 bytes, pinned by the phone
    val code: String,       // one-time pairing code
) {
    companion object {
        private const val PREFIX = "remotectl://p/"
        private const val VERSION = 2
        private const val CODE_LEN = 10
        private const val FIXED = 1 + 8 + 24 + 32 + CODE_LEN

        fun parse(text: String): Invite {
            val body = text.trim().removePrefix(PREFIX).also {
                require(text.trim().startsWith(PREFIX)) { "Not a remotectl invite" }
            }
            val b = try {
                Base64.getUrlDecoder().decode(body)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Corrupted invite", e)
            }
            require(b.size > FIXED && b[0].toInt() == VERSION) { "Unsupported or truncated invite" }
            fun hex(from: Int, to: Int) = b.copyOfRange(from, to).joinToString("") { "%02x".format(it) }
            return Invite(
                relay = String(b, FIXED, b.size - FIXED, Charsets.UTF_8),
                deviceId = hex(1, 9),
                secret = hex(9, 33),
                agentPub = b.copyOfRange(33, 65),
                code = String(b, 65, CODE_LEN, Charsets.US_ASCII),
            )
        }
    }
}
