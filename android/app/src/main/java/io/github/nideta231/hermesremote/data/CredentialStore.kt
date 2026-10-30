package io.github.nideta231.hermesremote.data

import android.content.Context
import android.util.Base64

/**
 * Stores the pairing. The device token is encrypted with a non-exportable AES key held in the
 * Android Keystore; prefs are excluded from cloud backup and device transfer.
 *
 * One store per paired PC: [file] comes from [ProfileStore.pairingFile].
 */
class CredentialStore(context: Context, file: String = "pairing") {
    private val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
    private val cipher: TokenCipher by lazy { KeystoreTokenCipher(ALIAS) }

    fun load(): Pairing? {
        val url = prefs.getString("url", null) ?: return null
        val blob = prefs.getString("token", null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val token = String(cipher.decrypt(raw), Charsets.UTF_8)
            val funnel = prefs.getString("funnel_key", null)?.let { String(cipher.decrypt(Base64.decode(it, Base64.NO_WRAP)), Charsets.UTF_8) }
            Pairing(url, prefs.getString("device", "") ?: "", token, pin = prefs.getString("pin", null), funnelKey = funnel)
        } catch (e: Exception) {
            clear() // key invalidated (e.g. restored to a new phone): force re-pairing
            null
        }
    }

    fun save(p: Pairing) {
        val blob = cipher.encrypt(p.token.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("url", p.url)
            .putString("device", p.device)
            .putString("pin", p.pin)
            .putString("token", Base64.encodeToString(blob, Base64.NO_WRAP))
            .putString("funnel_key", p.funnelKey?.let { Base64.encodeToString(cipher.encrypt(it.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) })
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

    /** What the bridge said it serves last time (monitor, terminal...), so its pages show before /me answers. */
    var bridgeFeatures: Set<String>
        get() = prefs.getStringSet("bridge_features", null)?.toSet().orEmpty()
        set(v) = prefs.edit().putStringSet("bridge_features", v).apply()

    /** The PC's public Tailscale Funnel address (https://pc.<tailnet>.ts.net), when it has one. */
    var funnelUrl: String?
        get() = prefs.getString("funnel_url", null)
        set(v) = prefs.edit().putString("funnel_url", v).apply()

    /** This phone's device id on the bridge (from /v1/me); the Funnel unlock signs it. */
    var deviceId: String?
        get() = prefs.getString("device_id", null)
        set(v) = prefs.edit().putString("device_id", v).apply()

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

    /** The Hermes profile in use on this PC ("default" when never chosen). */
    var hermesProfile: String
        get() = prefs.getString("hermes_profile", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_HERMES_PROFILE
        set(v) = prefs.edit().putString("hermes_profile", v).apply()

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
    }
}
