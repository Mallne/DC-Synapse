package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.client.ktor.KtorAviatorServiceConverter
import cloud.mallne.dicentra.aviator.client.ktor.KtorClientDefinition
import cloud.mallne.dicentra.aviator.client.ktor.KtorEngineDefinitionConverter
import cloud.mallne.dicentra.aviator.client.ktor.ktorEngineTemplateWithBody
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-pluginMaterialization`
import cloud.mallne.dicentra.aviator.core.NoBody
import cloud.mallne.dicentra.aviator.core.engine.*
import cloud.mallne.dicentra.aviator.core.execution.AviatorExecutionContext
import cloud.mallne.dicentra.aviator.core.execution.RequestParameters
import cloud.mallne.dicentra.aviator.core.io.RawRequestBody
import cloud.mallne.dicentra.aviator.core.plugins.AviatorPluginInstance
import cloud.mallne.dicentra.aviator.core.plugins.BasicPluginActivationScope
import cloud.mallne.dicentra.aviator.core.plugins.AviatorPluginRegistry
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystParameterCodecs
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystRequest
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystResultEncoder
import cloud.mallne.dicentra.aviator.plugin.synapse.toRequestParameters
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystParameterValue
import cloud.mallne.dicentra.aviator.orchestration.arazzo.execution.*
import cloud.mallne.dicentra.aviator.orchestration.arazzo.validation.ArazzoValidationResult
import cloud.mallne.dicentra.aviator.orchestration.arazzo.validation.ArazzoValidator
import cloud.mallne.dicentra.aviator.orchestration.model.*
import cloud.mallne.dicentra.aviator.orchestration.policy.*
import cloud.mallne.dicentra.aviator.orchestration.plugin.OrchestrationPluginRegistry
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import io.ktor.client.*
import io.ktor.client.engine.apache5.*
import io.ktor.http.ContentType
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
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
    enginePlugins: AviatorPluginRegistry = AviatorPluginRegistry.Empty,
    private val orchestrationPlugins: OrchestrationPluginRegistry = OrchestrationPluginRegistry.Empty,
    private val resolveWorkflowSource: (String, List<APIServiceDTO>) -> String? = { _, _ -> null },
    private val workflowParameterAliases: (ResolvedCatalystAction) -> Map<String, String> = { emptyMap() },
) : AutoCloseable {
    private val providersByIdentity = contributions.groupBy { it.identity }
    private val outboundPolicy = CatalystOutboundPolicy(allowedHosts, resolveProviderHost)
    private val client = client ?: HttpClient(Apache5) {
        followRedirects = false
        engine {
            dnsResolver = CatalystDnsResolver(outboundPolicy)
        }
    }
    private val converter = KtorAviatorServiceConverter(this.client, json, maxResponseBytes)
    private val definitionConverter = KtorEngineDefinitionConverter(converter)
    private class InvocationDefinition(
        val definition: KtorClientDefinition,
        val allowPublicHosts: Boolean,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<InvocationDefinition>
    }
    private val engine = AviatorEngine(
        coordinator = { locator ->
            currentCoroutineContext()[InvocationDefinition]?.definition
                ?.takeIf { it.locator == locator }?.let(::listOf).orEmpty()
        },
        authorization = { context ->
            if (currentCoroutineContext()[InvocationDefinition]?.definition === context.definition) {
                EngineAuthorizationDecision.Allowed
            } else {
                EngineAuthorizationDecision.Denied("Action was not authorized by Catalyst")
            }
        },
        outboundTargetPolicy = { _, definition, targets ->
            val invocation = currentCoroutineContext()[InvocationDefinition]
            if (invocation == null || invocation.definition !== definition) {
                "Action was not authorized by Catalyst"
            } else {
                try {
                    outboundPolicy.validate(targets, invocation.allowPublicHosts)
                    null
                } catch (cause: IllegalArgumentException) {
                    throw CatalystOutboundPolicyException(cause)
                }
            }
        },
        plugins = enginePlugins,
    )
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

    /**
     * Execute a stored document against a caller-visible service snapshot. Source identities and
     * all operation references are resolved before any step runs; no source URL is fetched.
     */
    suspend fun executeWorkflow(
        stored: StoredWorkflow,
        workflowId: String,
        visibleServices: List<APIServiceDTO>,
        inputs: JsonElement,
    ): ExecutionResult {
        val validation = ArazzoValidator.parseAndValidate(stored.document.toString())
        require(validation is ArazzoValidationResult.Valid) { "Stored workflow is not executable" }
        val document = validation.document
        val workflow = requireNotNull(document.workflows.singleOrNull { it.workflowId == workflowId }) {
            "Workflow not found"
        }
        require(workflow.steps.size <= maxTargets) { "Workflow exceeds configured step limit" }
        val selected = WorkflowOperationIndex(document, visibleServices, resolveWorkflowSource).preflight(workflow)
        val allowed = selected.values.map { it.first }.toSet()
        var aggregateBytes = 0L
        var aggregateLimitExceeded = false
        val policy = CapabilityPolicy(
            ExecutionStrategy.AGGREGATE, ConstituentOrdering.RESOLUTION,
            PartialFailurePolicy.REQUIRE_ALL, EmptySelectionPolicy.FAIL,
        )
        val steps = ArazzoEngineStepExecutor(engine, policy) { step, mapped ->
            val (identity, action) = selected.getValue(step.stepId)
            val (parameters, body) = workflowParameters(
                hasParameters = step.parameters.isNotEmpty(),
                hasRequestBody = step.requestBody != null,
                mapped = mapped,
                action = action,
            )
            val activation = BasicPluginActivationScope().apply { materializePlugins(action).forEach(::add) }
            val definition = definitionConverter.convert(action.service.serviceDefinition) {
                activation.registry.forEach(::add)
            }.single { it.locator == action.locator && it.service.route.method == action.route.method && it.service.route.path == action.route.path }
            ArazzoEngineActionResolution.Resolved(ArazzoEngineAction(action.locator, identity) { sharedEngine ->
                withContext(InvocationDefinition(definition, allowPublicServiceHosts && action.service.scope == null)) {
                    val template = ktorEngineTemplateWithBody<JsonElement, JsonElement>()
                    val responseTemplate = object : EngineTemplate<OpenApiClientInput<JsonElement>, cloud.mallne.dicentra.aviator.client.ktor.KtorExecutionContext<JsonElement, JsonElement>> by template {
                        override fun classifyFailure(output: cloud.mallne.dicentra.aviator.client.ktor.KtorExecutionContext<JsonElement, JsonElement>): Exception? = null
                    }
                    val execution = withTimeout(perTargetTimeoutMillis.milliseconds) {
                        sharedEngine.invoke(action.locator, responseTemplate, OpenApiClientInput(body = body, parameters = parameters))
                            .single().result.getOrThrow()
                    }
                    val response = execution.networkChain.lastOrNull { it.response != null }?.response
                        ?: return@withContext null
                    val sourceBody = response.content?.let { bytes ->
                        when {
                            bytes.isEmpty() -> BodyValue.Empty
                            response.contentType.withoutParameters().match(ContentType.Application.Json) ->
                                try {
                                    BodyValue.JsonValue(json.parseToJsonElement(bytes.decodeToString()))
                                } catch (_: IllegalArgumentException) {
                                    BodyValue.DecodeFailure(ExecutionFailure("invalid_json", "Provider response is not valid JSON"))
                                }
                            else -> BodyValue.BytesValue(bytes.toList())
                        }
                    } ?: BodyValue.Unavailable
                    val result = contextAsAny(execution).result?.let {
                        BodyValue.JsonValue(encodeFinalResult(it, action.route.`x-dicentra-aviator-pluginMaterialization`.orEmpty().keys))
                    } ?: sourceBody
                    val resultBytes = when (result) {
                        is BodyValue.JsonValue -> result.value.toString().encodeToByteArray().size.toLong()
                        is BodyValue.BytesValue -> result.bytes.size.toLong()
                        else -> 0L
                    }
                    aggregateBytes += maxOf(response.content?.size?.toLong() ?: 0L, resultBytes)
                    if (aggregateBytes > maxAggregateResponseBytes) {
                        aggregateLimitExceeded = true
                        throw CatalystAggregateLimitException()
                    }
                    CapabilityResult(action.locator, CapabilityOutcome.Succeeded(result), listOf(
                        ConstituentResult(identity, ConstituentOutcome.Succeeded(result),
                            ResponseSnapshot(sourceBody, response.contentType.toString(),
                                HttpResponseMetadata(response.status.value, response.headers.values.toStringList()))),
                    ))
                }
            })
        }
        val runner = ArazzoRunExecutor(steps, plugins = orchestrationPlugins, authorize = { identity ->
            if (identity in allowed) null else ExecutionFailure("unauthorized", "Operation is not available")
        })
        val revision = MessageDigest.getInstance("SHA-256").digest(stored.document.toString().encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        val result = withTimeout(overallTimeoutMillis.milliseconds) {
            runner.execute(workflow, WorkflowIdentity("arazzo", document.arazzo, stored.id, revision),
                RunId(UUID.randomUUID().toString()), inputs.toString())
        }
        if (aggregateLimitExceeded ||
            json.encodeToString(ExecutionResult.serializer(), result).encodeToByteArray().size.toLong() > maxAggregateResponseBytes
        ) throw CatalystAggregateLimitException()
        return result
    }

    private fun workflowParameters(
        hasParameters: Boolean,
        hasRequestBody: Boolean,
        mapped: JsonElement,
        action: ResolvedCatalystAction,
    ): Pair<RequestParameters, JsonElement?> {
        val groups = if (hasParameters) mapped.jsonObject["parameters"]?.jsonObject.orEmpty() else emptyMap()
        val body = if (hasRequestBody) mapped.jsonObject["requestBody"] else null
        CatalystInputBinder.validateBody(body, action.route, json)
        val values = mutableMapOf<String, CatalystParameterValue>()
        val aliases = workflowParameterAliases(action)
        val usedAliases = mutableMapOf<String, String>()
        groups.forEach { (location, group) ->
            (group as JsonObject).forEach { (name, value) ->
                val target = if (location.equals("query", ignoreCase = true)) aliases[name] ?: name else name
                val declaration = action.route.input.singleOrNull {
                    it.name == target && it.input.name.equals(location, ignoreCase = true)
                } ?: throw IllegalArgumentException("Workflow parameter does not match the operation")
                require(target !in values) { "Ambiguous workflow parameter name" }
                val items = if (declaration.type is cloud.mallne.dicentra.aviator.koas.typed.Model.Collection.List) {
                    (value as? JsonArray)?.toList() ?: throw IllegalArgumentException("Expected array parameter")
                } else listOf(value)
                values[target] = CatalystParameterValue(
                    if (declaration.type is cloud.mallne.dicentra.aviator.koas.typed.Model.Collection.List)
                        CatalystParameterValue.Cardinality.MULTI else CatalystParameterValue.Cardinality.SINGLE,
                    items,
                )
                if (target != name) usedAliases[name] = target
            }
        }
        val parameters = CatalystInputBinder.bind(CatalystRequest(typedParameters = values), action.route, json,
            CatalystParameterCodecs(contributions.flatMap { it.parameterCodecs }), parameterContractsFor(action))
        // Weaver consumes the logical names; the aliased values were validated against the
        // provider's declared schema and are retained until its projection replaces the route.
        usedAliases.forEach { (name, target) -> parameters[name] = parameters.getValue(target) }
        return parameters to body
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
        val materializedIdentities = action.route.`x-dicentra-aviator-pluginMaterialization`.orEmpty().keys
        val activation = BasicPluginActivationScope().apply { pluginInstances.forEach(::add) }
        val definitions = definitionConverter.convert(action.service.serviceDefinition) { activation.registry.forEach(::add) }
        val definition = definitions.singleOrNull {
            it.locator == action.locator && it.service.route.method == action.route.method && it.service.route.path == action.route.path
        }
            ?: throw IllegalArgumentException("Resolved action could not be constructed")
        val context = withContext(InvocationDefinition(definition, allowPublicServiceHosts && action.service.scope == null)) {
            if (serializedBody != null) {
                engine.invoke(action.locator, ktorEngineTemplateWithBody<JsonElement, RawRequestBody>(), OpenApiClientInput(body = serializedBody, parameters = parameters)).single().result.getOrThrow()
            } else if (request.body == null) {
                engine.invoke(action.locator, ktorEngineTemplateWithBody<JsonElement, NoBody>(), OpenApiClientInput(parameters = parameters)).single().result.getOrThrow()
            } else {
                engine.invoke(action.locator, ktorEngineTemplateWithBody<JsonElement, JsonElement>(), OpenApiClientInput(body = request.body, parameters = parameters)).single().result.getOrThrow()
            }
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

    override fun close() {
        converter.httpClient.close()
        client.close()
    }
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
