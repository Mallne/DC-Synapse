package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.model.AviatorServiceUtils
import cloud.mallne.dicentra.aviator.orchestration.arazzo.execution.ArazzoOperationResolution
import cloud.mallne.dicentra.aviator.orchestration.arazzo.execution.resolveArazzoOperation
import cloud.mallne.dicentra.aviator.orchestration.arazzo.model.ArazzoDocument
import cloud.mallne.dicentra.aviator.orchestration.arazzo.model.ArazzoWorkflow
import cloud.mallne.dicentra.aviator.orchestration.model.ConstituentIdentity
import cloud.mallne.dicentra.aviator.orchestration.policy.CapabilityPolicy
import cloud.mallne.dicentra.aviator.orchestration.policy.ConstituentOrdering
import cloud.mallne.dicentra.aviator.orchestration.policy.EmptySelectionPolicy
import cloud.mallne.dicentra.aviator.orchestration.policy.ExecutionStrategy
import cloud.mallne.dicentra.aviator.orchestration.policy.PartialFailurePolicy
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import cloud.mallne.dicentra.synapse.statics.Serialization
import io.ktor.openapi.OpenApiDoc
import java.security.MessageDigest

/**
 * Resolves workflow sources only from the caller-visible service snapshot. Source URLs are
 * registry identities, never network locations; every operation reference selects one exact action.
 */
internal class WorkflowOperationIndex(
    private val document: ArazzoDocument,
    private val services: List<APIServiceDTO>,
    private val resolveSource: (String, List<APIServiceDTO>) -> String? = { _, _ -> null },
) {
    private val policy = CapabilityPolicy(
        ExecutionStrategy.AGGREGATE, ConstituentOrdering.RESOLUTION,
        PartialFailurePolicy.REQUIRE_ALL, EmptySelectionPolicy.FAIL,
    )
    /** Resolve every step before dispatch, so a bad later reference cannot cause a partial run. */
    fun preflight(workflow: ArazzoWorkflow): Map<String, Pair<ConstituentIdentity, ResolvedCatalystAction>> {
        require(services.map { it.id }.distinct().size == services.size) { "Duplicate service identity" }
        val servicesById = services.filter { it.catalystTransformable }.associateBy { it.id }
        val prefix = "\$sourceDescriptions."
        val references = workflow.steps.mapNotNull { it.operationId }
        // An unqualified operation must consider every source to preserve ambiguity checks.
        val allSources = references.any { !it.startsWith(prefix) }
        val usedNames = references.filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('.') }.toSet()
        val sourceIds = document.sourceDescriptions.filter { allSources || it.name in usedNames }.associate { source ->
            val serviceId = if (source.url.startsWith(SERVICE_PREFIX)) {
                source.url.removePrefix(SERVICE_PREFIX)
            } else {
                requireNotNull(resolveSource(source.url, services)) { "Workflow source is unavailable" }
            }
            require(serviceId.isNotBlank() && serviceId in servicesById) { "Workflow source is unavailable" }
            source.name to serviceId
        }
        val indexed = sourceIds.values.distinct().flatMap { id ->
            val service = servicesById.getValue(id)
            val revision = sha256(Serialization().encodeToString(OpenApiDoc.serializer(), service.serviceDefinition))
            AviatorServiceUtils.extractServiceLocators(service.serviceDefinition)
                .filterNot { (_, route) -> CatalystActionResolver().isCatalystWrapper(route) }
                .map { (locator, route) ->
                val identity = ConstituentIdentity(locator, id, id, revision, route.operationId, route.path)
                identity to ResolvedCatalystAction(service, route, locator)
            }
        }
        require(indexed.map { it.first }.distinct().size == indexed.size) { "Duplicate workflow operation identity" }
        val actions = indexed.toMap()
        return workflow.steps.associate { step ->
            val resolution = resolveArazzoOperation(step, actions.keys.toList(), policy, sourceIds)
            require(resolution is ArazzoOperationResolution.Resolved) { "Workflow operation is unavailable or ambiguous" }
            val identity = requireNotNull(resolution.invocation.constituents).single()
            step.stepId to (identity to actions.getValue(identity))
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SERVICE_PREFIX = "urn:synapse:service:"
    }
}
