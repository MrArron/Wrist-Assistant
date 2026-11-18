package com.mrarron.wristclaude.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mrarron.wristclaude.App
import com.mrarron.wristclaude.R
import com.mrarron.wristclaude.core.Notes
import com.mrarron.wristclaude.core.Relay
import com.mrarron.wristclaude.data.SettingsStore
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * "Relay to watch" (off by default): notifications from apps the user picked are re-posted as a Wrist Assistant
 * notification on its own channel. The Pebble app mirrors it to the watch with two actions:
 * - Reply (only when the original has a reply box): the user dictates on the watch and the text goes through the
 *   original notification's own reply action. The user writes and sends it themselves, like a normal watch reply.
 * - Save to notes: adds a FOLLOWUP entry to the notes doc, so the main Claude or Duet can pick it up.
 * Text stays in memory and in the system notification only; nothing is written to disk or sent to Claude.
 */
object WatchRelay {
    const val CHANNEL = "relay"
    /** FLAG_MUTABLE exists from Android 12; before that PendingIntents are mutable by default. */
    private val MUTABLE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    private val seen = Relay.Seen()

    fun createChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Relayed to your watch", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Messages from the apps you chose in Settings > Relay to watch, with Reply and Save to notes"
            }
        )
    }

    fun wants(pkg: String, ownPackage: String): Boolean =
        SettingsStore.current().let { Relay.shouldRelay(it.relayEnabled, it.relayApps, pkg, ownPackage) }

    fun clearSeen() = seen.clear()

    fun post(ctx: Context, n: CapturedNotification) {
        if (!Notifications.canPost(ctx)) return
        if (seen.seenBefore(Relay.fingerprint(n.key, n.title, n.text))) return
        val nid = Relay.notificationId(n.key)
        val title = Relay.title(n.app, n.title)
        val text = Relay.text(n.text)
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_claude)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
        n.contentIntent?.let { b.setContentIntent(it) }
        if (n.canReply) {
            val input = RemoteInput.Builder(RelayActionReceiver.KEY_TEXT).setLabel("Reply to ${n.app}").build()
            // Mutable: the system fills in the RemoteInput result. The intent is explicit, so nothing else can catch it.
            val pi = PendingIntent.getBroadcast(
                ctx, nid * 2, action(ctx, RelayActionReceiver.ACTION_REPLY, n, nid),
                MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(null, "Reply", pi).addRemoteInput(input).setAllowGeneratedReplies(false).build())
        }
        if (SettingsStore.current().notesDoc.isNotBlank()) {
            val pi = PendingIntent.getBroadcast(
                ctx, nid * 2 + 1, action(ctx, RelayActionReceiver.ACTION_SAVE, n, nid),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(null, "Save to notes", pi).build())
        }
        ctx.getSystemService(NotificationManager::class.java).notify(nid, b.build())
    }

    private fun action(ctx: Context, what: String, n: CapturedNotification, nid: Int) =
        Intent(ctx, RelayActionReceiver::class.java).setAction(what)
            .putExtra(RelayActionReceiver.EXTRA_KEY, n.key)
            .putExtra(RelayActionReceiver.EXTRA_NID, nid)
            .putExtra(RelayActionReceiver.EXTRA_APP, n.app)
            .putExtra(RelayActionReceiver.EXTRA_TITLE, n.title)
            .putExtra(RelayActionReceiver.EXTRA_TEXT, n.text)

    /** Replaces the relayed notification with a quiet one-line result. */
    fun done(ctx: Context, nid: Int, title: String, line: String) {
        if (!Notifications.canPost(ctx)) return
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_claude)
            .setContentTitle(title)
            .setContentText(line)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(60_000)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(nid, n)
    }
}

class RelayActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REPLY = "com.mrarron.wristclaude.relay.REPLY"
        const val ACTION_SAVE = "com.mrarron.wristclaude.relay.SAVE"
        const val KEY_TEXT = "relay_reply"
        const val EXTRA_KEY = "key"
        const val EXTRA_NID = "nid"
        const val EXTRA_APP = "app"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val nid = intent.getIntExtra(EXTRA_NID, 0)
        val app = intent.getStringExtra(EXTRA_APP).orEmpty()
        val title = Relay.title(app, intent.getStringExtra(EXTRA_TITLE).orEmpty())
        when (intent.action) {
            ACTION_REPLY -> {
                val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
                if (reply.isEmpty()) return
                val n = intent.getStringExtra(EXTRA_KEY)?.let { NotificationBuffer.findByKey(it) }
                val ok = n != null && NotificationBuffer.reply(context, n, reply)
                WatchRelay.done(context, nid, title, if (ok) "Reply sent." else "Couldn't reply: the message is gone. Open ${app.ifBlank { "the app" }} to answer.")
            }
            ACTION_SAVE -> {
                val text = Relay.noteText(app, intent.getStringExtra(EXTRA_TITLE).orEmpty(), intent.getStringExtra(EXTRA_TEXT).orEmpty())
                val ctx = context.applicationContext
                val pending = goAsync()
                App.get().scope.launch {
                    try {
                        val r = NotesRepo.save(ctx, Notes.noteEntry(text, followup = true, project = null, now = ZonedDateTime.now()), timeoutMs = 8_000)
                        WatchRelay.done(
                            ctx, nid, title,
                            when (r) {
                                is NotesRepo.SaveResult.Saved -> "Saved to your notes."
                                is NotesRepo.SaveResult.Queued -> "Saved on the phone; it will sync to your notes."
                                else -> "Couldn't save it. Check the Notes tab."
                            },
                        )
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
