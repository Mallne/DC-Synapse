package cloud.mallne.dicentra.synapse.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalystFanoutTest {
    @Test
    fun `fanout is bounded preserves target order and flattens arrays`(): Unit = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val fanout = CatalystFanout(4, 2, 1_000, 2_000)

        val result = fanout.execute(listOf(1, 2, 3, 4)) { target ->
            val now = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            try {
                delay((5 - target) * 10L)
                if (target == 2) JsonArray(listOf(JsonPrimitive("2a"), JsonPrimitive("2b")))
                else JsonPrimitive(target.toString())
            } finally {
                active.decrementAndGet()
            }
        }

        assertTrue(peak.get() <= 2)
        assertEquals(
            listOf("1", "2a", "2b", "3", "4"),
            result.map { it.toString().removeSurrounding("\"") },
        )
    }

    @Test
    fun `selected target count is capped before invoking providers`(): Unit = runBlocking {
        val fanout = CatalystFanout(1, 1, 1_000, 2_000)
        var invoked = false

        assertFailsWith<CatalystTargetLimitException> {
            fanout.execute(listOf(1, 2)) {
                invoked = true
                JsonPrimitive(it)
            }
        }
        assertEquals(false, invoked)
    }

    @Test
    fun `aggregate serialized bytes are capped`(): Unit = runBlocking {
        val fanout = CatalystFanout(2, 2, 1_000, 2_000, maxAggregateResponseBytes = 5)

        assertFailsWith<CatalystAggregateLimitException> {
            fanout.execute(listOf(1, 2)) { JsonPrimitive("value-$it") }
        }
    }

    @Test
    fun `single target deadline is reported as timeout`(): Unit = runBlocking {
        val fanout = CatalystFanout(1, 1, 20, 1_000)

        assertFailsWith<CatalystExecutionTimeoutException> {
            fanout.execute(listOf(Unit)) {
                delay(1_000)
                JsonPrimitive("unreachable")
            }
        }
    }

    @Test
    fun `overall deadline cancels remaining fanout`(): Unit = runBlocking {
        val fanout = CatalystFanout(3, 1, 2_000, 50)
        val cancelled = AtomicInteger()

        assertFailsWith<CatalystExecutionTimeoutException> {
            fanout.execute(listOf(1, 2, 3)) {
                try {
                    delay(2_000)
                    JsonPrimitive(it.toString())
                } finally {
                    cancelled.incrementAndGet()
                }
            }
        }
        assertTrue(cancelled.get() >= 1)
    }

    @Test
    fun `all provider failures are reported as upstream failure`(): Unit = runBlocking {
        val fanout = CatalystFanout(2, 2, 1_000, 2_000)

        assertFailsWith<CatalystUpstreamFailureException> {
            fanout.execute(listOf(1, 2)) { error("provider failed $it") }
        }
    }

    @Test
    fun `partial failures return no apparent success and retain completed values for diagnostics`(): Unit = runBlocking {
        val fanout = CatalystFanout(2, 2, 1_000, 2_000)

        val failure = assertFailsWith<CatalystPartialExecutionException> {
            fanout.execute(listOf("ok", "failed")) {
                if (it == "failed") error("provider failed")
                JsonPrimitive("completed")
            }
        }

        assertEquals(listOf(JsonPrimitive("completed")), failure.completedResults)
    }

    @Test
    fun `caller cancellation propagates to active targets`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val fanout = CatalystFanout(1, 1, 10_000, 20_000)
        val execution = async {
            fanout.execute(listOf(Unit)) {
                entered.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                    JsonPrimitive("unreachable")
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }

        entered.await()
        execution.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
    }
}
