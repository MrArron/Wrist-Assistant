package com.mrarron.wristclaude.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mrarron.wristclaude.core.Updates
import com.mrarron.wristclaude.phone.AppUpdater
import kotlinx.coroutines.launch

private fun startSafely(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

/** Opens the installer, or first the "Install unknown apps" switch for this app if it's off. */
private fun install(ctx: Context) {
    if (!ctx.packageManager.canRequestPackageInstalls()) {
        startSafely(ctx, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, ("package:" + ctx.packageName).toUri()))
        return
    }
    AppUpdater.installIntent(ctx)?.let { startSafely(ctx, it) } ?: AppUpdater.refreshDownload(ctx)
}

/** What a version mismatch means for the user, or null when the versions match (or the watch hasn't said). */
fun mismatchText(phoneCode: Int, watchCode: Int?): String? = when {
    watchCode == null || phoneCode <= 0 || watchCode == phoneCode -> null
    watchCode > phoneCode -> "The watch app is ${Updates.versionName(watchCode)} but this phone app is ${Updates.versionName(phoneCode)}. " +
        "Update the phone app so everything works together."
    else -> "This phone app is ${Updates.versionName(phoneCode)} but the watch app is ${Updates.versionName(watchCode)}. " +
        "The Pebble app updates the watch app by itself; open the Pebble app to get it sooner."
}

/** Setup tab: shown only when there's something to do (an update, a download, a version mismatch). */
@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    val s by AppUpdater.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { AppUpdater.refreshDownload(ctx) }
    val mismatch = mismatchText(AppUpdater.installedVersionCode(ctx), s.watchVersion)
    if (!s.updateAvailable && mismatch == null) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val latest = s.latest
            if (s.updateAvailable && latest != null) {
                Text("Update available: ${latest.version}", style = MaterialTheme.typography.titleMedium)
                Text("You have ${s.installed}. Your settings and API key are kept.", style = MaterialTheme.typography.bodySmall)
                UpdateButtons(ctx, s, latest.pageUrl)
            }
            if (mismatch != null) Text(mismatch, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UpdateButtons(ctx: Context, s: AppUpdater.State, pageUrl: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (s.download) {
            AppUpdater.Download.RUNNING -> Text("Downloading... (see the notification)")
            AppUpdater.Download.DONE -> FilledTonalButton(onClick = { install(ctx) }) { Text("Install") }
            else -> FilledTonalButton(onClick = { AppUpdater.download(ctx) }) { Text("Download and install") }
        }
        TextButton(onClick = { startSafely(ctx, Intent(Intent.ACTION_VIEW, pageUrl.toUri())) }) { Text("What's new") }
    }
    if (s.download == AppUpdater.Download.FAILED) {
        Text("The download didn't finish. Try again, or get the APK from the release page.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (s.download == AppUpdater.Download.DONE && !ctx.packageManager.canRequestPackageInstalls()) {
        Text(
            "Android asks you to allow \"Install unknown apps\" for Wrist Assistant once. Turn it on, come back and tap Install.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Settings tab: the on/off switch lives in the form; this shows the state and a Check now button. */
@Composable
fun UpdateStatus() {
    val ctx = LocalContext.current
    val s by AppUpdater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val latest = s.latest
    Text(
        when {
            s.checking -> "Checking..."
            s.error != null -> s.error!!
            s.updateAvailable && latest != null -> "Version ${latest.version} is available (you have ${s.installed}). Install it from the Setup tab."
            s.lastCheck > 0 -> "You have the latest version (${s.installed})."
            else -> "You have version ${s.installed}."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    TextButton(enabled = !s.checking, onClick = { scope.launch { AppUpdater.check(ctx, force = true) } }) { Text("Check now") }
}
