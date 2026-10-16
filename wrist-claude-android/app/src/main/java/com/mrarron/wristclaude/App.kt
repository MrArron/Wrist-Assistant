package com.mrarron.wristclaude

import android.app.Application
import com.mrarron.wristclaude.core.AppSettings
import com.mrarron.wristclaude.core.ClaudeClient
import com.mrarron.wristclaude.core.OkHttpTransport
import com.mrarron.wristclaude.core.ToolExecutor
import com.mrarron.wristclaude.data.DraftStore
import com.mrarron.wristclaude.data.SecureKeyStore
import com.mrarron.wristclaude.data.SettingsStore
import com.mrarron.wristclaude.phone.GoogleAuth
import com.mrarron.wristclaude.phone.Notifications
import com.mrarron.wristclaude.phone.NotesRepo
import com.mrarron.wristclaude.phone.PhoneTools
import com.mrarron.wristclaude.phone.Speaker
import com.mrarron.wristclaude.watch.AskEngine
import com.mrarron.wristclaude.watch.AskService
import com.mrarron.wristclaude.watch.PebbleKitTransport
import com.mrarron.wristclaude.watch.WatchConfirmer
import com.mrarron.wristclaude.watch.WatchLink
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class App : Application() {
    companion object {
        private lateinit var instance: App
        fun get() = instance
    }

    /** App-wide scope: outlives the listener service binding. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = ClaudeClient(OkHttpTransport())
    val watchAppOpen = MutableStateFlow(false)

    lateinit var link: WatchLink
        private set
    lateinit var confirmer: WatchConfirmer
        private set
    lateinit var engine: AskEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Only talk to the Pebble app the user explicitly granted on the Setup screen.
        DefaultPebbleAndroidAppPicker.getInstance(this).enableAutoSelect = false
        SettingsStore.init(this)
        SecureKeyStore.init(this)
        DraftStore.init(this)
        Speaker.init(this)
        Notifications.createChannels(this)
        GoogleAuth.init(this)
        NotesRepo.init(this)

        link = WatchLink(PebbleKitTransport(this), scope)
        confirmer = WatchConfirmer(link)
        val app = this
        engine = AskEngine(scope, link, confirmer, client, object : AskEngine.Env {
            override fun settings(): AppSettings = SettingsStore.current()
            override fun apiKey(): String? = SecureKeyStore.load(app)
            override fun hasKey(): Boolean = SecureKeyStore.hasKey.value
            override fun tools(): ToolExecutor = PhoneTools(app, confirmer, inForeground = false)
            override fun onWatchOpen() {
                scope.launch { NotesRepo.onWatchOpen(app) }
            }

            override fun speak(on: Boolean) = if (on) Speaker.speak() else Speaker.stop()
            override fun onAnswer(answer: String) {
                Speaker.lastAnswer = answer
                if (SettingsStore.current().autoRead) Speaker.speak(answer)
            }

            override suspend fun <T> keepAlive(block: suspend () -> T): T {
                AskService.begin(app)
                try {
                    return block()
                } finally {
                    AskService.end(app)
                }
            }
        })
    }
}
