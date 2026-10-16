package com.mrarron.wristclaude.watch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.mrarron.wristclaude.phone.Notifications
import java.util.concurrent.atomic.AtomicInteger

/**
 * Short foreground service that keeps the process alive while an ask is running, even if the watch app
 * closes and the Pebble app unbinds the listener service. Type "shortService" (Android 14+): no extra
 * permission, meant for short critical work, ~3 minute limit (an ask is capped at 60 s plus a 60 s watch
 * confirmation). If Android refuses the start from the background, the ask still runs: the Pebble app's
 * binding to PebbleListenerService keeps the process alive while the watch app is open.
 */
class AskService : Service() {
    companion object {
        private val active = AtomicInteger(0)

        fun begin(ctx: Context) {
            if (active.incrementAndGet() == 1) {
                try {
                    ctx.startForegroundService(Intent(ctx, AskService::class.java))
                } catch (_: Exception) {
                    // ForegroundServiceStartNotAllowedException etc. Work continues without it.
                }
            }
        }

        /** Set once startForeground() has run; until then onStartCommand stops the service itself. */
        @Volatile private var running: AskService? = null

        fun end(ctx: Context) {
            // Decrement without going below zero (no decrement/set race)
            val left = active.updateAndGet { if (it > 0) it - 1 else 0 }
            if (left == 0) {
                // Never stop before startForeground() ran (that would crash): onStartCommand checks the count.
                running?.stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(Notifications.WORK_ID, Notifications.workNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            } else {
                startForeground(Notifications.WORK_ID, Notifications.workNotification(this))
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = this
        if (active.get() == 0) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    // Android 14 signature
    @Deprecated("Replaced by onTimeout(Int, Int) on Android 15")
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }
}
