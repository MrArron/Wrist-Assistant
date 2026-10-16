package com.mrarron.wristclaude.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Anthropic API key encrypted with an AES-256/GCM key that lives in the
 * AndroidKeyStore (never leaves secure hardware). The key text is never logged.
 */
object SecureKeyStore {
    private const val ALIAS = "wristclaude_api_key_v1"
    private const val PREFS = "secure"
    private const val PREF_KEY = "api_key_enc"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private val _hasKey = MutableStateFlow(false)
    val hasKey: StateFlow<Boolean> = _hasKey

    fun init(ctx: Context) {
        _hasKey.value = prefs(ctx).contains(PREF_KEY)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Saves [apiKey] (whitespace removed, like settings.js). Empty clears it. */
    fun save(ctx: Context, apiKey: String): Boolean {
        val clean = apiKey.replace(Regex("\\s+"), "")
        if (clean.isEmpty()) {
            clear(ctx); return true
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ct = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
            val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
            prefs(ctx).edit { putString(PREF_KEY, stored) }
            _hasKey.value = true
            true
        } catch (_: Exception) {
            false
        }
    }

    fun load(ctx: Context): String? {
        val stored = prefs(ctx).getString(PREF_KEY, null) ?: return null
        return try {
            val (ivB64, ctB64) = stored.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Encrypts other private data (the offline notes queue, the context cache) with the same Keystore key. */
    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    fun decrypt(stored: String): String? = try {
        val (ivB64, ctB64) = stored.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit { remove(PREF_KEY) }
        _hasKey.value = false
    }
}
