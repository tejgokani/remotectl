package dev.remotectl.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.remotectl.core.VaultStorage

/**
 * Keeps the phone's identity key and paired-laptop list encrypted at rest, with the key held in
 * the Android Keystore. Backup is disabled in the manifest so the key never leaves the device.
 */
class EncryptedVaultStorage(context: Context) : VaultStorage {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "remotectl_vault",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun read(): String? = prefs.getString("vault", null)

    override fun write(json: String) {
        prefs.edit().putString("vault", json).commit()
    }
}
