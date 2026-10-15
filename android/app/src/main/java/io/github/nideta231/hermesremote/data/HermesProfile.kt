package io.github.nideta231.hermesremote.data

import org.json.JSONObject

/**
 * One Hermes profile on the paired PC (`hermes profile list`): its own chats, model, skills and
 * settings. Not to be confused with a paired PC, which the app's [ProfileStore] keeps.
 *
 * [name] is the id Hermes uses ("default", "developer", ...); [label] is what the picker shows.
 */
data class HermesProfile(
    val name: String,
    val displayName: String = "",
    val description: String = "",
    val model: String = "",
    val provider: String = "",
    val isDefault: Boolean = false,
) {
    val label: String get() = displayName.ifBlank { name }
}

/** Hermes' own name for the profile every request without a name uses. */
const val DEFAULT_HERMES_PROFILE = "default"

/**
 * What a request was for: which paired PC, and which Hermes profile on it. An answer is applied only
 * while both are still the ones in use; one that arrives after the user switched PC or profile is
 * dropped, so one PC's data never shows under another.
 */
data class Target(val pc: String, val profile: String)

fun parseProfiles(o: JSONObject): List<HermesProfile> = o.optJSONArray("profiles").objects().mapNotNull { p ->
    val name = p.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    HermesProfile(name, p.str("display_name").orEmpty(), p.str("description").orEmpty(),
        p.str("model").orEmpty(), p.str("provider").orEmpty(), p.optBoolean("is_default"))
}

/** The `profile` a request carries: null for the default profile, so those requests stay exactly as before. */
fun profileParam(name: String?): String? = name?.takeIf { it.isNotBlank() && it != DEFAULT_HERMES_PROFILE }
