package cloud.mallne.dicentra.synapse.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Bounded, deadline-aware fanout with deterministic result ordering and all-or-error responses. */
internal class CatalystFanout(
    private val maxTargets: Int,
    private val maxConcurrentTargets: Int,
    private val perTargetTimeoutMillis: Long,
    private val overallTimeoutMillis: Long,
    private val maxAggregateResponseBytes: Long = 33_554_432,
) {
    init {
        require(maxTargets > 0) { "Catalyst maxTargets must be positive" }
        require(maxConcurrentTargets > 0) { "Catalyst maxConcurrentTargets must be positive" }
        require(perTargetTimeoutMillis > 0) { "Catalyst perTargetTimeoutMillis must be positive" }
        require(overallTimeoutMillis > 0) { "Catalyst overallTimeoutMillis must be positive" }
        require(maxAggregateResponseBytes > 0) { "Catalyst maxAggregateResponseBytes must be positive" }
    }

    suspend fun <T> execute(
        targets: List<T>,
        executeTarget: suspend (T) -> JsonElement,
    ): JsonArray {
        if (targets.size > maxTargets) throw CatalystTargetLimitException()

        val outcomes = try {
            withTimeout(overallTimeoutMillis) {
                supervisorScope {
                    val permits = Semaphore(maxConcurrentTargets)
                    targets.map { target ->
                        async {
                            permits.withPermit {
                                try {
                                    TargetOutcome.Success(withTimeout(perTargetTimeoutMillis) {
                                        executeTarget(target)
                                    })
                                } catch (timeout: TimeoutCancellationException) {
                                    TargetOutcome.Failure(timeout)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    TargetOutcome.Failure(error)
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            // A timeout from this fanout is translated; cancellation inherited from the caller is preserved.
            if (currentCoroutineContext().isActive) throw CatalystExecutionTimeoutException(timeout)
            throw timeout
        }

        val failures = outcomes.filterIsInstance<TargetOutcome.Failure>()
        val completedResults = outcomes.filterIsInstance<TargetOutcome.Success>()
            .flatMap { outcome ->
                when (val result = outcome.value) {
                    is JsonArray -> result
                    else -> listOf(result)
                }
            }
        val aggregatedResult = JsonArray(completedResults)
        val aggregateBytes = aggregatedResult.toString().encodeToByteArray().size.toLong()
        if (aggregateBytes > maxAggregateResponseBytes) throw CatalystAggregateLimitException()
        if (failures.isNotEmpty()) {
            val cause = failures.first().cause
            if (completedResults.isEmpty() && failures.all { it.cause is MissingCatalystPluginException }) {
                throw MissingCatalystPluginException()
            }
            if (completedResults.isEmpty() && failures.all { it.cause is TimeoutCancellationException }) {
                throw CatalystExecutionTimeoutException(cause)
            }
            if (completedResults.isEmpty()) throw CatalystUpstreamFailureException(cause)
            throw CatalystPartialExecutionException(cause, completedResults)
        }
        return aggregatedResult
    }

    private sealed interface TargetOutcome {
        data class Success(val value: JsonElement) : TargetOutcome
        data class Failure(val cause: Exception) : TargetOutcome
    }
}
