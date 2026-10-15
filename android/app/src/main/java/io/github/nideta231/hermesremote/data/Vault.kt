package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One saved login, card or address, as metadata. The secret part never leaves the PC. */
data class VaultItem(
    val id: String,
    val kind: String,
    val label: String,
    val origin: String?,
    val identifier: String?,
    val backend: String,
    val hasOtp: Boolean,
) {
    /** Only items in Hermes' own vault can be changed; password-manager items are read-only here. */
    val editable: Boolean get() = backend == "local"
}

data class VaultSource(val name: String, val displayName: String, val enabled: Boolean, val needsUnlock: Boolean, val unlocked: Boolean)

data class VaultState(
    val items: List<VaultItem> = emptyList(),
    val sources: List<VaultSource> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** The PC's bridge is older than the vault page (the relay refuses vault.*). */
    val unsupported: Boolean = false,
)

/** What the phone sends to save an item: [secret] goes straight to the PC's encrypted store. */
data class VaultDraft(
    val kind: String,
    val label: String,
    val origin: String,
    val secret: Map<String, String>,
)

fun parseVaultItems(r: JSONObject): List<VaultItem> = r.optJSONArray("items").objects().mapNotNull { o ->
    VaultItem(id = o.str("id") ?: return@mapNotNull null, kind = o.str("kind").orEmpty(), label = o.str("label").orEmpty(),
        origin = o.str("origin")?.takeIf { it.isNotBlank() }, identifier = o.str("identifier")?.takeIf { it.isNotBlank() },
        backend = o.str("backend") ?: "local", hasOtp = o.optBoolean("has_otp"))
}.sortedWith(compareBy({ it.kind }, { it.label.lowercase() }))

fun parseVaultSources(r: JSONObject): List<VaultSource> = r.optJSONArray("sources").objects().mapNotNull { o ->
    VaultSource(o.str("name") ?: return@mapNotNull null, o.str("display_name") ?: o.str("name")!!, o.optBoolean("enabled"),
        o.optBoolean("needs_unlock"), o.optBoolean("unlocked"))
}

/** http(s) origin with a host, like the desktop's check: `https://example.com`. Bare hosts get https://. */
fun normalizeVaultOrigin(input: String): String? {
    val t = input.trim().trimEnd('/')
    if (t.isEmpty()) return null
    val withScheme = if (t.contains("://")) t else "https://$t"
    val m = Regex("""^(https?)://([^/\s?#]+)""", RegexOption.IGNORE_CASE).find(withScheme) ?: return null
    return "${m.groupValues[1].lowercase()}://${m.groupValues[2].lowercase()}"
}

/** Problems with a draft, in the words the form shows; null when it can be saved. */
fun vaultDraftProblem(d: VaultDraft): String? = when {
    d.label.isBlank() -> "Give it a name."
    d.kind == "login" && normalizeVaultOrigin(d.origin) == null -> "Enter the site, e.g. https://example.com"
    d.kind != "login" && d.origin.isNotBlank() && normalizeVaultOrigin(d.origin) == null -> "The site must look like https://example.com"
    d.kind == "login" && (d.secret["identifier"].isNullOrBlank() || d.secret["password"].isNullOrEmpty()) -> "Login needs a username/email and a password."
    d.kind == "payment" && d.secret["card_number"].isNullOrBlank() -> "Enter the card number."
    d.kind == "address" && d.secret["address_line1"].isNullOrBlank() -> "Enter the street address."
    else -> null
}

fun vaultAddParams(d: VaultDraft): JSONObject = JSONObject().put("kind", d.kind).put("label", d.label.trim()).apply {
    normalizeVaultOrigin(d.origin)?.let { put("origin", it) }
    put("secret", JSONObject().apply { d.secret.forEach { (k, v) -> if (v.isNotEmpty()) put(k, v) } })
}

/**
 * The Vault page: Hermes' saved logins, cards and addresses on the PC (the ones the agent fills
 * into web pages). Lists metadata only; adding sends the secret once over the encrypted link to the
 * PC's encrypted store. "Edit" saves the new version first, then removes the old one, so a failed
 * save never loses the item.
 */
class Vault(
    private val scope: CoroutineScope,
    private val call: suspend (String, JSONObject) -> JSONObject,
    /** The PC and Hermes profile in use now: a list that arrives after a switch is dropped. */
    private val target: () -> Target,
    private val describe: (Throwable) -> String,
    private val say: (String) -> Unit,
) {
    private val _state = MutableStateFlow(VaultState())
    val state: StateFlow<VaultState> = _state.asStateFlow()

    fun clear() { _state.value = VaultState() }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        val asked = target()
        scope.launch {
            try {
                val items = parseVaultItems(call("vault.list", JSONObject()))
                val sources = runCatching { parseVaultSources(call("vault.sources", JSONObject())) }.getOrDefault(emptyList())
                if (asked == target()) _state.update { it.copy(items = items, sources = sources, loading = false, unsupported = false) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (asked != target()) return@launch
                val old = t is RpcException && t.code == -32601
                _state.update { it.copy(loading = false, unsupported = old, error = if (old) null else describe(t)) }
            }
        }
    }

    /** Save [draft]; with [replacing], the old item is removed once the new one is stored. */
    fun save(draft: VaultDraft, replacing: VaultItem?, done: (String?) -> Unit) {
        vaultDraftProblem(draft)?.let { return done(it) }
        scope.launch {
            try {
                call("vault.add", vaultAddParams(draft))
                replacing?.let { old ->
                    runCatching { call("vault.remove", JSONObject().put("id", old.id)) }
                        .onFailure { say("Saved, but the old version couldn't be removed. Delete it by hand.") }
                }
                done(null)
                refresh()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                done(describe(t))
            }
        }
    }

    fun remove(item: VaultItem) {
        scope.launch {
            try {
                call("vault.remove", JSONObject().put("id", item.id))
                _state.update { st -> st.copy(items = st.items.filterNot { it.id == item.id }) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                say(describe(t))
            }
        }
    }
}

