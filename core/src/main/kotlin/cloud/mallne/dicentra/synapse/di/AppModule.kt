package cloud.mallne.dicentra.synapse.di

import cloud.mallne.dicentra.synapse.service.APIDBService
import cloud.mallne.dicentra.synapse.service.CatalystGenerator
import cloud.mallne.dicentra.synapse.service.CatalystExecutor
import cloud.mallne.dicentra.synapse.service.CatalystActionResolver
import cloud.mallne.dicentra.synapse.service.DatabaseService
import cloud.mallne.dicentra.synapse.service.DiscoveryGenerator
import cloud.mallne.dicentra.synapse.service.ScopeService
import cloud.mallne.dicentra.synapse.model.SynapseConfig
import cloud.mallne.dicentra.synapse.service.CatalystPluginContribution
import cloud.mallne.dicentra.synapse.service.JsonAdapterCatalystContribution
import cloud.mallne.dicentra.synapse.service.XmlAdapterCatalystContribution
import cloud.mallne.dicentra.synapse.service.SynapsePluginBundle
import cloud.mallne.dicentra.synapse.service.WorkflowDBService
import cloud.mallne.dicentra.aviator.core.plugins.aviatorPluginRegistry
import cloud.mallne.dicentra.aviator.orchestration.plugin.installOrchestrationPlugins
import cloud.mallne.dicentra.aviator.plugin.adapter.json.JsonAdapter
import cloud.mallne.dicentra.aviator.plugin.adapter.xml.XmlAdapter
import cloud.mallne.dicentra.synapse.service.CatalystActionFilter
import cloud.mallne.dicentra.synapse.statics.Serialization
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.core.qualifier.named

@Module
@ComponentScan(
    "cloud.mallne.dicentra.synapse.di",
    "cloud.mallne.dicentra.synapse.service"
)
class AppModule

val DI = module {
    singleOf(::APIDBService)
    singleOf(::CatalystActionResolver)
    singleOf(::CatalystGenerator)
    factoryOf(::DatabaseService)
    singleOf(::DiscoveryGenerator)
    singleOf(::ScopeService)
    singleOf(::WorkflowDBService)
    single { Serialization() }
    single<CatalystPluginContribution>(named(JsonAdapter.identity)) { JsonAdapterCatalystContribution() }
    single<CatalystPluginContribution>(named(XmlAdapter.identity)) { XmlAdapterCatalystContribution() }
    single {
        val limits = get<SynapseConfig>().catalyst
        val bundles = getAll<SynapsePluginBundle>()
        CatalystExecutor(
            contributions = getAll<CatalystPluginContribution>() + bundles.flatMap { it.catalystPlugins },
            filters = getAll<CatalystActionFilter>() + bundles.flatMap { it.actionFilters },
            enginePlugins = aviatorPluginRegistry { bundles.forEach { it.configureEngine(this) } },
            orchestrationPlugins = installOrchestrationPlugins { bundles.forEach { it.configureOrchestration(this) } },
            resolveWorkflowSource = { source, services ->
                bundles.mapNotNull { it.resolveWorkflowSource(source, services) }.distinct().singleOrNull()
            },
            workflowParameterAliases = { action ->
                val aliases = bundles.flatMap { it.workflowParameterAliases(action).entries }
                require(aliases.map { it.key }.distinct().size == aliases.size) { "Duplicate workflow parameter alias" }
                aliases.associate { it.key to it.value }
            },
            maxTargets = limits.maxTargets,
            maxConcurrentTargets = limits.maxConcurrentTargets,
            perTargetTimeoutMillis = limits.perTargetTimeoutMillis,
            overallTimeoutMillis = limits.overallTimeoutMillis,
            maxResponseBytes = limits.maxResponseBytes,
            maxAggregateResponseBytes = limits.maxAggregateResponseBytes,
            allowedHosts = limits.allowedHosts.split(',').map(String::trim).filter(String::isNotEmpty),
            allowPublicServiceHosts = limits.allowPublicServiceHosts,
        )
    }
}
