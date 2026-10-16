package com.mrarron.wristclaude.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mrarron.wristclaude.data.DraftStore
import com.mrarron.wristclaude.phone.Notifications
import java.text.DateFormat
import java.util.Date

@Composable
fun DraftsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val drafts by DraftStore.drafts.collectAsState()
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Drafts", style = MaterialTheme.typography.headlineMedium)
        if (drafts.isEmpty()) {
            Text("No drafts yet. Ask on the watch, for example \"text Sam that I'm running late\".")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(drafts, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            (if (d.kind == "email") "Email" else "Text") + (d.to?.let { " to $it" } ?: "") + " - " +
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(d.createdAt)),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        d.subject?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                        Text(d.body, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                try {
                                    ctx.startActivity(if (d.kind == "email") Notifications.emailIntent(d) else Notifications.smsIntent(d))
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(ctx, "No app can open this draft", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("Open") }
                            TextButton(onClick = {
                                Notifications.copyToClipboard(ctx, d.body)
                                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy") }
                            TextButton(onClick = { DraftStore.delete(ctx, d.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
