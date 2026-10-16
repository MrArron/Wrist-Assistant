package com.mrarron.wristclaude.watch

import android.content.Context
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Sends one dictionary to the watch; true when every targeted watch reported Success. */
fun interface WatchTransport {
    suspend fun send(data: PebbleDictionary): Boolean
}

class PebbleKitTransport(context: Context) : WatchTransport {
    private val sender by lazy { DefaultPebbleSender(context.applicationContext) }

    override suspend fun send(data: PebbleDictionary): Boolean {
        val results = try {
            sender.sendDataToPebble(Keys.WATCH_UUID, data)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false
        return results.isNotEmpty() && results.values.any { it == TransmissionResult.Success }
    }
}

/**
 * Outbox: one AppMessage at a time, wait for each TransmissionResult, retry failures up to 3 times,
 * then drop the message and continue (same as the PebbleKit JS outbox).
 */
class WatchLink(
    private val transport: WatchTransport,
    scope: CoroutineScope,
    private val retryDelayMs: Long = 300,
    private val sendTimeoutMs: Long = 10_000,
) {
    companion object {
        const val MAX_RETRIES = 3
    }

    private class Item(val data: PebbleDictionary, val done: CompletableDeferred<Boolean>)

    private val lock = Mutex()
    private val queue = ArrayDeque<Item>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (u in wake) {
                while (true) {
                    val item = lock.withLock { queue.firstOrNull() } ?: break
                    var ok = false
                    for (attempt in 0..MAX_RETRIES) {
                        ok = try {
                            // A send that never reports back counts as a failed attempt
                            withTimeoutOrNull(sendTimeoutMs) { transport.send(item.data) } ?: false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        if (ok) break
                        if (attempt < MAX_RETRIES) delay(retryDelayMs)
                    }
                    lock.withLock { if (queue.firstOrNull() === item) queue.removeFirst() }
                    item.done.complete(ok)
                }
            }
        }
    }

    /** Queue a message. The returned deferred completes with the delivery result. */
    suspend fun enqueue(data: PebbleDictionary): CompletableDeferred<Boolean> {
        val item = Item(data, CompletableDeferred())
        lock.withLock { queue.addLast(item) }
        wake.trySend(Unit)
        return item.done
    }

    /** Queue and wait until delivered (or dropped after retries). */
    suspend fun sendAndWait(data: PebbleDictionary): Boolean = enqueue(data).await()

    /** Drop queued messages that are not in flight (a new question replaces the old answer). */
    suspend fun clearPending() = lock.withLock {
        if (queue.size > 1) {
            val inFlight = queue.first()
            queue.drop(1).forEach { it.done.complete(false) }
            queue.clear()
            queue.addLast(inFlight)
        }
    }
}
