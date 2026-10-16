package com.mrarron.wristclaude.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mrarron.wristclaude.App
import com.mrarron.wristclaude.data.SecureKeyStore
import com.mrarron.wristclaude.phone.Notifications
import com.mrarron.wristclaude.phone.GoogleAuth
import com.mrarron.wristclaude.phone.PhoneTools
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import io.rebble.pebblekit2.client.ui.PebbleAppPermissionDialog
import io.rebble.pebblekit2.model.ConnectedWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn

private fun granted(ctx: Context, perm: String) =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

private fun open(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:" + ctx.packageName).toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun SetupScreen(modifier: Modifier = Modifier, onOpenNotes: () -> Unit = {}) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val picker = remember { DefaultPebbleAndroidAppPicker.getInstance(ctx) }
    var showPebbleDialog by remember { mutableStateOf(false) }
    val selectedApp by produceState<String?>(null, refresh, showPebbleDialog) { value = picker.getCurrentlySelectedApp() }
    val eligible = remember(refresh) { picker.getAllEligibleApps() }
    val watches by produceState<List<ConnectedWatch>?>(null, selectedApp) {
        if (selectedApp == null) {
            value = emptyList(); return@produceState
        }
        DefaultPebbleInfoRetriever(ctx).getConnectedWatches().flowOn(Dispatchers.IO).catch { emit(emptyList()) }.collect { value = it }
    }
    val watchAppOpen by App.get().watchAppOpen.collectAsState()
    val hasKey by SecureKeyStore.hasKey.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    if (showPebbleDialog) {
        PebbleAppPermissionDialog(picker, onDismiss = {
            showPebbleDialog = false
            refresh++
            App.get().engine.sendHello()
        })
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Wrist Assistant", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Work through this list once. Everything stays on this phone; questions go only to api.anthropic.com.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Ask Claude from your wrist. Not affiliated with or endorsed by Anthropic.",
            style = MaterialTheme.typography.bodySmall,
        )

        // ---- API key ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusTitle("Anthropic API key", hasKey)
                var keyText by remember { mutableStateOf("") }
                var keyError by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text(if (hasKey) "Key saved - paste a new one to replace" else "sk-ant-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = keyText.isNotBlank(), onClick = {
                        if (SecureKeyStore.save(ctx, keyText)) {
                            keyText = ""; keyError = false; App.get().engine.sendHello()
                        } else {
                            keyError = true
                        }
                    }) { Text("Save key") }
                    if (hasKey) TextButton(onClick = { SecureKeyStore.clear(ctx); App.get().engine.sendHello() }) { Text("Remove key") }
                }
                if (keyError) {
                    Text(
                        "Couldn't save the key: the phone's secure key storage failed. Try again, or restart the phone.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Get a key at console.anthropic.com. It is encrypted with the Android Keystore.", style = MaterialTheme.typography.bodySmall)
            }
        }

        // ---- Pebble ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val connected = selectedApp != null && !watches.isNullOrEmpty()
                StatusTitle("Pebble connection", connected)
                when {
                    eligible.isEmpty() -> Text("No Pebble app found. Install the Pebble app (1.0.7.7 or newer).")
                    selectedApp == null -> Text("Allow Wrist Assistant to talk to the Pebble app.")
                    watches == null -> Text("Checking...")
                    watches!!.isEmpty() -> Text("Pebble app allowed, but no watch is connected right now.")
                    else -> Text("Connected: " + watches!!.joinToString { it.name })
                }
                if (selectedApp != null) {
                    Text(
                        if (watchAppOpen) "Wrist Assistant is open on the watch." else "Open Wrist Assistant on the watch to use the companion.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "If you close Wrist Assistant on the watch while a question is still being answered, the answer may be lost: " +
                        "newer Android versions can refuse to keep the phone app working in the background. Keep the watch app open until the answer shows.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (eligible.isNotEmpty()) {
                    FilledTonalButton(onClick = { showPebbleDialog = true }) {
                        Text(if (selectedApp == null) "Connect to Pebble app" else "Change Pebble app permission")
                    }
                }
            }
        }

        Text("Permissions", style = MaterialTheme.typography.titleMedium)

        val notifOk = remember(refresh) { Notifications.canPost(ctx) }
        PermissionRow("Post notifications", "For drafts and tap-to-set alarms.", notifOk, "Allow") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.hasPostPermission(ctx)) {
                permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                open(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
        }

        val calOk = remember(refresh) { granted(ctx, Manifest.permission.READ_CALENDAR) && granted(ctx, Manifest.permission.WRITE_CALENDAR) }
        PermissionRow("Calendar", "Read your events and add new ones.", calOk, "Allow") {
            permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        }

        val listenerOk = remember(refresh) { PhoneTools.notificationAccess(ctx) }
        PermissionRow(
            "Notification access",
            "Lets Claude read your last 50 notifications (kept in memory only) and reply after you approve on the watch.",
            listenerOk, "Open settings",
        ) {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, PhoneTools.notificationListenerComponent(ctx).flattenToString())
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
            open(ctx, intent)
        }

        val overlayOk = remember(refresh) { Settings.canDrawOverlays(ctx) }
        PermissionRow(
            "Display over other apps",
            "Lets alarms and timers open the clock app while the phone is in your pocket. Without it you get a tap-to-set notification.",
            overlayOk, "Open settings",
        ) {
            open(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, ("package:" + ctx.packageName).toUri()))
        }

        val batteryOk = remember(refresh) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
        PermissionRow(
            "Battery: unrestricted",
            "Keeps answers going when the screen is off.",
            batteryOk, "Allow",
        ) {
            @Suppress("BatteryLife") // sideloaded companion that must answer the watch in the background
            open(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, ("package:" + ctx.packageName).toUri()))
        }

        val googleOk by GoogleAuth.connected.collectAsState()
        PermissionRow(
            "Google account for notes",
            "Lets \"continue on Claude\" and notes from the watch go to your Wrist Assistant Notes doc, and gives the watch context from your main Claude.",
            googleOk, "Set up on the Notes tab",
        ) { onOpenNotes() }

        Spacer(Modifier.padding(8.dp))
    }
}

@Composable
private fun StatusTitle(title: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = if (ok) "Done" else "Needs attention",
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PermissionRow(title: String, detail: String, ok: Boolean, button: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusTitle(title, ok)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            if (!ok) FilledTonalButton(onClick = onClick) { Text(button) }
        }
    }
}
