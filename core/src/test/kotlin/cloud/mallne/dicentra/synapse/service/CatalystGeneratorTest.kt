package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-pluginMaterialization`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.core.ServiceMethods
import cloud.mallne.dicentra.aviator.model.AviatorServiceUtils
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.aviator.plugin.synapse.SynapsePlugin
import cloud.mallne.dicentra.aviator.plugin.adapter.json.JsonAdapter
import cloud.mallne.dicentra.synapse.model.Catalyst
import cloud.mallne.dicentra.synapse.model.Database
import cloud.mallne.dicentra.synapse.model.Security
import cloud.mallne.dicentra.synapse.model.SecurityGroups
import cloud.mallne.dicentra.synapse.model.Server
import cloud.mallne.dicentra.synapse.model.ServerCors
import cloud.mallne.dicentra.synapse.model.SynapseConfig
import cloud.mallne.dicentra.synapse.statics.Serialization
import io.ktor.http.ContentType
import io.ktor.openapi.*
import io.ktor.openapi.ReferenceOr.*
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CatalystGeneratorTest {
    private val generator = CatalystGenerator(
        configuration = SynapseConfig(
            security = Security(groups = SecurityGroups()),
            data = Database(),
            server = Server(cors = ServerCors()),
            catalyst = Catalyst(),
        ),
        json = Serialization(),
    )

    @Test
    fun `generated operation declares JSON CatalystRequest and only receiving plugin`() {
        val source = OpenApiDoc.build {
            `x-dicentra-aviator` = AviatorExtensionSpec.SpecVersion
            servers { server("https://upstream.example") }
            info = OpenApiInfo(title = "Source", version = "1.0.0")
        }.copy(
            paths = mapOf(
                "/source" to Value(
                    PathItem(
                        get = Operation.build {
                            operationId = "SourceAction"
                            `x-dicentra-aviator-serviceDelegateCall` =
                                ServiceLocator("source", ServiceMethods.GATHER)
                            `x-dicentra-aviator-pluginMaterialization` = mapOf(
                                "provider-transform" to JsonObject(emptyMap())
                            )
                        }
                    )
                )
            )
        )
        val sourceRoute = AviatorServiceUtils.extractServiceLocators(source).single().second

        val generated = generator.generateFor(sourceRoute)
        val operation = assertNotNull(generated.second.post)
        val requestBody = assertNotNull(operation.requestBody as? Value<RequestBody>).value

        assertEquals(
            "/catalyst/${CatalystActionSelector.encode(ServiceLocator("source", ServiceMethods.GATHER))}",
            generated.first,
        )
        assertTrue(requestBody.required)
        assertTrue(requestBody.content?.containsKey(ContentType.Application.Json) == true)
        val requestSchema = assertNotNull(
            (requestBody.content?.get(ContentType.Application.Json)?.schema as? Value<JsonSchema>)?.value,
        )
        assertEquals(JsonType.OBJECT, requestSchema.type)
        assertTrue(requestSchema.properties.orEmpty().keys.containsAll(setOf("version", "body", "typedParameters", "identities")))
        val okResponse = assertNotNull(operation.responses?.responses?.get(200) as? Value<Response>).value
        val responseSchema = assertNotNull(
            (okResponse.content?.get(ContentType.Application.Json)?.schema as? Value<JsonSchema>)?.value,
        )
        assertEquals(JsonType.ARRAY, responseSchema.type)
        assertEquals(setOf(200, 400, 404, 413, 415, 500, 502, 503, 504), operation.responses?.responses?.keys)
        assertEquals(
            setOf(SynapsePlugin.identity, JsonAdapter.identity),
            operation.`x-dicentra-aviator-pluginMaterialization`.orEmpty().keys,
        )
        assertEquals("Catalyst_${CatalystActionSelector.encode(ServiceLocator("source", ServiceMethods.GATHER))}", operation.operationId)
        assertEquals(generated.first, generator.generateFor(sourceRoute).first)
    }
}
