package io.github.nideta231.hermesremote.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nideta231.hermesremote.data.BridgeAdminState
import io.github.nideta231.hermesremote.data.HeldAction
import io.github.nideta231.hermesremote.data.ParkedSockets

class BridgeAdminActions(
    val refresh: () -> Unit,
    val fix: (String) -> Unit,
    val restart: () -> Unit,
    val trust: () -> Unit,
    val untrust: (String) -> Unit,
    val revoke: (String) -> Unit,
    val loadLogs: () -> Unit,
    /** The bridge self-update row (null when the bridge can't update itself). */
    val update: BridgeUpdateActions? = null,
    /** Tailscale Funnel controls (null when the bridge has none). */
    val funnel: FunnelActions? = null,
)

class FunnelActions(
    val set: (on: Boolean, port: Int) -> Unit,
    val remove: (String) -> Unit,
    val allowThis: () -> Unit,
    val leaveThis: () -> Unit,
    /** True while the app itself is on the internet route (allowing and switching on are home/Tailscale only). */
    val overFunnel: Boolean,
)

private fun fixLabel(fix: String) = when (fix) {
    "trust" -> "Trust network"; "restart" -> "Restart bridge"; "start_hermes" -> "Start Hermes"; else -> "Fix"
}

private fun checkTitle(id: String) = when (id) {
    "hermes" -> "Hermes"; "service" -> "Bridge service"; "network" -> "Network"; "firewall" -> "Firewall"
    "tailscale" -> "Tailscale"; "devices" -> "Paired devices"; else -> id
}

/**
 * The PC side of Hermes Remote, from the phone: what the doctor sees with a one-tap fix per problem,
 * restart and update the bridge, trusted networks, paired devices and the bridge log. Everything here
 * used to need a terminal on the PC.
 */
@Composable
fun BridgeAdminScreen(state: BridgeAdminState, actions: BridgeAdminActions, onBack: () -> Unit) {
    LaunchedEffect(Unit) { actions.refresh() }
    var confirmRestart by remember { mutableStateOf(false) }
    var confirmUpdate by remember { mutableStateOf(false) }
    // Set when held chats still have work: Restart and Update then ask first.
    val heldWork = ParkedSockets.confirmText(state.parked, state.parkedSupported)
    val heldUpdate = ParkedSockets.confirmText(state.parked, state.parkedSupported, HeldAction.Update)
    var revoke by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf(false) }
    var funnelOn by remember { mutableStateOf(false) }
    PageScaffold("PC bridge", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.error?.let { Panel { PanelRow("Couldn't reach the bridge admin", it, Glyphs.Offline, iconTint = Bad) } }

            SettingsSection("Health", "What `hermes-remote-bridge doctor` sees on the PC.") {
                Panel {
                    if (state.checks.isEmpty()) Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        if (state.loading) TypingDots(Gold) else Text("No report yet. Pull down or tap Check again.")
                    }
                    state.checks.forEach { c ->
                        val tint = when (c.level) { "ok" -> Ok; "warn" -> Warn; else -> Bad }
                        PanelRow(checkTitle(c.id), c.message, if (c.level == "ok") Glyphs.Check else Glyphs.Alert, iconTint = tint) {
                            c.fix?.let { f ->
                                if (state.busy == f) TypingDots()
                                else TextButton(onClick = { if (f == "restart") confirmRestart = true else actions.fix(f) }) { Text(fixLabel(f)) }
                            }
                        }
                    }
                    PanelRow("Check again", state.version?.let { "Bridge $it" }, Glyphs.Refresh, onClick = actions.refresh) {
                        if (state.loading && state.checks.isNotEmpty()) TypingDots()
                    }
                }
            }

            if (ParkedSockets.showSection(state.parked, state.parkedSupported)) SettingsSection("Held chats",
                "Chats the PC keeps for a phone that dropped, so their background work isn't lost. They release on their own.") {
                Panel {
                    state.parked.forEach { p ->
                        PanelRow(p.device, "${ParkedSockets.reason(p)} · held ${ParkedSockets.held(p)}", Glyphs.Person)
                    }
                }
            }

            SettingsSection("Bridge", "Restart or update the bridge on the PC. Pairings, Hermes and running chats are kept.") {
                Panel {
                    PanelRow("Restart bridge", if (state.restarting) "Restarting… the connection comes back on its own"
                        else "Reloads the bridge (new code, changed settings). About 10 seconds.", Glyphs.Refresh,
                        onClick = { if (!state.restarting) confirmRestart = true }) { if (state.restarting) TypingDots() }
                    PanelRow("Log", "The bridge's latest log lines", Glyphs.Doc, onClick = { actions.loadLogs(); logs = true })
                }
                actions.update?.let { u ->
                    val s = u.state
                    Spacer(Modifier.size(8.dp))
                    Panel {
                        PanelRow("Update", when {
                            s.updating -> s.message ?: "Updating…"
                            s.available && s.canUpdate -> "${s.latest} ready (running ${s.current ?: "?"})"
                            s.current != null -> "Up to date (${s.current})"
                            else -> s.reason ?: "Checking…"
                        }, Glyphs.Download, onClick = { u.check(true) }) {
                            if (s.updating) TypingDots()
                            else if (s.available && s.canUpdate) TextButton(onClick = { if (heldWork != null) confirmUpdate = true else u.start() }) { Text(if (s.restartNeeded) "Restart" else "Update") }
                        }
                    }
                }
            }

            val f = state.funnel
            if (f != null && actions.funnel != null) SettingsSection("Internet (Tailscale Funnel)",
                "Reach this PC from anywhere without Tailscale on the phone. Only phones you allow get in: chat, files and boards; " +
                    "the terminal, PC pages and notes ask for your fingerprint or PIN first (5 minutes); passwords (vault) never.") {
                val fa = actions.funnel
                Panel {
                    if (!f.available && !f.on) {
                        val open = androidx.compose.ui.platform.LocalUriHandler.current
                        PanelRow("Not available yet", f.reason + if (f.enableUrl != null) " Tap Enable, sign in to Tailscale and approve, then come back and tap Check again." else "",
                            Glyphs.Alert, iconTint = Warn) {
                            f.enableUrl?.let { u -> TextButton(onClick = { open.openUri(u) }) { Text("Enable") } }
                        }
                    }
                    PanelRow("Funnel", if (f.on) "On: ${f.url}" else "Off: only home Wi-Fi and Tailscale reach this PC", Glyphs.Shield,
                        iconTint = if (f.on) Warn else MaterialTheme.colorScheme.onSurfaceVariant) {
                        if (state.busy == "funnel") TypingDots()
                        else if (f.on) TextButton(onClick = { fa.set(false, 443) }) { Text("Turn off") }
                        else if (f.available && !fa.overFunnel) TextButton(onClick = { funnelOn = true }) { Text("Turn on") }
                    }
                    val me = f.devices.firstOrNull { it.isThis }
                    PanelRow("This phone", if (me != null) "Allowed over the internet" else "Not allowed", Glyphs.Person) {
                        if (me != null) TextButton(onClick = fa.leaveThis) { Text("Remove", color = Bad) }
                        else if (!fa.overFunnel) TextButton(onClick = fa.allowThis) { Text("Allow") }
                    }
                    f.devices.filter { !it.isThis }.forEach { d ->
                        PanelRow(d.name, "Allowed over the internet", Glyphs.Person) { TextButton(onClick = { fa.remove(d.name) }) { Text("Remove", color = Bad) } }
                    }
                }
                if (funnelOn) {
                    var port by remember { mutableStateOf(f.ports.firstOrNull() ?: 443) }
                    AlertDialog(onDismissRequest = { funnelOn = false }, title = { Text("Put this PC on the internet?") },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Anyone can reach the address, but only phones you allow get past the door: each needs its pairing, " +
                                "its own internet key, and your fingerprint or PIN for the terminal and PC pages. Wrong keys lock the sender out. " +
                                "Allow this phone below once Funnel is on.")
                            Text("Public port", style = MaterialTheme.typography.labelMedium)
                            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                f.ports.forEach { p -> androidx.compose.material3.FilterChip(port == p, { port = p }, { Text(p.toString()) }, colors = themedChip()) }
                            }
                        } },
                        confirmButton = { TextButton(onClick = { funnelOn = false; fa.set(true, port) }) { Text("Turn on") } },
                        dismissButton = { TextButton(onClick = { funnelOn = false }) { Text("Cancel") } })
                }
            }

            SettingsSection("Networks", "On a trusted Wi-Fi the phone connects directly, without Tailscale.") {
                Panel {
                    state.currentNetwork?.let { name ->
                        PanelRow("PC is on: $name", if (state.currentTrusted) "Trusted" else "Not trusted: only Tailscale works here",
                            Glyphs.Wifi, iconTint = if (state.currentTrusted) Ok else Warn) {
                            if (!state.currentTrusted) {
                                if (state.busy == "trust") TypingDots() else TextButton(onClick = actions.trust) { Text("Trust") }
                            }
                        }
                    } ?: PanelRow("PC network", "Not on a private network right now", Glyphs.Wifi)
                    state.networks.filter { it.id != state.currentNetworkId }.forEach { n ->
                        PanelRow(n.name, "Trusted", Glyphs.Wifi) { TextButton(onClick = { actions.untrust(n.id) }) { Text("Remove") } }
                    }
                    if (state.currentTrusted) state.currentNetworkId?.let { id ->
                        PanelRow("Stop trusting ${state.currentNetwork}", "The phone then needs Tailscale here", Glyphs.Offline,
                            onClick = { actions.untrust(id) })
                    }
                }
            }

            SettingsSection("Paired devices", "Phones and tablets that can reach this PC.") {
                Panel {
                    state.devices.forEach { d ->
                        PanelRow(d.name + if (d.isThis) " (this device)" else "",
                            d.lastSeen?.let { "Last seen " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                                .format(java.util.Date((it * 1000).toLong())) } ?: "Never connected", Glyphs.Person) {
                            if (!d.isThis) TextButton(onClick = { revoke = d.name }) { Text("Revoke", color = Bad) }
                        }
                    }
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
    if (confirmRestart) AlertDialog(onDismissRequest = { confirmRestart = false }, title = { Text("Restart the PC bridge?") },
        text = { Text(heldWork ?: "The connection drops for a few seconds and comes back on its own. Hermes and running chats keep going.") },
        confirmButton = { TextButton(onClick = { confirmRestart = false; actions.restart() }) { Text(if (heldWork != null) "Restart anyway" else "Restart") } },
        dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Cancel") } })
    if (confirmUpdate && heldUpdate != null) AlertDialog(onDismissRequest = { confirmUpdate = false }, title = { Text("Update the PC bridge?") },
        text = { Text(heldUpdate) },
        confirmButton = { TextButton(onClick = { confirmUpdate = false; actions.update?.start?.invoke() }) { Text("Update anyway") } },
        dismissButton = { TextButton(onClick = { confirmUpdate = false }) { Text("Cancel") } })
    revoke?.let { name ->
        AlertDialog(onDismissRequest = { revoke = null }, title = { Text("Revoke $name?") },
            text = { Text("It can no longer connect to this PC until it is paired again.") },
            confirmButton = { TextButton(onClick = { revoke = null; actions.revoke(name) }) { Text("Revoke", color = Bad) } },
            dismissButton = { TextButton(onClick = { revoke = null }) { Text("Cancel") } })
    }
    if (logs) AlertDialog(onDismissRequest = { logs = false }, title = { Text("Bridge log") }, text = {
        Text(state.logs ?: "Loading…", fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp,
            modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE)).horizontalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = { logs = false }) { Text("Close") } },
        dismissButton = { TextButton(onClick = actions.loadLogs) { Text("Refresh") } })
}
