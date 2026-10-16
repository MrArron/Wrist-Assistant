package com.mrarron.wristclaude.phone

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** One captured notification. Kept only in memory. */
class CapturedNotification(
    val id: String,
    val key: String,
    val app: String,
    val pkg: String,
    val title: String,
    val text: String,
    val time: Long,
    val replyAction: Notification.Action?,
) {
    val canReply get() = replyAction != null
}

/** In-memory ring buffer of the last 50 notifications. Never written to disk. */
object NotificationBuffer {
    const val MAX = 50
    private val items = ArrayDeque<CapturedNotification>()
    private var counter = 0

    @Synchronized
    fun add(n: CapturedNotification) {
        items.removeAll { it.key == n.key }
        items.addFirst(n)
        while (items.size > MAX) items.removeLast()
    }

    @Synchronized
    fun nextId(): String = "n" + (++counter)

    @Synchronized
    fun recent(app: String?, limit: Int): List<CapturedNotification> =
        items.filter { app.isNullOrBlank() || it.app.contains(app, ignoreCase = true) }.take(limit.coerceIn(1, MAX))

    @Synchronized
    fun find(id: String): CapturedNotification? = items.firstOrNull { it.id == id }

    @Synchronized
    fun clear() = items.clear()

    /** Sends [text] through the notification's RemoteInput reply action. */
    fun reply(ctx: Context, n: CapturedNotification, text: String): Boolean {
        val action = n.replyAction ?: return false
        val inputs = action.remoteInputs?.filter { it.allowFreeFormInput } ?: return false
        if (inputs.isEmpty()) return false
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs.toTypedArray(), intent, results)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return try {
            action.actionIntent.send(ctx, 0, intent)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }
}

/** Fills [NotificationBuffer] (skips ongoing, group summaries, system and our own notifications). */
class NotificationCaptureService : NotificationListenerService() {
    override fun onListenerDisconnected() {
        NotificationBuffer.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.isOngoing || sbn.packageName == packageName) return
        if (sbn.packageName == "android" || sbn.packageName == "com.android.systemui") return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_SYSTEM || n.category == Notification.CATEGORY_SERVICE ||
            n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS
        ) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && body.isBlank()) return
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) {
            sbn.packageName
        }
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        NotificationBuffer.add(
            CapturedNotification(
                id = NotificationBuffer.nextId(),
                key = sbn.key,
                app = appName,
                pkg = sbn.packageName,
                title = title.take(200),
                text = body.take(600),
                time = sbn.postTime,
                replyAction = reply,
            )
        )
    }
}
