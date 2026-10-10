package io.github.nideta231.hermesremote.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Composer drafts, kept per session id ("__new__" for a chat that has not been created yet).
 *
 * Compose's `rememberSaveable` only survives a configuration change: switching tabs, opening a
 * session or coming back from the launcher drops the composable and the text with it. The draft
 * lives here instead, so it outlives the composable and, because it is on disk, process death too.
 *
 * Drafts are sent prompts the user typed but did not send. They are kept out of the pairing
 * prefs (which are Keystore-encrypted and excluded from backup) and out of git.
 */
class DraftStore(private val prefs: SharedPreferences) {

    constructor(context: Context, file: String = "drafts") : this(context.getSharedPreferences(file, Context.MODE_PRIVATE))

    fun get(sessionId: String?): String = prefs.getString(key(sessionId), "").orEmpty()

    fun put(sessionId: String?, text: String) {
        val key = key(sessionId)
        prefs.edit().apply { if (text.isBlank()) remove(key) else putString(key, text) }.apply()
    }

    fun clear(sessionId: String?) {
        prefs.edit().remove(key(sessionId)).apply()
    }

    /** Forget every draft (the PC was unpaired). */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /** Drop drafts for sessions that no longer exist, keeping the current and undrafted ones. */
    fun prune(keep: Set<String>, keepText: Map<String, String>) {
        val stale = prefs.all.keys.filter {
            it.startsWith(PREFIX) && it.removePrefix(PREFIX) !in keep && it.removePrefix(PREFIX) !in keepText
        }
        if (stale.isEmpty()) return
        prefs.edit().apply { stale.forEach(::remove) }.apply()
    }

    private fun key(sessionId: String?) = PREFIX + (sessionId ?: NEW)

    private companion object {
        const val PREFIX = "draft:"
        const val NEW = "__new__"
    }
}