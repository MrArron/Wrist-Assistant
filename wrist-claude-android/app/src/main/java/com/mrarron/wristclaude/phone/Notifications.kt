package com.mrarron.wristclaude.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.mrarron.wristclaude.R
import com.mrarron.wristclaude.data.Draft
import com.mrarron.wristclaude.data.DraftStore
import com.mrarron.wristclaude.ui.MainActivity

object Notifications {
    const val CH_DRAFTS = "drafts"
    const val CH_ACTIONS = "actions"
    const val CH_WORK = "work"
    const val CH_NOTES = "notes"
    const val CLAUDE_APP = "com.anthropic.claude"
    const val CATCH_UP = "catch up on my notes"
    const val WORK_ID = 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_DRAFTS, "Drafts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Message and email drafts written by Claude"
                },
                NotificationChannel(CH_ACTIONS, "Alarms and timers to set", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Tap to set an alarm or timer when the phone can't open the clock app by itself"
                },
                NotificationChannel(CH_NOTES, "Handoffs", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A conversation was handed off to your notes doc"
                },
                NotificationChannel(CH_WORK, "Answering", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while Claude is answering a question from the watch"
                },
            )
        )
    }

    /** POST_NOTIFICATIONS is a runtime permission only on Android 13+; before that only the app switch counts. */
    fun hasPostPermission(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun canPost(ctx: Context) = hasPostPermission(ctx) && ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun workNotification(ctx: Context): Notification =
        Notification.Builder(ctx, CH_WORK)
            .setSmallIcon(R.drawable.ic_stat_claude)
            .setContentTitle("Answering on your watch")
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .build()

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun smsIntent(d: Draft): Intent =
        Intent(Intent.ACTION_SENDTO, ("smsto:" + android.net.Uri.encode(if (d.kind == "text") d.to.orEmpty() else "")).toUri())
            .putExtra("sms_body", d.body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun emailIntent(d: Draft): Intent {
        val to = if (d.kind == "email") d.to.orEmpty() else ""
        val uri = (
            "mailto:" + android.net.Uri.encode(to) +
                "?subject=" + android.net.Uri.encode(d.subject.orEmpty()) + "&body=" + android.net.Uri.encode(d.body)
            ).toUri()
        return Intent(Intent.ACTION_SENDTO, uri)
            .putExtra(Intent.EXTRA_SUBJECT, d.subject.orEmpty())
            .putExtra(Intent.EXTRA_TEXT, d.body)
            .apply { if (to.isNotEmpty()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to)) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun copyToClipboard(ctx: Context, text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Draft", text))
    }

    /** "Draft ready" with Open in Messages / Open in Email / Copy. Returns false if it couldn't be posted. */
    fun postDraft(ctx: Context, d: Draft): Boolean {
        if (!canPost(ctx)) return false
        val nid = (d.id % 1_000_000).toInt() + 1000
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val sms = PendingIntent.getActivity(ctx, nid * 4, smsIntent(d), flags)
        val email = PendingIntent.getActivity(ctx, nid * 4 + 1, emailIntent(d), flags)
        val copy = PendingIntent.getBroadcast(
            ctx, nid * 4 + 2,
            Intent(ctx, CopyDraftReceiver::class.java).putExtra(CopyDraftReceiver.EXTRA_ID, d.id).putExtra(CopyDraftReceiver.EXTRA_NID, nid),
            flags,
        )
        val title = if (d.kind == "email") "Email draft ready" else "Text draft ready"
        val n = Notification.Builder(ctx, CH_DRAFTS)
            .setSmallIcon(R.drawable.ic_stat_claude)
            .setContentTitle(title + (d.to?.let { " for $it" } ?: ""))
            .setContentText(d.body)
            .setStyle(Notification.BigTextStyle().bigText((d.subject?.let { "$it\n\n" } ?: "") + d.body))
            .setContentIntent(if (d.kind == "email") email else sms)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Open in Messages", sms).build())
            .addAction(Notification.Action.Builder(null, "Open in Email", email).build())
            .addAction(Notification.Action.Builder(null, "Copy", copy).build())
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(nid, n)
        return true
    }

    /** Opens the Claude app with "catch up on my notes" ready to send (falls back to the share sheet). */
    fun claudeIntent(ctx: Context): Intent {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, CATCH_UP)
        val direct = Intent(send).setPackage(CLAUDE_APP)
        val target = if (direct.resolveActivity(ctx.packageManager) != null) direct else Intent.createChooser(send, "Open in Claude")
        return target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun docIntent(docId: String): Intent =
        Intent(Intent.ACTION_VIEW, com.mrarron.wristclaude.core.Notes.docUrl(docId).toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** "Handed off: <title>" with Open in Claude / Open notes. */
    fun postHandoff(ctx: Context, title: String, docId: String): Boolean {
        if (!canPost(ctx)) return false
        val nid = (System.currentTimeMillis() % 100_000).toInt() + 3_000_000
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val claude = PendingIntent.getActivity(ctx, nid * 2, claudeIntent(ctx), flags)
        val doc = if (docId.isBlank()) null else PendingIntent.getActivity(ctx, nid * 2 + 1, docIntent(docId), flags)
        val n = Notification.Builder(ctx, CH_NOTES)
            .setSmallIcon(R.drawable.ic_stat_claude)
            .setContentTitle("Handed off: $title")
            .setContentText("Say \"$CATCH_UP\" in Claude to pick it up.")
            .setContentIntent(claude)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Open in Claude", claude).build())
            .apply { if (doc != null) addAction(Notification.Action.Builder(null, "Open notes", doc).build()) }
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(nid, n)
        return true
    }

    /** Fallback when the clock app can't be opened from the background: tap to run [intent]. */
    fun postTapToRun(ctx: Context, title: String, intent: Intent): Boolean {
        if (!canPost(ctx)) return false
        val nid = (System.currentTimeMillis() % 100_000).toInt() + 2_000_000
        val pi = PendingIntent.getActivity(ctx, nid, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CH_ACTIONS)
            .setSmallIcon(R.drawable.ic_stat_claude)
            .setContentTitle(title)
            .setContentText("Tap to open the clock app")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(nid, n)
        return true
    }
}

class CopyDraftReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_ID = "draft_id"
        const val EXTRA_NID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val d = DraftStore.get(intent.getLongExtra(EXTRA_ID, -1)) ?: return
        Notifications.copyToClipboard(context, d.body)
        Toast.makeText(context, "Draft copied", Toast.LENGTH_SHORT).show()
        context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(EXTRA_NID, 0))
    }
}
