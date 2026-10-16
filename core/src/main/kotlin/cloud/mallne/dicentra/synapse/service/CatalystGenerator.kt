package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-pluginMaterialization`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.koas.typed.Route
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.aviator.plugin.synapse.SynapsePlugin
import cloud.mallne.dicentra.aviator.plugin.synapse.SynapsePluginConfig
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystRequest
import cloud.mallne.dicentra.aviator.plugin.adapter.json.JsonAdapter
import cloud.mallne.dicentra.synapse.model.SynapseConfig
import cloud.mallne.dicentra.synapse.statics.Serialization
import io.ktor.http.*
import io.ktor.openapi.*
import io.ktor.openapi.ReferenceOr.*
import io.ktor.openapi.KotlinxSerializerJsonSchemaInference.Companion.Default
import io.ktor.openapi.jsonSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class CatalystGenerator(
    @Provided val configuration: SynapseConfig,
    val json: Json = Serialization(),
) {
    fun generateFor(
        route: Route,
    ): Pair<String, PathItem> {
        val rawLocator = route.`x-dicentra-aviator-serviceDelegateCall`
        val locator =
            rawLocator?.let { ServiceLocator(it) } ?: throw IllegalArgumentException("Service Locator is required")
        val actionSelector = CatalystActionSelector.encode(locator)
        // The generated client calls Catalyst, so only materialize its receiving plugin here.
        // Provider plugins and private service options are retained on the original server definition.
        val plugins = mapOf(
            SynapsePlugin.identity to json.encodeToJsonElement(SynapsePluginConfig(active = true)),
            // The receiving action body is the Catalyst envelope and must be serialized as JSON.
            JsonAdapter.identity to kotlinx.serialization.json.JsonObject(emptyMap()),
        )
        return "/catalyst/$actionSelector" to PathItem(
            post = Operation.build {
                summary = "Execute ${route.operationId ?: locator.toString()} through Synapse Catalyst"
                operationId = "Catalyst_$actionSelector"
                `x-dicentra-aviator-serviceDelegateCall` = locator
                `x-dicentra-aviator-pluginMaterialization` = plugins
                responses {
                    response(200) {
                        description = "Ordered array of results from selected original Aviator actions."
                        ContentType.Application.Json {
                            schema = Default.jsonSchema<List<JsonElement>>()
                        }
                    }
                    response(400) { description = "The request envelope or typed action input is invalid." }
                    response(404) { description = "No caller-visible action matches the selector or identity." }
                    response(413) { description = "The request or selected target count exceeds its configured limit." }
                    response(415) { description = "The request content type must be application/json." }
                    response(500) {
                        description = "Catalyst could not complete the action."
                        ContentType.Application.Json { schema = Default.jsonSchema<Map<String, String>>() }
                    }
                    response(502) {
                        description = "One or more upstream actions failed or the aggregate exceeded its limit."
                        ContentType.Application.Json { schema = Default.jsonSchema<Map<String, String>>() }
                    }
                    response(503) {
                        description = "A required provider plugin is unavailable."
                        ContentType.Application.Json { schema = Default.jsonSchema<Map<String, String>>() }
                    }
                    response(504) {
                        description = "Catalyst execution exceeded its configured deadline."
                        ContentType.Application.Json { schema = Default.jsonSchema<Map<String, String>>() }
                    }
                }
            }.copy(
                requestBody = Value(
                    RequestBody(
                        description = "Agnostic request parameters and body forwarded to the original Aviator action.",
                        required = true,
                        content = mapOf(
                            ContentType.Application.Json to MediaType(
                                schema = Value(Default.jsonSchema<CatalystRequest>())
                            )
                        )
                    )
                )
            )
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
