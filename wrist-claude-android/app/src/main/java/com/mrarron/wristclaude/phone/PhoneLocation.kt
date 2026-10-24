package com.mrarron.wristclaude.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.mrarron.wristclaude.core.Place
import com.mrarron.wristclaude.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * City-level location for "near me" questions. Only the place name (city, region, country) leaves the phone,
 * in the prompt to Claude; coordinates are never sent. Cached in memory only.
 */
object PhoneLocation {
    private const val FRESH_MS = 15 * 60_000L
    private const val STALE_MS = 6 * 60 * 60_000L
    private const val FIX_MAX_AGE_MS = 2 * 60 * 60_000L
    private const val LOOKUP_MS = 3_000L

    @Volatile private var cached: Place? = null
    @Volatile private var cachedAt = 0L

    fun hasPermission(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Needed to read the location while the phone app is in the background (asks from the watch). */
    fun hasBackground(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /** The current place, or null when it's off, not permitted, or unknown. Never takes much longer than 3 s. */
    suspend fun current(ctx: Context): Place? {
        if (!SettingsStore.current().usePhoneLocation || !hasPermission(ctx)) return null
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < FRESH_MS) return it }
        val fresh = withTimeoutOrNull(LOOKUP_MS) { lookup(ctx.applicationContext) }
        if (fresh != null) {
            cached = fresh
            cachedAt = System.currentTimeMillis()
            return fresh
        }
        return cached?.takeIf { now - cachedAt < STALE_MS }
    }

    /** Warm the cache (when the watch app opens), so the first question doesn't wait. */
    suspend fun refresh(ctx: Context) {
        current(ctx)
    }

    private suspend fun lookup(ctx: Context): Place? {
        val loc = lastFix(ctx) ?: currentFix(ctx) ?: return null
        val addr = geocode(ctx, loc) ?: return null
        return Place.of(addr.locality ?: addr.subAdminArea, addr.adminArea, addr.countryCode, addr.subLocality)
    }

    @SuppressLint("MissingPermission") // checked in current()
    private fun lastFix(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        return runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { now - it.time < FIX_MAX_AGE_MS }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission") // checked in current()
    private suspend fun currentFix(ctx: Context): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        return suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            try {
                lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { if (cont.isActive) cont.resume(it) }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private suspend fun geocode(ctx: Context, loc: Location): Address? {
        if (!Geocoder.isPresent()) return null
        val g = Geocoder(ctx, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                try {
                    g.getFromLocation(loc.latitude, loc.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { g.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() }.getOrNull()
            }
        }
    }
}
