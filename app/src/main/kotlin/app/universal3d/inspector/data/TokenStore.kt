package app.universal3d.inspector.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Guarda tokens de API cifrados con una clave del Android Keystore (AES-GCM). Nunca se registran ni se exportan. */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("tokens", Context.MODE_PRIVATE)
    private val alias = "u3d_token_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    fun put(id: String, token: String) {
        if (token.isBlank()) { prefs.edit().remove(id).apply(); return }
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(token.trim().toByteArray())
            prefs.edit().putString(id, Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)).apply()
        } catch (_: Exception) { prefs.edit().remove(id).apply() }
    }

    fun get(id: String): String? {
        val raw = prefs.getString(id, null) ?: return null
        return try {
            val b = Base64.decode(raw, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
            String(c.doFinal(b.copyOfRange(12, b.size)))
        } catch (_: Exception) { null }
    }

    fun has(id: String) = prefs.contains(id)
    fun clear(id: String) { prefs.edit().remove(id).apply() }
}
