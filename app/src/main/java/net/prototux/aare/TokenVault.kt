package net.prototux.aare

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
 * A secret kept encrypted with a key of the Android Keystore (server handover 16.11.2026: "Token … im Android Keystore
 * bzw. verschlüsselten Einstellungen ablegen"): AES-GCM, the key never leaves the keystore, the preferences hold only
 * IV and cipher text. Read once and kept in memory - the map server's token goes with every tile.
 */
internal class TokenVault(context: Context, private val name: String) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val alias = "mate_$name"

    fun read(): String = cache.getOrPut(name) {
        val stored = prefs.getString("${name}_enc", null)
        if (stored == null) {
            // the plain value of before (16.11.2026 morning): taken over and removed
            val plain = prefs.getString(name, "").orEmpty()
            if (plain.isNotEmpty()) { write(plain); prefs.edit().remove(name).apply() }
            return plain
        }
        runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance(MODE)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV))
            String(c.doFinal(raw, IV, raw.size - IV), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun write(value: String) {
        cache[name] = value
        if (value.isEmpty()) { prefs.edit().remove("${name}_enc").remove(name).apply(); return }
        val c = Cipher.getInstance(MODE)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("${name}_enc", Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private companion object {
        const val MODE = "AES/GCM/NoPadding"
        const val IV = 12
        val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}
