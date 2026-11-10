package app.espcontrol.protocol

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto suite v1: SHA-256 / HMAC-SHA256 for authentication and key derivation,
 * ChaCha20-Poly1305 (AEAD) for session traffic. Kept in one object so a stronger
 * scheme can replace it later without touching transports or UI.
 */
object Crypto {
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { m.update(it) }
        return m.doFinal()
    }

    /** Long-term authentication key derived from the Owner Key. The Owner Key itself never leaves the phone. */
    fun authKey(ownerKey: String): ByteArray = sha256("espctl-v1|$ownerKey".toByteArray(Charsets.UTF_8))

    /** Pseudonymous owner identifier used only toward the relay server. */
    fun ownerId(authKey: ByteArray): ByteArray = sha256(authKey, "owner".toByteArray()).copyOf(8)

    fun sessionKey(authKey: ByteArray, nonceA: ByteArray, nonceD: ByteArray): ByteArray =
        hmac(authKey, "SESSION".toByteArray(), nonceA, nonceD)

    /** 12-byte AEAD nonce: [dir][0][0][0][session u32][seq u32]. Unique per key because seq never repeats per direction. */
    fun nonce(dir: Int, session: Int, seq: Int): ByteArray {
        val n = ByteArray(12)
        n[0] = dir.toByte()
        n[4] = (session ushr 24).toByte(); n[5] = (session ushr 16).toByte(); n[6] = (session ushr 8).toByte(); n[7] = session.toByte()
        n[8] = (seq ushr 24).toByte(); n[9] = (seq ushr 16).toByte(); n[10] = (seq ushr 8).toByte(); n[11] = seq.toByte()
        return n
    }

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        c.updateAAD(aad)
        return c.doFinal(plain) // ciphertext || 16-byte tag
    }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        c.updateAAD(aad)
        c.doFinal(sealed)
    } catch (e: GeneralSecurityException) {
        null
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
