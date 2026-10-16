package com.mrarron.wristclaude.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mrarron.wristclaude.core.Notes
import com.mrarron.wristclaude.data.SettingsStore
import com.mrarron.wristclaude.phone.GoogleAuth
import com.mrarron.wristclaude.phone.NotesRepo
import com.mrarron.wristclaude.phone.Notifications
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private fun start(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // nothing can open it
    }
}

@Composable
fun NotesScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by GoogleAuth.connected.collectAsState()
    val queued by NotesRepo.queued.collectAsState()
    val lastError by NotesRepo.lastError.collectAsState()
    val contextText by NotesRepo.context.collectAsState()
    val contextAt by NotesRepo.contextAt.collectAsState()
    val settings by SettingsStore.settings.collectAsState()
    val tidyAt by NotesRepo.tidyAt.collectAsState()
    val tidyRemoved by NotesRepo.tidyRemoved.collectAsState()
    val tidyError by NotesRepo.tidyError.collectAsState()

    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val docsNotice by NotesRepo.docsNotice.collectAsState()
    val docsForbidden by NotesRepo.docsForbidden.collectAsState()

    fun afterConnect() {
        scope.launch {
            status = "Connected. Setting up your docs..."
            status = NotesRepo.connectSetup(ctx) ?: "Connected. Notes and context are working."
        }
    }

    val approve = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        busy = false
        when (val t = GoogleAuth.resultFromIntent(ctx, result.data)) {
            is GoogleAuth.Token.Ok -> { GoogleAuth.setConnected(ctx, true); afterConnect() }
            is GoogleAuth.Token.NeedsUser -> status = "Google access wasn't granted."
            is GoogleAuth.Token.ScopeMissing -> status = Notes.MSG_SCOPE_MISSING
            is GoogleAuth.Token.Failed -> status = "Google sign-in didn't finish (${t.reason})."
        }
    }

    fun connect() {
        busy = true
        status = ""
        scope.launch {
            when (val t = GoogleAuth.token(ctx)) {
                is GoogleAuth.Token.Ok -> { busy = false; GoogleAuth.setConnected(ctx, true); afterConnect() }
                is GoogleAuth.Token.NeedsUser -> {
                    val pi = t.pendingIntent
                    if (pi == null) { busy = false; status = "Google sign-in isn't available." }
                    else approve.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                }
                is GoogleAuth.Token.ScopeMissing -> { busy = false; status = Notes.MSG_SCOPE_MISSING }
                is GoogleAuth.Token.Failed -> {
                    busy = false
                    status = "Couldn't start Google sign-in (${t.reason}). Check that the app's Google Cloud client is set up (see the README)."
                }
            }
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Notes", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Say \"note: ...\" or \"continue on Claude\" on the watch, or hold Down on an answer, and it goes to your " +
                "Wrist Assistant Notes doc. Then say \"${Notifications.CATCH_UP}\" in the Claude app to pick it up.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Google account", style = MaterialTheme.typography.titleMedium)
                if (connected) {
                    Text("Connected. Wrist Assistant only opens the two docs it created.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { connect() }, enabled = !busy) { Text("Reconnect") }
                        TextButton(onClick = {
                            GoogleAuth.setConnected(ctx, false); NotesRepo.clearAll(ctx)
                            scope.launch { NotesRepo.forgetDocs(ctx); status = "Disconnected. Your old docs stay in Google Drive." }
                        }) {
                            Text("Disconnect")
                        }
                    }
                } else {
                    Text("Connect your Google account. Wrist Assistant then creates its own notes and context docs in your Drive and only opens those.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { connect() }, enabled = !busy) { Text(if (busy) "Connecting..." else "Connect Google account") }
                }
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                docsNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (connected && docsForbidden) {
                    OutlinedButton(onClick = {
                        scope.launch { busy = true; status = NotesRepo.makeNewDocs(ctx) ?: "Made new docs. Your old docs stay in Google Drive."; busy = false }
                    }, enabled = !busy) { Text("Make new docs") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Notes doc", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (queued == 0) "Nothing waiting to sync." else "$queued waiting to sync" + (lastError?.let { ": $it" } ?: "."),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (settings.notesDoc.isBlank()) Text("Not created yet \u2014 connect Google", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { start(ctx, Notifications.docIntent(settings.notesDoc)) },
                        enabled = settings.notesDoc.isNotBlank(),
                    ) { Text("Open notes") }
                    OutlinedButton(onClick = { start(ctx, Notifications.claudeIntent(ctx)) }) { Text("Open in Claude") }
                }
                if (queued > 0 && connected) {
                    TextButton(onClick = { scope.launch { status = NotesRepo.flush(ctx) ?: "All notes synced." } }) { Text("Retry now") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Show a notification after a handoff", Modifier.weight(1f))
                    Switch(settings.handoffNotification, { SettingsStore.update(ctx) { s -> s.copy(handoffNotification = it) } })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Remove done notes from the doc", Modifier.weight(1f))
                    Switch(settings.tidyDone, { SettingsStore.update(ctx) { s -> s.copy(tidyDone = it) } })
                }
                Text(
                    if (tidyAt == 0L) "Last tidy: never."
                    else "Last tidy: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(tidyAt)) +
                        ", removed $tidyRemoved done note" + (if (tidyRemoved == 1) "." else "s."),
                    style = MaterialTheme.typography.bodySmall,
                )
                tidyError?.let { Text("Last tidy attempt failed: $it", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { scope.launch { status = NotesRepo.tidy(ctx) } }, enabled = connected) { Text("Tidy now") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Context from Claude", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your main Claude updates a short summary of what you're working on twice a day. The watch uses it as background.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Use context from Claude", Modifier.weight(1f))
                    Switch(settings.useContext, { SettingsStore.update(ctx) { s -> s.copy(useContext = it) } })
                }
                if (settings.useContext) {
                    Text(
                        if (contextAt == 0L) "Not loaded yet."
                        else "Last loaded " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(contextAt)) + ".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!contextText.isNullOrBlank()) {
                        Text(contextText.orEmpty(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    if (settings.contextDoc.isBlank()) Text("Not created yet \u2014 connect Google", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { scope.launch { status = NotesRepo.refreshContext(ctx) ?: "Context refreshed." } },
                            enabled = connected,
                        ) { Text("Refresh now") }
                        TextButton(
                            onClick = { start(ctx, Notifications.docIntent(settings.contextDoc)) },
                            enabled = settings.contextDoc.isNotBlank(),
                        ) { Text("Open context doc") }
                    }
                }
            }
        }
    }
}
