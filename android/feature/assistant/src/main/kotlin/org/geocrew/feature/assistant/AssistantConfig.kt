package org.geocrew.feature.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.geocrew.core.model.action.AiPermissions
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The assistant's settings, on this phone only. The API key is the user's
 * own; nothing is built in. It is encrypted with a key held by the Android
 * keystore, so a copied preferences file does not reveal it.
 */
@Singleton
class AssistantConfig @Inject constructor(@ApplicationContext context: Context) {
    data class Values(
        val baseUrl: String = DEFAULT_BASE,
        val model: String = "",
        val hasKey: Boolean = false,
        val permissions: AiPermissions = AiPermissions(),
        val speak: Boolean = true,
        val rate: Float = 1.0f,
        val handsFree: Boolean = false,
    ) {
        val ready: Boolean get() = hasKey && model.isNotBlank() && baseUrl.startsWith("http")
    }

    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values.asStateFlow()

    private fun read() = Values(
        baseUrl = prefs.getString("base_url", null) ?: DEFAULT_BASE,
        model = prefs.getString("model", null).orEmpty(),
        hasKey = prefs.contains("key"),
        permissions = runCatching { AiPermissions.fromJson(Json.parseToJsonElement(prefs.getString("permissions", "{}")!!) as JsonObject) }.getOrDefault(AiPermissions()),
        speak = prefs.getBoolean("speak", true),
        rate = prefs.getFloat("rate", 1.0f),
        handsFree = prefs.getBoolean("hands_free", false),
    )

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _values.value = read()
    }

    fun setEndpoint(baseUrl: String, model: String) = edit {
        putString("base_url", baseUrl.trim().trimEnd('/').ifEmpty { DEFAULT_BASE })
        putString("model", model.trim())
    }

    fun setKey(key: String?) = edit {
        if (key.isNullOrBlank()) remove("key") else putString("key", encrypt(key.trim()))
    }

    fun key(): String? = prefs.getString("key", null)?.let { runCatching { decrypt(it) }.getOrNull() }

    fun setPermissions(p: AiPermissions) = edit { putString("permissions", p.toJson().toString()) }
    fun setSpeak(on: Boolean) = edit { putBoolean("speak", on) }
    fun setRate(r: Float) = edit { putFloat("rate", r.coerceIn(0.5f, 2.0f)) }
    fun setHandsFree(on: Boolean) = edit { putBoolean("hands_free", on) }

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secret())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(c.doFinal(raw.copyOfRange(12, raw.size)))
    }

    companion object {
        const val DEFAULT_BASE = "https://api.openai.com/v1"
        private const val ALIAS = "geocrew_assistant_key"
    }
}
