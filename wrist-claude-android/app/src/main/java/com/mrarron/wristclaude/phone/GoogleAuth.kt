package com.mrarron.wristclaude.phone

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google authorization for the notes and context docs, via the Identity AuthorizationClient.
 * Scope: drive.file only (non-sensitive): the app can open just the files it created itself, i.e. the
 * notes and context docs it makes via the Docs API.
 * The access token is never stored or logged; Google Play services caches and refreshes it.
 */
object GoogleAuth {
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val PREFS = "google"
    private const val CONNECTED = "connected"

    private val _connected = MutableStateFlow(false)
    /** The user completed Google authorization at least once (and hasn't disconnected). */
    val connected: StateFlow<Boolean> = _connected

    fun init(ctx: Context) {
        _connected.value = prefs(ctx).getBoolean(CONNECTED, false)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setConnected(ctx: Context, on: Boolean) {
        prefs(ctx).edit { putBoolean(CONNECTED, on) }
        _connected.value = on
    }

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE))).build()

    sealed class Token {
        data class Ok(val accessToken: String) : Token()
        /** The user has to approve in the app (first time, or access was revoked). */
        data class NeedsUser(val pendingIntent: PendingIntent?) : Token()
        /** Authorization succeeded but the user didn't tick the box to let Wrist Assistant use the files it creates. */
        object ScopeMissing : Token()
        data class Failed(val reason: String) : Token()
    }

    private suspend fun <T> Task<T>.awaitResult(): Result<T> = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(Result.success(it)) }
        addOnFailureListener { if (cont.isActive) cont.resume(Result.failure(it)) }
        addOnCanceledListener { if (cont.isActive) cont.resume(Result.failure(IllegalStateException("canceled"))) }
    }

    private fun toToken(r: AuthorizationResult): Token {
        if (r.hasResolution()) return Token.NeedsUser(r.pendingIntent)
        val t = r.accessToken
        if (t.isNullOrEmpty()) return Token.Failed("No access token")
        if (r.grantedScopes.isNotEmpty() && DRIVE_FILE_SCOPE !in r.grantedScopes) return Token.ScopeMissing
        return Token.Ok(t)
    }

    /** Gets a token without UI (works from the background once the user has approved). */
    suspend fun token(ctx: Context): Token {
        val r = Identity.getAuthorizationClient(ctx.applicationContext).authorize(request()).awaitResult()
        return r.fold({ toToken(it) }, { Token.Failed(it.javaClass.simpleName) })
    }

    /** Reads the result of the approval screen launched from the app. */
    fun resultFromIntent(ctx: Context, data: Intent?): Token = try {
        toToken(Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data))
    } catch (e: Exception) {
        Token.Failed(e.javaClass.simpleName)
    }
}
