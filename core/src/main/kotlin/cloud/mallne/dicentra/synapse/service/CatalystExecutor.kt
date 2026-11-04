package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.client.ktor.KtorAviatorServiceConverter
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-pluginMaterialization`
import cloud.mallne.dicentra.aviator.core.NoBody
import cloud.mallne.dicentra.aviator.core.execution.AviatorExecutionContext
import cloud.mallne.dicentra.aviator.core.execution.RequestParameters
import cloud.mallne.dicentra.aviator.core.io.RawRequestBody
import cloud.mallne.dicentra.aviator.core.plugins.AviatorPluginInstance
import cloud.mallne.dicentra.aviator.core.plugins.BasicPluginActivationScope
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystParameterCodecs
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystRequest
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystResultEncoder
import cloud.mallne.dicentra.aviator.plugin.synapse.toRequestParameters
import io.ktor.client.*
import io.ktor.client.engine.apache5.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.net.InetAddress
import kotlin.time.Duration.Companion.milliseconds

/** Executes selected authorized original Aviator actions outside the database transaction. */
class CatalystExecutor(
    private val contributions: List<CatalystPluginContribution>,
    private val filters: List<CatalystActionFilter> = emptyList(),
    private val json: Json = Json,
    client: HttpClient? = null,
    private val maxTargets: Int = 32,
    maxConcurrentTargets: Int = 4,
    private val perTargetTimeoutMillis: Long = 15_000,
    private val overallTimeoutMillis: Long = 30_000,
    private val maxResponseBytes: Long = 16_777_216,
    private val maxAggregateResponseBytes: Long = 33_554_432,
    allowedHosts: List<String> = emptyList(),
    private val allowPublicServiceHosts: Boolean = false,
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
        val selectionParameters = try {
            request.toRequestParameters(json, codecRegistry)
        } catch (cause: IllegalArgumentException) {
            throw CatalystInvalidRequestException(cause)
        }
        val validatedSelectionParameters = try {
            CatalystInputBinder.validateSelectionInputs(
                request = request,
                routes = actions.map { it.route },
                parameters = selectionParameters,
                names = if (request.identities.isEmpty()) {
                    filters.flatMapTo(mutableSetOf()) { it.selectionInputs }
                } else emptySet(),
                json = json,
                codecs = codecRegistry,
            )
        } catch (cause: IllegalArgumentException) {
            throw CatalystInvalidRequestException(cause)
        }
        val selection = selectActionPlan(actions, request, validatedSelectionParameters)
        if (selection.actions.isEmpty()) throw CatalystActionNotFoundException()

        if (selection.orderedFallback) {
            return executeFallback(selection.actions, request, codecRegistry)
        }

        val selected = selection.actions
        val pluginContractsByAction = selected.map(::parameterContractsFor)
        val pluginContracts = pluginContractsByAction.firstOrNull().orEmpty()
        require(pluginContractsByAction.all { contracts ->
            contracts.keys == pluginContracts.keys && contracts.all { (name, contract) ->
                pluginContracts[name]?.let { it.codec == contract.codec && it.cardinality == contract.cardinality } == true
            }
        }) { throw CatalystInvalidRequestException(IllegalArgumentException("Selected actions do not share a plugin input contract")) }

        val prepared = selected.map { action -> action to prepareAction(action, request, codecRegistry) }
        return fanout.execute(selected.sortedBy { it.service.id }) { action ->
            val target = prepared.first { it.first == action }.second
            executeOne(action, request, target.pluginInstances, target.parameters, target.serializedBody)
        }
    }

    private data class PreparedCatalystAction(
        val pluginInstances: List<AviatorPluginInstance>,
        val parameters: RequestParameters,
        val serializedBody: RawRequestBody?,
    )

    private data class CatalystActionSelection(
        val actions: List<ResolvedCatalystAction>,
        val orderedFallback: Boolean = false,
    )

    private suspend fun executeFallback(
        actions: List<ResolvedCatalystAction>,
        request: CatalystRequest,
        codecs: CatalystParameterCodecs,
    ): JsonElement {
        if (actions.size > maxTargets) throw CatalystTargetLimitException()
        var lastFailure: Exception? = null
        try {
            return withTimeout(overallTimeoutMillis.milliseconds) {
                for (action in actions) {
                    val prepared = prepareAction(action, request, codecs)
                    try {
                        val result = withTimeout(perTargetTimeoutMillis.milliseconds) {
                            executeOne(action, request, prepared.pluginInstances, prepared.parameters, prepared.serializedBody)
                        }
                        val flattened = if (result is kotlinx.serialization.json.JsonArray) result else listOf(result)
                        val aggregate = kotlinx.serialization.json.JsonArray(flattened)
                        if (aggregate.toString().encodeToByteArray().size.toLong() > maxAggregateResponseBytes) {
                            throw CatalystAggregateLimitException()
                        }
                        return@withTimeout aggregate
                    } catch (cancelled: CancellationException) {
                        if (cancelled is TimeoutCancellationException && currentCoroutineContext().isActive) {
                            lastFailure = cancelled
                            continue
                        }
                        throw cancelled
                    } catch (failure: CatalystInvalidRequestException) {
                        throw failure
                    } catch (failure: CatalystOutboundPolicyException) {
                        throw failure
                    } catch (failure: MissingCatalystPluginException) {
                        throw failure
                    } catch (failure: CatalystAggregateLimitException) {
                        throw failure
                    } catch (failure: CatalystTargetLimitException) {
                        throw failure
                    } catch (failure: Exception) {
                        lastFailure = failure
                    }
                }
                throw CatalystUpstreamFailureException(lastFailure)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw CatalystExecutionTimeoutException(timeout)
        }
    }

    private fun prepareAction(
        action: ResolvedCatalystAction,
        request: CatalystRequest,
        codecs: CatalystParameterCodecs,
    ): PreparedCatalystAction = try {
        val serializedBody = CatalystInputBinder.serializedBody(request, action.route)
        if (serializedBody == null && !(request.body == null && requestBodyIsConstructedByPlugin(action))) {
            CatalystInputBinder.validateBody(request.body, action.route, json)
        }
        val pluginInstances = materializePlugins(action)
        val parameters = CatalystInputBinder.bind(
            request,
            action.route,
            json,
            codecs,
            parameterContractsFor(action),
        )
        PreparedCatalystAction(pluginInstances, parameters, serializedBody)
    } catch (cause: IllegalArgumentException) {
        throw CatalystInvalidRequestException(cause)
    }

    private fun materializePlugins(action: ResolvedCatalystAction): List<AviatorPluginInstance> {
        val materialized = action.route.`x-dicentra-aviator-pluginMaterialization`.orEmpty()
        val identities = materialized.keys + contributions.filter { it.activateByDefault }.map { it.identity }
        return identities.distinct().map { identity ->
            val contribution = providersByIdentity[identity]?.singleOrNull()
                ?: throw MissingCatalystPluginException()
            val instance = contribution.create()
            val configured = materialized[identity]?.let(instance::requestReconfigure) ?: instance
            require(configured.identity == identity) { "Catalyst provider identity mismatch" }
            configured
        }
    }

    private fun requestBodyIsConstructedByPlugin(action: ResolvedCatalystAction): Boolean {
        val materializedIdentities = action.route.`x-dicentra-aviator-pluginMaterialization`.orEmpty().keys
        return contributions.any { it.identity in materializedIdentities && it.handlesRequestBody }
    }

    private fun parameterContractsFor(action: ResolvedCatalystAction): Map<String, CatalystParameterContract> {
        val identities = action.route.`x-dicentra-aviator-pluginMaterialization`.orEmpty().keys +
            contributions.filter { it.activateByDefault }.map { it.identity }
        val contracts = identities.flatMap { identity ->
            providersByIdentity[identity]?.singleOrNull()?.parameterContracts.orEmpty()
        }
        require(contracts.map { it.name }.distinct().size == contracts.size) {
            "Catalyst provider contributions declare duplicate input names"
        }
        return contracts.associateBy { it.name }
    }

    internal fun selectActions(
        actions: List<ResolvedCatalystAction>,
        request: CatalystRequest,
        parameters: RequestParameters,
    ): List<ResolvedCatalystAction> = selectActionPlan(actions, request, parameters).actions

    private fun selectActionPlan(
        actions: List<ResolvedCatalystAction>,
        request: CatalystRequest,
        parameters: RequestParameters,
    ): CatalystActionSelection {
        if (request.identities.isNotEmpty()) {
            if (request.identities.distinct().size != request.identities.size) {
                throw CatalystInvalidRequestException(IllegalArgumentException("Catalyst identities must not contain duplicates"))
            }
            val requested = request.identities.toSet()
            val matching = actions.filter { it.service.id in requested }
            if (matching.map { it.service.id }.toSet() != requested) throw CatalystActionNotFoundException()
            return CatalystActionSelection(matching)
        }
        for (filter in filters.sortedWith(compareBy<CatalystActionFilter> { it.priority }.thenBy { it.identity })) {
            val selection = filter.select(actions, request, parameters)
            if (selection != null) {
                val available = actions.toSet()
                if (selection.any { it !in available } || selection.map { it.service.id }.distinct().size != selection.size) {
                    throw CatalystInvalidFilterSelectionException()
                }
                return CatalystActionSelection(selection, filter is CatalystFallbackActionFilter)
            }
        }
        return CatalystActionSelection(actions)
    }

    private suspend fun executeOne(
        action: ResolvedCatalystAction,
        request: CatalystRequest,
        pluginInstances: List<AviatorPluginInstance>,
        parameters: RequestParameters,
        serializedBody: RawRequestBody?,
    ): JsonElement {
        try {
            outboundPolicy.validate(
                action.service.serviceDefinition.servers.orEmpty().map { it.url },
                allowServiceDefinitionHosts = allowPublicServiceHosts && action.service.scope == null,
            )
        } catch (cause: IllegalArgumentException) {
            throw CatalystOutboundPolicyException(cause)
        }
        val materializedIdentities = action.route.`x-dicentra-aviator-pluginMaterialization`.orEmpty().keys
        val activation = BasicPluginActivationScope().apply { pluginInstances.forEach(::add) }
        val services = KtorAviatorServiceConverter(client, json, maxResponseBytes)
            .build(action.service.serviceDefinition) { activation.registry.forEach(::add) }
        val service = services.singleOrNull { it.serviceLocator == action.locator && it.route.method == action.route.method && it.route.path == action.route.path }
            ?: throw IllegalArgumentException("Resolved action could not be constructed")
        val context = if (serializedBody != null) {
            service.requestContextful<JsonElement, RawRequestBody>(requestBody = serializedBody, requestParams = parameters)
        } else if (request.body == null) {
            service.requestContextful<JsonElement, NoBody>(requestParams = parameters)
        } else {
            service.requestContextful<JsonElement, JsonElement>(requestBody = request.body, requestParams = parameters)
        }
        val value = contextAsAny(context).result ?: throw CatalystUpstreamFailureException()
        return encodeFinalResult(value, materializedIdentities)
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
