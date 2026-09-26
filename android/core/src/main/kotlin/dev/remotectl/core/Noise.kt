package dev.remotectl.core

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

/**
 * Noise_XX_25519_ChaChaPoly_BLAKE2s, initiator side. Interoperates with the Rust `snow`
 * responder in the laptop agent. The phone is always the initiator.
 */
class NoiseException(message: String, cause: Throwable? = null) : Exception(message, cause)

class KeyPair(val private: ByteArray, val public: ByteArray)

object Noise {
    const val PROTOCOL = "Noise_XX_25519_ChaChaPoly_BLAKE2s"
    private const val HASHLEN = 32
    private const val DHLEN = 32
    private const val TAGLEN = 16
    private val random = SecureRandom()

    fun generateKeyPair(): KeyPair {
        val sk = ByteArray(32)
        X25519.generatePrivateKey(random, sk)
        val pk = ByteArray(32)
        X25519.generatePublicKey(sk, 0, pk, 0)
        return KeyPair(sk, pk)
    }

    fun publicFromPrivate(sk: ByteArray): ByteArray = ByteArray(32).also { X25519.generatePublicKey(sk, 0, it, 0) }

    private fun dh(sk: ByteArray, pk: ByteArray): ByteArray {
        val out = ByteArray(32)
        if (!X25519.calculateAgreement(sk, 0, pk, 0, out, 0)) throw NoiseException("invalid DH result")
        return out
    }

    private fun hash(vararg parts: ByteArray): ByteArray {
        val d = Blake2sDigest()
        for (p in parts) d.update(p, 0, p.size)
        return ByteArray(HASHLEN).also { d.doFinal(it, 0) }
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = HMac(Blake2sDigest())
        m.init(KeyParameter(key))
        for (p in parts) m.update(p, 0, p.size)
        return ByteArray(HASHLEN).also { m.doFinal(it, 0) }
    }

    /** HKDF with two outputs, as defined in the Noise spec. */
    private fun hkdf2(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = hmac(chainingKey, ikm)
        val out1 = hmac(temp, byteArrayOf(0x01))
        val out2 = hmac(temp, out1, byteArrayOf(0x02))
        return out1 to out2
    }

    private fun nonce(n: Long): ByteArray {
        val b = ByteArray(12) // 32 zero bits, then the 64-bit counter little-endian
        for (i in 0 until 8) b[4 + i] = (n ushr (8 * i)).toByte()
        return b
    }

    private fun aeadEncrypt(k: ByteArray, n: Long, ad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, ParametersWithIV(KeyParameter(k), nonce(n)))
        c.processAADBytes(ad, 0, ad.size)
        val out = ByteArray(c.getOutputSize(plaintext.size))
        var len = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += c.doFinal(out, len)
        return out.copyOf(len)
    }

    private fun aeadDecrypt(k: ByteArray, n: Long, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < TAGLEN) throw NoiseException("ciphertext too short")
        val c = ChaCha20Poly1305()
        c.init(false, ParametersWithIV(KeyParameter(k), nonce(n)))
        c.processAADBytes(ad, 0, ad.size)
        val out = ByteArray(c.getOutputSize(ciphertext.size))
        try {
            var len = c.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            len += c.doFinal(out, len)
            return out.copyOf(len)
        } catch (e: InvalidCipherTextException) {
            throw NoiseException("decryption failed", e)
        }
    }

    /** One direction of the transport: a key and a strictly increasing nonce. */
    class CipherState(private val key: ByteArray) {
        private var n = 0L

        fun encrypt(plaintext: ByteArray): ByteArray = aeadEncrypt(key, n++, ByteArray(0), plaintext)
        fun decrypt(ciphertext: ByteArray): ByteArray = aeadDecrypt(key, n++, ByteArray(0), ciphertext)
    }

    private class SymmetricState {
        var ck: ByteArray
        var h: ByteArray
        private var k: ByteArray? = null
        private var n = 0L

        init {
            val name = PROTOCOL.toByteArray()
            h = if (name.size <= HASHLEN) name.copyOf(HASHLEN) else hash(name)
            ck = h
            mixHash(ByteArray(0)) // empty prologue
        }

        fun mixHash(data: ByteArray) {
            h = hash(h, data)
        }

        fun mixKey(ikm: ByteArray) {
            val (newCk, tempK) = hkdf2(ck, ikm)
            ck = newCk
            k = tempK
            n = 0
        }

        fun encryptAndHash(plaintext: ByteArray): ByteArray {
            val key = k ?: return plaintext.also { mixHash(it) }
            val ct = aeadEncrypt(key, n++, h, plaintext)
            mixHash(ct)
            return ct
        }

        fun decryptAndHash(ciphertext: ByteArray): ByteArray {
            val key = k ?: return ciphertext.also { mixHash(it) }
            val pt = aeadDecrypt(key, n++, h, ciphertext)
            mixHash(ciphertext)
            return pt
        }

        fun split(): Pair<CipherState, CipherState> {
            val (k1, k2) = hkdf2(ck, ByteArray(0))
            return CipherState(k1) to CipherState(k2)
        }
    }

    /** Initiator handshake. Call [writeMessage1], [readMessage2], [writeMessage3], then [finish]. */
    class Initiator(private val s: KeyPair) {
        private val ss = SymmetricState()
        private val e = generateKeyPair()
        private var re: ByteArray? = null
        private var rs: ByteArray? = null
        private var sent3 = false
        private var send: CipherState? = null
        private var recv: CipherState? = null

        /** -> e */
        fun writeMessage1(): ByteArray {
            ss.mixHash(e.public)
            val payload = ss.encryptAndHash(ByteArray(0)) // no key yet: just hashes the empty payload
            return e.public + payload
        }

        /** <- e, ee, s, es */
        fun readMessage2(msg: ByteArray) {
            if (msg.size < DHLEN + DHLEN + TAGLEN + TAGLEN) throw NoiseException("handshake message 2 too short")
            var off = 0
            val remoteEphemeral = msg.copyOfRange(off, off + DHLEN).also { off += DHLEN }
            ss.mixHash(remoteEphemeral)
            re = remoteEphemeral
            ss.mixKey(dh(e.private, remoteEphemeral))                         // ee
            val remoteStatic = ss.decryptAndHash(msg.copyOfRange(off, off + DHLEN + TAGLEN)).also { off += DHLEN + TAGLEN }
            rs = remoteStatic
            ss.mixKey(dh(e.private, remoteStatic))                            // es
            ss.decryptAndHash(msg.copyOfRange(off, msg.size))                 // empty payload + tag
        }

        /** -> s, se */
        fun writeMessage3(): ByteArray {
            val remoteEphemeral = re ?: throw NoiseException("message 2 not read yet")
            val encStatic = ss.encryptAndHash(s.public)
            ss.mixKey(dh(s.private, remoteEphemeral))                         // se
            val payload = ss.encryptAndHash(ByteArray(0))
            val (c1, c2) = ss.split()
            send = c1
            recv = c2
            sent3 = true
            return encStatic + payload
        }

        fun finish(): Channel {
            if (!sent3) throw NoiseException("handshake not complete")
            return Channel(send!!, recv!!, rs!!)
        }
    }

    /** Encrypted transport with transparent fragmentation, mirroring the Rust `Channel`. */
    class Channel(private val send: CipherState, private val recv: CipherState, val remoteStatic: ByteArray) {
        private val partial = java.io.ByteArrayOutputStream()

        @Synchronized
        fun seal(plaintext: ByteArray): List<ByteArray> {
            val chunks = if (plaintext.isEmpty()) {
                listOf(ByteArray(0))
            } else {
                (plaintext.indices step CHUNK).map { plaintext.copyOfRange(it, minOf(it + CHUNK, plaintext.size)) }
            }
            return chunks.mapIndexed { i, c ->
                val flag: Byte = if (i != chunks.lastIndex) 1 else 0 // 1 = more fragments follow
                send.encrypt(byteArrayOf(flag) + c)
            }
        }

        /** Decrypts one wire frame; returns the full message once its last fragment arrives. */
        @Synchronized
        fun open(frame: ByteArray): ByteArray? {
            val buf = recv.decrypt(frame)
            if (buf.isEmpty()) throw NoiseException("empty frame")
            partial.write(buf, 1, buf.size - 1)
            if (partial.size() > MAX_MESSAGE) throw NoiseException("message too large")
            if (buf[0].toInt() == 1) return null
            val out = partial.toByteArray()
            partial.reset()
            return out
        }

        private companion object {
            const val CHUNK = 60_000
            const val MAX_MESSAGE = 8 * 1024 * 1024
        }
    }
}
