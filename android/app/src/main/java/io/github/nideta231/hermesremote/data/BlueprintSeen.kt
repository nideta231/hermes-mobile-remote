package io.github.nideta231.hermesremote.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Which Hermes comments the user has seen, per card, so the board can badge unread ones and the
 * app can notify about new ones once. Comments written from the phone never count as unread.
 */
class BlueprintSeen(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("blueprint_seen", Context.MODE_PRIVATE))

    private fun key(projectId: String, cardId: String) = "seen:$projectId/$cardId"

    fun seenAt(projectId: String, cardId: String): Double = prefs.getString(key(projectId, cardId), null)?.toDoubleOrNull() ?: 0.0

    /** The user looked at the card: everything on it up to now is read. */
    fun markSeen(projectId: String, card: BpCard) {
        val newest = card.comments.filter { it.by != "phone" && !it.ack }.maxOfOrNull { it.at } ?: return
        if (newest > seenAt(projectId, card.id)) prefs.edit().putString(key(projectId, card.id), newest.toString()).apply()
    }

    fun unread(projectId: String, card: BpCard): Int = unreadCount(card, seenAt(projectId, card.id))

    /** Unread on the latest comment of a project (drawer dot): newer than what was seen on that card. */
    fun projectUnread(projectId: String, latest: BpLatestComment?): Boolean =
        latest != null && latest.at > seenAt(projectId, latest.card)

    /** When the last alert for [projectId] was posted; null = never checked (first look sets a baseline, no alert). */
    fun notifiedAt(projectId: String): Double? = prefs.getString("notified:$projectId", null)?.toDoubleOrNull()

    fun setNotified(projectId: String, at: Double) { prefs.edit().putString("notified:$projectId", at.toString()).apply() }
}

/** The newest non-phone comment of a blueprint, from the bridge's summary (`latest_comment`). */
data class BpLatestComment(val card: String, val title: String, val at: Double, val by: String, val kind: String, val text: String)

internal fun unreadCount(card: BpCard, seenAt: Double): Int = card.comments.count { it.by != "phone" && !it.ack && it.at > seenAt }

/** Whether to alert about [latest]: something newer than the last alert, which the user has not already seen. */
internal fun shouldNotify(latest: BpLatestComment?, notifiedAt: Double?, seenAt: Double): Boolean =
    latest != null && notifiedAt != null && latest.at > notifiedAt && latest.at > seenAt
