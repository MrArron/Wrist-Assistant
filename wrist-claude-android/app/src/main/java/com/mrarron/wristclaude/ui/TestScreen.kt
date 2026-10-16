package com.mrarron.wristclaude.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mrarron.wristclaude.App
import com.mrarron.wristclaude.core.AskResult
import com.mrarron.wristclaude.core.ChatMessage
import com.mrarron.wristclaude.core.ClaudeClient
import com.mrarron.wristclaude.core.ConfirmOutcome
import com.mrarron.wristclaude.core.Confirmer
import com.mrarron.wristclaude.core.Recipients
import com.mrarron.wristclaude.core.TextUtil
import com.mrarron.wristclaude.core.TimeUtil
import com.mrarron.wristclaude.data.SecureKeyStore
import com.mrarron.wristclaude.data.SettingsStore
import com.mrarron.wristclaude.phone.PhoneTools
import com.mrarron.wristclaude.phone.Speaker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Ask without a watch and see exactly what the watch would receive. Confirmations appear as a dialog. */
@Composable
fun TestScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var running by remember { mutableStateOf<Job?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var chunks by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var historyTainted by remember { mutableStateOf(false) }
    var confirmText by remember { mutableStateOf<String?>(null) }
    var confirmWaiter by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    val confirmer = remember {
        Confirmer { text ->
            // Same rule as the watch: show exactly what will be approved, or nothing.
            if (!Recipients.isDisplayable(text)) return@Confirmer ConfirmOutcome.NO_ANSWER
            val w = CompletableDeferred<Boolean>()
            confirmWaiter = w
            confirmText = text
            val r = try {
                withTimeoutOrNull(60_000L) { w.await() }
            } finally {
                confirmText = null
                confirmWaiter = null
            }
            when (r) { true -> ConfirmOutcome.APPROVED; false -> ConfirmOutcome.DECLINED; null -> ConfirmOutcome.NO_ANSWER }
        }
    }

    fun run(followUp: Boolean) {
        val q = question.trim()
        if (q.isEmpty()) return
        log.clear(); chunks = emptyList(); error = null
        val hist = if (followUp) history.takeLast(ClaudeClient.MAX_HISTORY) else emptyList()
        val startTainted = followUp && historyTainted
        running = scope.launch {
            val tools = PhoneTools(ctx, confirmer, inForeground = true)
            val result = App.get().client.ask(
                SettingsStore.current(), SecureKeyStore.load(ctx), hist, q, tools,
                object : ClaudeClient.Callbacks {
                    override suspend fun onStatus(text: String) { log += "STATUS  $text" }
                    override suspend fun onReminder(unix: Long, text: String) {
                        log += "REMINDER  ${TimeUtil.formatReminder(unix, java.time.ZonedDateTime.now())}  $text"
                    }
                },
                startTainted,
            )
            when (result) {
                is AskResult.Answer -> {
                    chunks = TextUtil.watchChunks(result.text)
                    historyTainted = result.tainted
                    history = (hist + ChatMessage("user", q) + ChatMessage("assistant", result.text)).takeLast(ClaudeClient.MAX_HISTORY)
                    Speaker.lastAnswer = result.text
                    if (SettingsStore.current().autoRead) Speaker.speak(result.text)
                }
                is AskResult.Error -> error = result.text
            }
            running = null
        }
    }

    confirmText?.let { text ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Approve? (shown on the watch)") },
            text = { Text(text, fontFamily = FontFamily.Monospace) },
            confirmButton = { TextButton(onClick = { confirmWaiter?.complete(true) }) { Text("Approve") } },
            dismissButton = { TextButton(onClick = { confirmWaiter?.complete(false) }) { Text("Decline") } },
        )
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Test", style = MaterialTheme.typography.headlineMedium)
        Text("Ask a question and see the exact watch-formatted answer. Phone tools run for real.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(question, { question = it.take(500) }, label = { Text("Question") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = running == null, onClick = { run(false) }) { Text("Ask") }
            OutlinedButton(enabled = running == null && history.isNotEmpty(), onClick = { run(true) }) { Text("Follow-up") }
            if (running != null) TextButton(onClick = { running?.cancel(); running = null }) { Text("Cancel") }
        }
        if (log.isNotEmpty()) {
            Text(log.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("ERROR  $it", Modifier.padding(12.dp), fontFamily = FontFamily.Monospace)
            }
        }
        if (chunks.isNotEmpty()) {
            Text(
                "${chunks.size} CHUNK message(s), ${TextUtil.utf8Bytes(chunks.joinToString(""))} bytes",
                style = MaterialTheme.typography.labelLarge,
            )
            Card(Modifier.fillMaxWidth()) {
                Text(chunks.joinToString(""), Modifier.padding(12.dp), fontFamily = FontFamily.Monospace)
            }
            chunks.forEachIndexed { i, c ->
                Text(
                    "#${i + 1} (${TextUtil.utf8Bytes(c)} B)${if (i == chunks.lastIndex) " FINAL" else ""}: " + c.replace("\n", "\\n"),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Speaker.speak() }) { Text("Read aloud") }
                TextButton(onClick = { Speaker.stop() }) { Text("Stop") }
            }
        }
    }
}
