package io.github.nideta231.hermesremote.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One paired PC. [name] is blank until the bridge reports its computer name. */
data class Profile(val id: String, val name: String = "")

/**
 * The paired PCs and which one the app talks to.
 *
 * Every PC keeps its own prefs files (pairing, drafts), named after its id, so switching is a
 * matter of opening another file: tokens, addresses, the last chat and drafts never mix. The
 * install from before profiles existed used the files "pairing" and "drafts"; those belong to
 * the [LEGACY] profile, which is why an upgrade keeps its pairing without a re-scan.
 */
class ProfileStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences("profiles", Context.MODE_PRIVATE))

    /** Never empty: a fresh install (or one that removed every PC) has the [LEGACY] slot. */
    fun list(): List<Profile> {
        val raw = prefs.getString(KEY_LIST, null) ?: return listOf(Profile(LEGACY))
        val items = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Profile(it.getString("id"), it.optString("name")) } }
        }.getOrDefault(emptyList())
        return items.ifEmpty { listOf(Profile(LEGACY)) }
    }

    var activeId: String
        get() = prefs.getString(KEY_ACTIVE, null)?.takeIf { id -> list().any { it.id == id } } ?: list().first().id
        set(v) { prefs.edit().putString(KEY_ACTIVE, v).apply() }

    fun active(): Profile = list().first { it.id == activeId }

    /** A new empty slot; the caller fills it with a pairing. */
    fun add(name: String = ""): Profile {
        val p = Profile(UUID.randomUUID().toString().replace("-", "").take(12), name.trim())
        save(list() + p)
        return p
    }

    fun rename(id: String, name: String) = save(list().map { if (it.id == id) it.copy(name = name.trim()) else it })

    /** Drops [id] from the list; the next PC (if any) becomes active. Returns the new active id. */
    fun remove(id: String): String {
        val rest = list().filterNot { it.id == id }
        save(rest)
        val next = rest.firstOrNull()?.id ?: LEGACY
        if (activeId == id || rest.none { it.id == activeId }) activeId = next
        return activeId
    }

    private fun save(items: List<Profile>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    companion object {
        /** The pre-profiles pairing: its files keep their original names. */
        const val LEGACY = "default"
        private const val KEY_LIST = "list"
        private const val KEY_ACTIVE = "active"

        fun pairingFile(id: String) = if (id == LEGACY) "pairing" else "pairing_$id"
        fun draftsFile(id: String) = if (id == LEGACY) "drafts" else "drafts_$id"
    }
}
