package io.github.nideta231.hermesremote.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Pages beyond chat that the user switches on in Settings > Additional features (all off by default). */
enum class Extra(val key: String, val label: String, val description: String, val wire: String = key) {
    LIBRARY("library", "Memory Library", "Hermes' own long-term memory: library page and setup on the PC bridge page"),
    PC_MONITOR("pc_monitor", "PC monitor", "PC stats, processes and the Claude usage ring in the chat", wire = "monitor"),
    TERMINAL("terminal", "Terminal", "A shell on your PC"),
    NOTES("notes", "Notes", "Keep-style notes Hermes can read and edit"),
    BLUEPRINTS("blueprints", "Blueprints", "Project boards and flow diagrams, their badges and notifications"),
    GRAPH("graph", "Graph", "Map of chats, projects and topics (knowledge map with Memory Library)"),
    ;

    companion object {
        /** Stored keys back to features; unknown keys (a removed feature) are dropped. */
        fun parse(keys: Set<String>?): Set<Extra> = keys.orEmpty().mapNotNull { k -> entries.firstOrNull { it.key == k } }.toSet()
    }
}

/**
 * Which [Extra]s are switched on. A bridge with the `extras` feature owns the switches (they stop the
 * feature on the PC too, see [applyRemote]); an older bridge only gets them hidden on this phone.
 * Nothing is on until the user turns it on.
 */
object ExtraFeatures {
    var enabled by mutableStateOf<Set<Extra>>(emptySet())
        private set
    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
        enabled = Extra.parse(prefs(context).getStringSet(KEY, null))
    }

    fun isOn(e: Extra) = e in enabled

    /** Extras the connected PC can serve when it manages the switches itself; null = an older bridge. */
    var remoteAvailable by mutableStateOf<Set<Extra>?>(null)
        private set

    /** Mirror the PC's `GET/PUT /v1/extras` answer. */
    fun applyRemote(doc: org.json.JSONObject) {
        val (on, available) = parseRemote(doc)
        remoteAvailable = available
        enabled = on
        ctx?.let { prefs(it).edit().putStringSet(KEY, enabled.map { x -> x.key }.toHashSet()).apply() }
    }

    /** Connected to a bridge without the switches: the phone-only behaviour. */
    fun clearRemote() { remoteAvailable = null }

    /** (switched on, available) from `{"extras": {"monitor": {"on": true, "available": true}, ...}}`. */
    fun parseRemote(doc: org.json.JSONObject): Pair<Set<Extra>, Set<Extra>> {
        val x = doc.optJSONObject("extras") ?: org.json.JSONObject()
        val on = Extra.entries.filter { x.optJSONObject(it.wire)?.optBoolean("on") == true }.toSet()
        val available = Extra.entries.filter { x.optJSONObject(it.wire)?.optBoolean("available", true) ?: false }.toSet()
        return on to available
    }

    fun set(e: Extra, on: Boolean) {
        enabled = if (on) enabled + e else enabled - e
        ctx?.let { prefs(it).edit().putStringSet(KEY, enabled.map { x -> x.key }.toHashSet()).apply() }
    }

    /** The bridge's `/v1/me` features minus the extras the user left off; the UI reads only this set. */
    fun gate(bridge: Set<String>, on: Set<Extra> = enabled): Set<String> =
        bridge - BRIDGE_FEATURE.filterKeys { it !in on }.values.toSet()

    /** Extras this host can serve: bridge flags, Graph always, the PC monitor also on a gateway host. */
    fun served(bridge: Set<String>, gateway: Boolean): Set<Extra> = Extra.entries.filter { e ->
        val flag = BRIDGE_FEATURE[e]
        flag == null || flag in bridge || (e == Extra.PC_MONITOR && gateway)
    }.toSet()

    /** Extras backed by a bridge feature flag (Graph has none: it is always served). */
    private val BRIDGE_FEATURE = mapOf(Extra.LIBRARY to "library", Extra.PC_MONITOR to "monitor",
        Extra.TERMINAL to "terminal", Extra.NOTES to "notes", Extra.BLUEPRINTS to "blueprints")

    private const val KEY = "enabled"
    private fun prefs(c: Context) = c.getSharedPreferences("extra_features", Context.MODE_PRIVATE)
}
