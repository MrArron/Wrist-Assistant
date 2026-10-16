package com.mrarron.wristclaude.watch

import com.mrarron.wristclaude.App
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID

/**
 * Bound by the Pebble app while Wrist Assistant runs on the watch. Only the Pebble app the user granted
 * (DefaultPebbleAndroidAppPicker, auto-select off) can reach it, and only our watch app UUID is handled.
 * Messages are acknowledged right away; the Claude work runs in the app-wide scope, not here.
 */
class PebbleListenerService : BasePebbleListenerService() {
    override suspend fun onMessageReceived(watchappUUID: UUID, data: PebbleDictionary, watch: WatchIdentifier): ReceiveResult {
        if (watchappUUID != Keys.WATCH_UUID) return ReceiveResult.Nack
        App.get().engine.onMessage(data)
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != Keys.WATCH_UUID) return
        App.get().watchAppOpen.value = true
        App.get().engine.sendHello()
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == Keys.WATCH_UUID) App.get().watchAppOpen.value = false
    }
}
