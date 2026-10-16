package com.mrarron.wristclaude.phone

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mrarron.wristclaude.data.SettingsStore

/** Reads answers aloud with TextToSpeech on the assistant stream, holding transient audio focus. */
@SuppressLint("StaticFieldLeak") // holds only the Application context
object Speaker {
    @Volatile var lastAnswer: String = ""

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    private var focusRequest: AudioFocusRequest? = null
    private var rateOverride: Float? = null
    private lateinit var appCtx: Context

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    @Synchronized
    private fun engine(): TextToSpeech {
        tts?.let { return it }
        val t = TextToSpeech(appCtx) { status ->
            synchronized(this) {
                ready = status == TextToSpeech.SUCCESS
                val p = pendingText
                pendingText = null
                if (ready && p != null) speakNow(p)
            }
        }
        t.setAudioAttributes(attrs)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = abandonFocus()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = abandonFocus()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = abandonFocus()
        })
        tts = t
        return t
    }

    /** Speak [text] (default: the latest answer). */
    @Synchronized
    fun speak(text: String = lastAnswer, rate: Float? = null) {
        if (text.isBlank()) return
        rateOverride = rate
        engine()
        if (!ready) {
            pendingText = text; return
        }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        val t = tts ?: return
        t.setSpeechRate(rateOverride ?: SettingsStore.current().speechRate)
        val am = appCtx.getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
            }
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer")
    }

    @Synchronized
    fun stop() {
        pendingText = null
        tts?.stop()
        abandonFocus()
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        appCtx.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(req)
    }
}
