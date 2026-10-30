package io.github.nideta231.hermesremote.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.nideta231.hermesremote.MainActivity
import io.github.nideta231.hermesremote.R

/**
 * The app's notifications. Only events worth interrupting someone for: a turn that needs a
 * decision (approval, a clarify question), and a turn that ended while the app was off screen.
 * Tool progress stays silent.
 *
 * Channels use the system defaults (sound, vibration, heads-up for approvals) so the user's own
 * Android notification settings apply; tapping opens the session the notification is about.
 */
object Notifier {
    private const val CHANNEL_APPROVALS = "approvals"
    private const val CHANNEL_RESULTS = "results"
    private const val CHANNEL_WATCH = "watching"

    const val WATCH_ID = 1
    const val EXTRA_OPEN_SESSION = "open_session"
    /** "projectId\tname": open that project's blueprint (a new Hermes comment). */
    const val EXTRA_OPEN_BLUEPRINT = "open_blueprint"
    /** The Hermes profile that session belongs to (absent: the default profile). */
    const val EXTRA_PROFILE = "open_profile"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_APPROVALS, "Approval requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Hermes is paused until you answer"
            },
            NotificationChannel(CHANNEL_RESULTS, "Finished tasks", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A task in a chat open on this device finished, failed or was stopped"
            },
            // Required by Android for a foreground service; silent and collapsed.
            NotificationChannel(CHANNEL_WATCH, "Task in progress", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Hermes Remote stays connected for a running task"
                setShowBadge(false)
            },
        ))
    }

    fun watchNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_WATCH)
            .setContentTitle("Hermes is working")
            .setContentText("You'll be notified when it finishes or needs you")
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context, null, null, 0))
            .build()

    fun postTurnFinished(context: Context, key: String, sessionId: String?, profile: String?, title: String?, status: String, reply: String?) {
        val where = title?.takeIf { it.isNotBlank() } ?: "Hermes"
        val (heading, fallback) = when (status) {
            "complete" -> where to "Finished."
            "error" -> "Failed: $where" to "The task ended with an error. Tap to see what happened."
            "interrupted" -> "Stopped: $where" to "The task was stopped before it finished."
            else -> where to "Task ended ($status)."
        }
        val body = reply?.let(::plain)?.takeIf { it.isNotBlank() }?.take(600) ?: fallback
        // One "finished" alert per session: a newer turn replaces the older one.
        post(context, CHANNEL_RESULTS, resultId(sessionId ?: key), heading, body, sessionId, profile, NotificationCompat.CATEGORY_MESSAGE)
    }

    fun postApprovalNeeded(context: Context, requestId: String, sessionId: String?, profile: String?, title: String?, request: ApprovalRequest) {
        val body = listOfNotNull(request.description?.takeIf { it.isNotBlank() }, request.command?.takeIf { it.isNotBlank() }?.let { "$ $it" })
            .joinToString("\n").ifBlank { "Hermes is waiting for your decision." }
        val id = questionId(requestId)
        // Answer from the notification itself (also on the lock screen). Allowing needs the phone
        // unlocked first (Android 12+): a command must not run because someone else picked the phone up.
        val actions = approvalActions(request.choices).map { (choice, label) ->
            val intent = Intent(context, ApprovalActionReceiver::class.java)
                .setAction(ApprovalActionReceiver.ACTION)
                .putExtra(ApprovalActionReceiver.EXTRA_REQUEST, requestId)
                .putExtra(ApprovalActionReceiver.EXTRA_CHOICE, choice)
                .putExtra(ApprovalActionReceiver.EXTRA_TITLE, title)
                .putExtra(ApprovalActionReceiver.EXTRA_SESSION, sessionId)
                .putExtra(ApprovalActionReceiver.EXTRA_PROFILE, profile)
            // One PendingIntent per (request, choice): the request code keeps them apart.
            val pi = PendingIntent.getBroadcast(context, (requestId + choice).hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            NotificationCompat.Action.Builder(0, label, pi)
                .setAuthenticationRequired(choice != "deny")
                .setSemanticAction(if (choice == "deny") NotificationCompat.Action.SEMANTIC_ACTION_DELETE else NotificationCompat.Action.SEMANTIC_ACTION_NONE)
                .setShowsUserInterface(false)
                .build()
        }
        post(context, CHANNEL_APPROVALS, id,
            "Approval needed${title?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
            body.take(600), sessionId, profile, NotificationCompat.CATEGORY_REMINDER, requestId, actions,
            publicTitle = "Hermes needs your approval")
    }

    /** Buttons on the approval notification, in order: Deny, Allow, Allow for session. "always" stays in the app. */
    internal fun approvalActions(choices: List<String>): List<Pair<String, String>> = listOfNotNull(
        ("deny" to "Deny").takeIf { "deny" in choices },
        ("once" to "Allow").takeIf { "once" in choices },
        ("session" to "Allow for session").takeIf { "session" in choices },
    )

    /** What the notification says once answered from its buttons. */
    internal fun answeredText(choice: String): String = when (choice) {
        "deny" -> "Denied"
        "once" -> "Allowed once"
        "session" -> "Allowed for this session"
        "always" -> "Always allowed"
        else -> "Answered: $choice"
    }

    /** Replace the approval notification with the outcome of a button tap; it clears itself shortly after. */
    fun postApprovalOutcome(context: Context, requestId: String, sessionId: String?, profile: String?, title: String?, text: String, failed: Boolean) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannels(context)
        val id = questionId(requestId)
        val n = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setContentTitle(title?.takeIf { it.isNotBlank() } ?: "Hermes")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .apply { if (!failed) setTimeoutAfter(4_000) }
            .setContentIntent(openApp(context, sessionId, profile, id))
            .build()
        if (!failed) posted.remove(requestId)
        runCatching { nm.notify(id, n) }
    }

    fun postQuestion(context: Context, requestId: String, sessionId: String?, profile: String?, title: String?, question: String?) {
        post(context, CHANNEL_APPROVALS, questionId(requestId),
            "Hermes has a question${title?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
            (question ?: "Hermes is waiting for your answer.").take(600), sessionId, profile, NotificationCompat.CATEGORY_REMINDER, requestId)
    }

    /** Hermes commented on a card (a question, a review request or a note). Tapping opens the blueprint. */
    fun postBlueprintComment(context: Context, projectId: String, projectName: String, m: BpLatestComment) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannels(context)
        val heading = when (m.kind) {
            "question" -> "Hermes asks · ${m.title}"
            "review" -> "Review needed · ${m.title}"
            else -> "Hermes commented · ${m.title}"
        }
        val body = plain(m.text).ifBlank { "Attached a file." }.take(600) + "\n$projectName blueprint"
        val id = 0x30000 + (projectId.hashCode() and 0xffff)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_BLUEPRINT, "$projectId\t$projectName")
        val n = NotificationCompat.Builder(context, if (m.kind == "comment") CHANNEL_RESULTS else CHANNEL_APPROVALS)
            .setContentTitle(heading).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setCategory(if (m.kind == "comment") NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        runCatching { nm.notify(id, n) }
    }

    fun clearQuestion(context: Context, requestId: String) {
        NotificationManagerCompat.from(context).cancel(questionId(requestId))
        posted.remove(requestId)
    }

    /** Drop alerts for a session the user just opened; they have seen it. */
    fun clearSession(context: Context, sessionId: String) {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(resultId(sessionId))
        posted.filterValues { it == sessionId }.keys.toList().forEach { rid -> nm.cancel(questionId(rid)); posted.remove(rid) }
    }

    /** requestId → sessionId of questions currently posted, so opening a chat can clear them. */
    private val posted = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun post(context: Context, channel: String, id: Int, title: String, body: String,
                     sessionId: String?, profile: String?, category: String, requestId: String? = null,
                     actions: List<NotificationCompat.Action> = emptyList(), publicTitle: String? = null) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        if (requestId != null && sessionId != null) posted[requestId] = sessionId
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setCategory(category)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, sessionId, profile, id))
            .apply {
                actions.forEach { addAction(it) }
                // Lock screen with "hide sensitive content": show that something waits, not the command.
                if (publicTitle != null) {
                    setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    setPublicVersion(NotificationCompat.Builder(context, channel)
                        .setContentTitle(publicTitle).setContentText("Unlock to see the command")
                        .setSmallIcon(R.drawable.ic_stat_hermes).build())
                }
            }
            .build()
        runCatching { nm.notify(id, n) } // SecurityException if permission was revoked mid-flight
    }

    private fun openApp(context: Context, sessionId: String?, profile: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply {
                if (sessionId != null) putExtra(EXTRA_OPEN_SESSION, sessionId)
                if (profile != null) putExtra(EXTRA_PROFILE, profile)
            }
        return PendingIntent.getActivity(context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Markdown in a notification shows as raw symbols; strip the common marks. */
    internal fun plain(md: String): String = md
        .replace(Regex("```[\\s\\S]*?```"), "[code]")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun resultId(key: String) = 0x10000 + (key.hashCode() and 0xffff)
    private fun questionId(requestId: String) = 0x20000 + (requestId.hashCode() and 0xffff)
}
