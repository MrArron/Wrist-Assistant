package com.mrarron.wristclaude.ui

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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mrarron.wristclaude.App
import com.mrarron.wristclaude.core.AppSettings
import com.mrarron.wristclaude.core.QuickPrompt
import com.mrarron.wristclaude.data.SettingsStore
import com.mrarron.wristclaude.phone.CalendarRepo
import com.mrarron.wristclaude.phone.Speaker
import java.util.Locale

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val initial = remember { SettingsStore.current() }
    var model by remember { mutableStateOf(initial.model) }
    var length by remember { mutableStateOf(initial.length) }
    var webSearch by remember { mutableStateOf(initial.webSearch) }
    var units by remember { mutableStateOf(initial.units) }
    var city by remember { mutableStateOf(initial.city) }
    var aboutMe by remember { mutableStateOf(initial.aboutMe) }
    val prompts = remember {
        mutableStateListOf<QuickPrompt>().apply {
            addAll(initial.quickPrompts)
            while (size < AppSettings.MAX_PROMPTS) add(QuickPrompt("", ""))
        }
    }
    var calendarId by remember { mutableLongStateOf(initial.defaultCalendarId) }
    var speechRate by remember { mutableFloatStateOf(initial.speechRate) }
    var autoRead by remember { mutableStateOf(initial.autoRead) }
    var savedNote by remember { mutableStateOf("") }
    val calendars = remember { CalendarRepo.calendars(ctx).filter { it.writable } }

    // Starts from the stored settings so fields edited on other tabs (notes docs, context) are kept.
    fun apply(base: AppSettings) = base.copy(
        model = model, length = length, webSearch = webSearch, units = units, city = city, aboutMe = aboutMe,
        quickPrompts = prompts.toList(), defaultCalendarId = calendarId, speechRate = speechRate, autoRead = autoRead,
    )

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Section("Answers") {
            Choice("Model", AppSettings.MODELS, model, { AppSettings.MODEL_LABELS[it] ?: it }) { model = it }
            Choice("Answer length", AppSettings.LENGTHS, length, {
                when (it) { "long" -> "Long (about 150 words)"; "medium" -> "Medium (about 80 words)"; else -> "Short (about 40 words)" }
            }) { length = it }
            SwitchRow("Web search for current info", webSearch) { webSearch = it }
            Choice("Units", AppSettings.UNITS, units, { if (it == "metric") "Metric (C, km)" else "Imperial (F, miles)" }) { units = it }
        }

        Section("About you") {
            OutlinedTextField(
                city, { city = it.take(AppSettings.MAX_CITY) }, label = { Text("City (optional, for local answers)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                aboutMe, { aboutMe = it.take(AppSettings.MAX_ABOUT) },
                label = { Text("About me (optional)") },
                supportingText = { Text("${aboutMe.length} / ${AppSettings.MAX_ABOUT}") },
                minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
        }

        Section("Quick prompts") {
            Text("Up to 5. Leave a row empty to remove it. Label up to 24 characters. Put {topic} in a prompt to get a random subject each time.", style = MaterialTheme.typography.bodySmall)
            prompts.forEachIndexed { i, q ->
                OutlinedTextField(
                    q.label, { prompts[i] = q.copy(label = it.take(AppSettings.MAX_LABEL)) },
                    label = { Text("Label ${i + 1}") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    q.prompt, { prompts[i] = q.copy(prompt = it.take(AppSettings.MAX_PROMPT)) },
                    label = { Text("Prompt ${i + 1}") }, modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Section("Phone") {
            if (calendars.isEmpty()) {
                Text("Default calendar: allow Calendar on the Setup tab to choose one.", style = MaterialTheme.typography.bodyMedium)
            } else {
                val ids = listOf(-1L) + calendars.map { it.id }
                Choice("Default calendar for new events", ids, calendarId, { id ->
                    if (id == -1L) "Automatic (first primary calendar)"
                    else calendars.first { it.id == id }.let { c -> c.name + if (c.account.isNotBlank() && c.account != c.name) " (${c.account})" else "" }
                }) { calendarId = it }
            }
            Text("Speech rate: " + String.format(Locale.US, "%.1fx", speechRate))
            Slider(speechRate, { speechRate = (Math.round(it * 10) / 10f) }, valueRange = 0.5f..2.0f, steps = 14)
            SwitchRow("Read every answer aloud automatically", autoRead) { autoRead = it }
            OutlinedButton(onClick = { Speaker.speak("This is how answers will sound.", rate = speechRate) }) { Text("Try the voice") }
        }

        Button(onClick = {
            val saved = SettingsStore.update(ctx) { apply(it) }
            prompts.clear(); prompts.addAll(saved.quickPrompts)
            while (prompts.size < AppSettings.MAX_PROMPTS) prompts.add(QuickPrompt("", ""))
            App.get().engine.sendHello()
            savedNote = "Saved. The watch menu updates when Wrist Assistant is open."
        }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        if (savedNote.isNotEmpty()) Text(savedNote, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(label: String, options: List<T>, selected: T, display: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = display(selected), onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(display(o)) }, onClick = { onSelect(o); expanded = false })
            }
        }
    }
}
