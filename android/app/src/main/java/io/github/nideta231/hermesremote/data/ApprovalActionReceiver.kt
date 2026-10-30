package io.github.nideta231.hermesremote.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Deny / Allow buttons on an approval notification. The answer goes back over the live
 * socket ([LiveLink]), the same way the chat's approval dock sends it, so the app never opens.
 * When the link to the PC is down the notification says so and tapping it opens the chat.
 */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST) ?: return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE)
        val session = intent.getStringExtra(EXTRA_SESSION)
        val profile = intent.getStringExtra(EXTRA_PROFILE)
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                // A socket that is just reconnecting (phone woke from sleep) gets a few seconds to come back.
                LiveLink.gateway?.let { g -> withTimeoutOrNull(WAIT_MS) { g.state.first { it == GatewayState.OPEN } } }
                val sent = LiveLink.answerApproval(app, requestId, choice)
                val text = if (sent) Notifier.answeredText(choice)
                    else "Not connected to your PC, so the answer was not sent. Tap to open the chat and answer there."
                Notifier.postApprovalOutcome(app, requestId, session, profile, title, text, failed = !sent)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val WAIT_MS = 8_000L
        const val ACTION = "io.github.nideta231.hermesremote.APPROVAL_ANSWER"
        const val EXTRA_REQUEST = "request_id"
        const val EXTRA_CHOICE = "choice"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_PROFILE = "profile"
    }
}
