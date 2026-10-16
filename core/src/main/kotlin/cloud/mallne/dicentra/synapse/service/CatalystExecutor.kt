package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.client.ktor.KtorAviatorServiceConverter
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-pluginMaterialization`
import cloud.mallne.dicentra.aviator.core.NoBody
import cloud.mallne.dicentra.aviator.core.execution.AviatorExecutionContext
import cloud.mallne.dicentra.aviator.core.execution.RequestParameters
import cloud.mallne.dicentra.aviator.core.plugins.BasicPluginActivationScope
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystParameterCodecs
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystRequest
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystResultEncoder
import io.ktor.client.*
import io.ktor.client.engine.apache5.Apache5
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetAddress
import kotlinx.serialization.json.JsonElement

/** Executes selected authorized original Aviator actions outside the database transaction. */
class CatalystExecutor(
    private val contributions: List<CatalystPluginContribution>,
    private val filters: List<CatalystActionFilter> = emptyList(),
    private val json: Json = Json,
    client: HttpClient? = null,
    maxTargets: Int = 32,
    maxConcurrentTargets: Int = 4,
    perTargetTimeoutMillis: Long = 15_000,
    overallTimeoutMillis: Long = 30_000,
    private val maxResponseBytes: Long = 16_777_216,
    maxAggregateResponseBytes: Long = 33_554_432,
    allowedHosts: List<String> = emptyList(),
    resolveProviderHost: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : AutoCloseable {
    private val providersByIdentity = contributions.groupBy { it.identity }
    private val outboundPolicy = CatalystOutboundPolicy(allowedHosts, resolveProviderHost)
    private val client = client ?: HttpClient(Apache5) {
        followRedirects = false
        engine {
            dnsResolver = CatalystDnsResolver(outboundPolicy)
        }
    }
    private val fanout = CatalystFanout(
        maxTargets = maxTargets,
        maxConcurrentTargets = maxConcurrentTargets,
        perTargetTimeoutMillis = perTargetTimeoutMillis,
        overallTimeoutMillis = overallTimeoutMillis,
        maxAggregateResponseBytes = maxAggregateResponseBytes,
    )

    init {
        require(providersByIdentity.values.all { it.size == 1 }) {
            "Catalyst plugin contribution identities must be unique"
        }
        require(filters.map { it.identity }.distinct().size == filters.size) {
            "Catalyst action filter identities must be unique"
        }
    }

    suspend fun execute(actions: List<ResolvedCatalystAction>, request: CatalystRequest): JsonElement {
        require(actions.isNotEmpty()) { "At least one action is required" }
        val codecRegistry = CatalystParameterCodecs(contributions.flatMap { it.parameterCodecs })
        val parameters = try {
            // Bind against each original action schema before filters or provider invocation. The
            // locator-group candidates must expose the same public parameter contract.
            val schemas = actions.map { it.route.input }.distinct()
            require(schemas.size == 1) { "Selected actions do not share an input contract" }
            require(actions.map { it.route.body }.distinct().size == 1) {
                "Selected actions do not share a body contract"
            }
            CatalystInputBinder.validateBody(request.body, actions.first().route, json)
            CatalystInputBinder.bind(request, actions.first().route, json, codecRegistry)
        } catch (cause: IllegalArgumentException) {
            throw CatalystInvalidRequestException(cause)
        }
        val selected = selectActions(actions, request, parameters)
        if (selected.isEmpty()) throw CatalystActionNotFoundException()
        return fanout.execute(selected.sortedBy { it.service.id }) { action ->
            executeOne(action, request, parameters)
        }
    }

    internal fun selectActions(
        actions: List<ResolvedCatalystAction>,
        request: CatalystRequest,
        parameters: RequestParameters,
    ): List<ResolvedCatalystAction> {
        if (request.identities.isNotEmpty()) {
            if (request.identities.distinct().size != request.identities.size) {
                throw CatalystInvalidRequestException(IllegalArgumentException("Catalyst identities must not contain duplicates"))
            }
            val requested = request.identities.toSet()
            val matching = actions.filter { it.service.id in requested }
            if (matching.map { it.service.id }.toSet() != requested) throw CatalystActionNotFoundException()
            return matching
        }
        for (filter in filters.sortedWith(compareBy<CatalystActionFilter> { it.priority }.thenBy { it.identity })) {
            val selection = filter.select(actions, request, parameters)
            if (selection != null) {
                val available = actions.toSet()
                if (selection.any { it !in available } || selection.map { it.service.id }.distinct().size != selection.size) {
                    throw CatalystInvalidFilterSelectionException()
                }
                return selection
            }
        }
        return actions
    }

    private suspend fun executeOne(
        action: ResolvedCatalystAction,
        request: CatalystRequest,
        parameters: RequestParameters,
    ): JsonElement {
        try {
            outboundPolicy.validate(action.service.serviceDefinition.servers.orEmpty().map { it.url })
        } catch (cause: IllegalArgumentException) {
            throw CatalystOutboundPolicyException(cause)
        }
        val materialized = action.route.`x-dicentra-aviator-pluginMaterialization` ?: emptyMap()
        val selected = materialized.keys.map { identity ->
            val provider = providersByIdentity[identity]?.singleOrNull()
                ?: throw MissingCatalystPluginException()
            provider.create().also { instance ->
                require(instance.identity == identity) { "Catalyst provider identity mismatch" }
            }
        }
        val activation = BasicPluginActivationScope().apply { selected.forEach(::add) }
        val services = KtorAviatorServiceConverter(client, json, maxResponseBytes)
            .build(action.service.serviceDefinition) { activation.registry.forEach(::add) }
        val service = services.singleOrNull { it.serviceLocator == action.locator && it.route.method == action.route.method && it.route.path == action.route.path }
            ?: throw IllegalArgumentException("Resolved action could not be constructed")
        val context = if (request.body == null) {
            service.requestContextful<JsonElement, NoBody>(requestParams = parameters)
        } else {
            service.requestContextful<JsonElement, JsonElement>(requestBody = request.body, requestParams = parameters)
        }
        val value = contextAsAny(context).result ?: throw CatalystUpstreamFailureException()
        return encodeFinalResult(value, materialized.keys)
    }

    private fun encodeFinalResult(value: Any, materializedIdentities: Set<String>): JsonElement {
        val codecs = materializedIdentities
            .flatMap { providersByIdentity[it]?.singleOrNull()?.resultCodecs.orEmpty() }
        return CatalystResultEncoder.encode(value, codecs, json)
    }

    @Suppress("UNCHECKED_CAST")
    private fun contextAsAny(context: cloud.mallne.dicentra.aviator.client.ktor.KtorExecutionContext<*, *>):
            AviatorExecutionContext<@Serializable Any, @Serializable Any> =
        context as AviatorExecutionContext<@Serializable Any, @Serializable Any>

    override fun close() = client.close()
}

class MissingCatalystPluginException : RuntimeException()
class CatalystUpstreamFailureException(cause: Throwable? = null) : RuntimeException(cause)
class CatalystInvalidRequestException(cause: Throwable) : RuntimeException(cause)
class CatalystActionNotFoundException : RuntimeException()
class CatalystPartialExecutionException(cause: Throwable, val completedResults: List<JsonElement>) : RuntimeException(cause)
class CatalystTargetLimitException : RuntimeException()
class CatalystAggregateLimitException : RuntimeException()
class CatalystOutboundPolicyException(cause: Throwable) : RuntimeException(cause)
class CatalystExecutionTimeoutException(cause: Throwable? = null) : RuntimeException(cause)
class CatalystInvalidFilterSelectionException : RuntimeException()
