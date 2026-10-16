package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-pluginMaterialization`
import cloud.mallne.dicentra.aviator.model.AviatorServiceUtils
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.aviator.plugin.synapse.SynapsePlugin
import cloud.mallne.dicentra.aviator.koas.typed.Route
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import org.koin.core.annotation.Single

data class ResolvedCatalystAction(
    val service: APIServiceDTO,
    val route: Route,
    val locator: ServiceLocator,
)

/** Resolves all caller-visible original actions matching a universal Aviator locator. */
@Single
class CatalystActionResolver {
    fun resolve(
        locator: ServiceLocator,
        callerVisibleServices: Collection<APIServiceDTO>,
    ): List<ResolvedCatalystAction> {
        return callerVisibleServices.asSequence()
            .filter { it.catalystTransformable }
            .flatMap { service ->
                val matches = AviatorServiceUtils.extractServiceLocators(service.serviceDefinition)
                    .filter { (candidateLocator, route) ->
                        candidateLocator == locator && !isCatalystWrapper(route)
                    }
                // One universal locator must identify at most one original action per service.
                matches.singleOrNull()?.let { (matchedLocator, route) ->
                    sequenceOf(ResolvedCatalystAction(service, route, matchedLocator))
                } ?: emptySequence()
            }
            .distinctBy { Triple(it.service.id, it.route.method, it.route.path) }
            .toList()
    }

    private fun isCatalystWrapper(route: Route): Boolean {
        val materialization = route.`x-dicentra-aviator-pluginMaterialization`
            ?: return false
        return SynapsePlugin.identity in materialization
    }
}
