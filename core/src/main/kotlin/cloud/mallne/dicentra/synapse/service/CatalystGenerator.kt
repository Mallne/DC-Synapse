package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-pluginMaterialization`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceOptions`
import cloud.mallne.dicentra.aviator.core.InflatedServiceOptions
import cloud.mallne.dicentra.aviator.koas.typed.Route
import cloud.mallne.dicentra.aviator.model.AviatorServiceUtils
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.aviator.plugin.synapse.SynapsePlugin
import cloud.mallne.dicentra.aviator.plugin.synapse.SynapsePluginConfig
import cloud.mallne.dicentra.aviator.plugin.weaver.WeaverServiceObject
import cloud.mallne.dicentra.synapse.model.Configuration
import cloud.mallne.dicentra.synapse.statics.Serialization
import io.ktor.http.*
import io.ktor.openapi.*
import io.ktor.openapi.ReferenceOr.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class CatalystGenerator(
    @Provided val configuration: Configuration,
    val json: Json = Serialization(),
) {
    fun generateFor(
        route: Route,
        useId: String? = null
    ): Pair<String, PathItem> {
        val rawOptions = route.`x-dicentra-aviator-serviceOptions`
        val rawLocator = route.`x-dicentra-aviator-serviceDelegateCall`
        val locator =
            rawLocator?.let { ServiceLocator(it) } ?: throw IllegalArgumentException("Service Locator is required")
        //filter out potential weaver translation schema
        val options = if (rawOptions != null) {
            try {
                AviatorServiceUtils.optionBundle<WeaverServiceObject>(rawOptions)
                GenericElement(rawOptions.entries().filterNot { it.first == "x-dicentra-weaver-schema" })
            } catch (_: IllegalArgumentException) {
                rawOptions
            }
        } else InflatedServiceOptions.empty.usable()
        val queryParams = if (useId != null) "?id=$useId" else "?locator=${locator.toString().encodeURLParameter()}"
        val rawPlugins = route.`x-dicentra-aviator-pluginMaterialization` ?: mapOf()
        val plugins = rawPlugins + mapOf(
            SynapsePlugin.identity to json.encodeToJsonElement(SynapsePluginConfig(true))
        )
        return "/catalyst$queryParams" to PathItem(
            post = Operation.build {
                `x-dicentra-aviator-serviceDelegateCall` = locator
                `x-dicentra-aviator-serviceOptions` = options
                `x-dicentra-aviator-pluginMaterialization` = plugins
            }
        )
    }

    fun generateWrapper(paths: List<Pair<String, PathItem>>): OpenApiDoc {
        val schema = if (configuration.catalyst.tlsEnabled) "https://" else "http://"
        return OpenApiDoc.build {
            `x-dicentra-aviator` = AviatorExtensionSpec.SpecVersion
            servers {
                server(url = schema + configuration.catalyst.serverName)
            }
            info = OpenApiInfo(
                title = configuration.catalyst.title,
                description = configuration.catalyst.description,
                version = AviatorExtensionSpec.SpecVersion
            )
        }.copy(paths = paths.associate { it.first to it.second.let(::Value) })
    }
}