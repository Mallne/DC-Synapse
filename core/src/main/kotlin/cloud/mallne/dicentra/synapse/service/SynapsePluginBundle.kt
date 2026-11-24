package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.plugins.AviatorPluginRegistryBuilder
import cloud.mallne.dicentra.aviator.orchestration.plugin.OrchestrationPluginInstallationScope
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO

/**
 * Host-owned extensions installed into Synapse's shared Aviator engine and Catalyst.
 *
 * Instances and policies are supplied by trusted application code, never by an OpenAPI document.
 * Catalyst contributions retain their existing per-action configuration and codec semantics.
 */
class SynapsePluginBundle(
    val catalystPlugins: List<CatalystPluginContribution> = emptyList(),
    val actionFilters: List<CatalystActionFilter> = emptyList(),
    val configureEngine: AviatorPluginRegistryBuilder.() -> Unit = {},
    val configureOrchestration: OrchestrationPluginInstallationScope.() -> Unit = {},
    val resolveWorkflowSource: (String, List<APIServiceDTO>) -> String? = { _, _ -> null },
    /** Logical workflow query inputs mapped to provider-declared query parameters. */
    val workflowParameterAliases: (ResolvedCatalystAction) -> Map<String, String> = { emptyMap() },
)
