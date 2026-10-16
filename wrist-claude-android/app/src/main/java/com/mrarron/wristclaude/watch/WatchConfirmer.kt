package com.mrarron.wristclaude.watch

import com.mrarron.wristclaude.core.ConfirmOutcome
import com.mrarron.wristclaude.core.Confirmer
import com.mrarron.wristclaude.core.Recipients
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * CONFIRM_ID + CONFIRM_TEXT to the watch, then waits up to 60 s (after delivery) for the matching
 * CONFIRM_RESULT. A STATUS is sent before and after so the watch's 70 s answer timeout never fires.
 */
class WatchConfirmer(
    private val link: WatchLink,
    private val timeoutMs: Long = 60_000L,
) : Confirmer {
    private val nextId = AtomicInteger((System.currentTimeMillis() % 100_000).toInt() + 1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()

    override suspend fun confirm(text: String): ConfirmOutcome {
        // Never alter what the user approves: text the watch can't show unchanged is not sent at all.
        if (!Recipients.isDisplayable(text)) return ConfirmOutcome.NO_ANSWER
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<Boolean>()
        pending[id] = waiter
        try {
            link.enqueue(dict(Keys.STATUS to "Check your watch to approve."))
            val delivered = link.sendAndWait(
                dict(Keys.CONFIRM_ID to id, Keys.CONFIRM_TEXT to text)
            )
            if (!delivered) return ConfirmOutcome.NO_ANSWER
            val r = withTimeoutOrNull(timeoutMs) { waiter.await() }
            link.enqueue(dict(Keys.STATUS to "Working..."))
            return when (r) {
                true -> ConfirmOutcome.APPROVED
                false -> ConfirmOutcome.DECLINED
                null -> ConfirmOutcome.NO_ANSWER
            }
        } finally {
            pending.remove(id)
        }
    }

    /** Called for a CONFIRM_RESULT from the watch. Unknown/stale ids are ignored. */
    fun onResult(id: Int, approved: Boolean) {
        pending[id]?.complete(approved)
    }
}
