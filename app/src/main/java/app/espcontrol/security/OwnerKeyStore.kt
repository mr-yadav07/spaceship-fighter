package app.espcontrol.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Owner Key encrypted with an Android Keystore key. The key is created automatically;
 * the user never has to understand encryption. The default matches the firmware default ("Astrobot").
 */
class OwnerKeyStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("owner_key", Context.MODE_PRIVATE)

    fun get(): String {
        val blob = prefs.getString(PREF, null) ?: return DEFAULT.also { set(it) }
        return decrypt(blob) ?: DEFAULT
    }

    fun set(ownerKey: String) {
        val value = ownerKey.ifBlank { DEFAULT }
        prefs.edit().putString(PREF, encrypt(value)).apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? = try {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    companion object {
        const val DEFAULT = "Astrobot"
        private const val ALIAS = "espcontrol_owner_key"
        private const val PREF = "k"
    }
}
