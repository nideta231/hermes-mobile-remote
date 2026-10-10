package io.github.nideta231.hermesremote.data

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
 * Stores the pairing. The device token is encrypted with a non-exportable AES key held in the
 * Android Keystore; prefs are excluded from cloud backup and device transfer.
 *
 * One store per paired PC: [file] comes from [ProfileStore.pairingFile].
 */
class CredentialStore(context: Context, file: String = "pairing") {
    private val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    fun load(): Pairing? {
        val url = prefs.getString("url", null) ?: return null
        val blob = prefs.getString("token", null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            val token = String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
            Pairing(url, prefs.getString("device", "") ?: "", token, pin = prefs.getString("pin", null))
        } catch (e: Exception) {
            clear() // key invalidated (e.g. restored to a new phone): force re-pairing
            null
        }
    }

    fun save(p: Pairing) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(p.token.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("url", p.url)
            .putString("device", p.device)
            .putString("pin", p.pin)
            .putString("token", Base64.encodeToString(blob, Base64.NO_WRAP))
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // ---- small non-secret UI state persisted across process death

    /** Addresses the bridge advertised last time, so a dead saved address still has fallbacks. */
    var bridgeAddresses: Map<String, List<String>>?
        get() = prefs.getString("bridge_addresses", null)?.let { raw ->
            runCatching {
                val o = org.json.JSONObject(raw)
                o.keys().asSequence().associateWith { k -> o.getJSONArray(k).strings() }
            }.getOrNull()
        }
        set(v) = prefs.edit().putString("bridge_addresses",
            v?.let { m -> org.json.JSONObject(m.mapValues { org.json.JSONArray(it.value) }).toString() }).apply()

    /** Manual network choice: "auto", "lan" or "tailnet". */
    var transportMode: String
        get() = prefs.getString("transport_mode", "auto") ?: "auto"
        set(v) = prefs.edit().putString("transport_mode", v).apply()

    /** Reasoning effort for runs started from the app; null leaves it to Hermes' config. */
    var reasoningEffort: String?
        get() = prefs.getString("reasoning_effort", null)
        set(v) = prefs.edit().putString("reasoning_effort", v).apply()

    var lastSessionId: String?
        get() = prefs.getString("last_session", null)
        set(v) = prefs.edit().putString("last_session", v).apply()

    /** A send that may not have reached the bridge yet: retried with the same id (dedup-safe). */
    var pendingSend: Triple<String, String, String>?
        get() {
            val id = prefs.getString("pending_id", null) ?: return null
            return Triple(id, prefs.getString("pending_session", "")!!, prefs.getString("pending_text", "")!!)
        }
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove("pending_id").remove("pending_session").remove("pending_text")
            else e.putString("pending_id", v.first).putString("pending_session", v.second).putString("pending_text", v.third)
            e.commit()
        }

    private companion object {
        const val ALIAS = "hermes_remote_token_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
