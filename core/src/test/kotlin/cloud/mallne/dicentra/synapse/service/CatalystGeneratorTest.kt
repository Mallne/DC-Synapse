package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-pluginMaterialization`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.core.ServiceMethods
import cloud.mallne.dicentra.aviator.model.AviatorServiceUtils
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.aviator.koas.typed.Model
import cloud.mallne.dicentra.aviator.koas.typed.Route
import cloud.mallne.dicentra.aviator.koas.typed.Constraints
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
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.ParameterType
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

        val typedRoute = sourceRoute.copy(
            input = listOf(
                Route.Input("latitude", Model.Primitive.Double(null, "Latitude", null), true, ParameterType.query, "Latitude"),
                Route.Input(
                    "bounded",
                    Model.Primitive.Int(null, null, Constraints.Number(false, Double.NEGATIVE_INFINITY, false, 12.0, null)),
                    false,
                    ParameterType.query,
                    null,
                ),
                Route.Input(
                    "fields",
                    Model.Collection.List(
                        Model.Primitive.String(null, null, null), null, "Fields", Constraints.Collection(2, 5),
                    ),
                    false,
                    ParameterType.query,
                    null,
                ),
            ),
            body = Route.Bodies(
                required = true,
                types = mapOf(
                    ContentType.Application.Json to Route.Bodies.Body(
                        model = Model.Primitive.String(null, "Text", null),
                        schema = JsonSchema(type = JsonType.STRING),
                        parameters = emptyMap(),
                    ),
                    ContentType.Application.Xml to Route.Bodies.Body(
                        model = Model.OctetStream("XML bytes"),
                        schema = null,
                        parameters = emptyMap(),
                    ),
                ),
                extensions = emptyMap(),
            ),
            returnType = Route.Returns(
                mapOf(
                    HttpStatusCode.OK to Route.ReturnType(
                        mapOf(ContentType.Application.Json to Route.Bodies.Body(
                            model = Model.FreeFormJson("Report", null),
                            schema = JsonSchema(type = JsonType.OBJECT, properties = mapOf(
                                "temperature" to Value(JsonSchema(type = JsonType.NUMBER)),
                            )),
                            parameters = emptyMap(),
                        )),
                        extensions = emptyMap(),
                    ),
                    HttpStatusCode.Created to Route.ReturnType(
                        mapOf(ContentType.Application.Json to Route.Bodies.Body(
                            model = Model.FreeFormJson("CreatedReport", null),
                            schema = JsonSchema(type = JsonType.OBJECT, properties = mapOf(
                                "created" to Value(JsonSchema(type = JsonType.BOOLEAN)),
                            )),
                            parameters = emptyMap(),
                        )),
                        extensions = emptyMap(),
                    ),
                    HttpStatusCode.Accepted to Route.ReturnType(
                        mapOf(ContentType.Application.Json to Route.Bodies.Body(
                            model = Model.Collection.List(Model.Primitive.Int(null, null, null), null, null, null),
                            schema = JsonSchema(type = JsonType.ARRAY, items = Value(JsonSchema(type = JsonType.INTEGER))),
                            parameters = emptyMap(),
                        )),
                        extensions = emptyMap(),
                    ),
                ),
                extensions = emptyMap(),
            ),
        )
        val generated = generator.generateFor(typedRoute)
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
        assertTrue(requestSchema.properties.orEmpty().keys.containsAll(setOf("version", "body", "typedParameters", "identities", "bodyFormat")))
        assertEquals(setOf(ContentType.Application.Json), requestBody.content?.keys)
        assertTrue(requestBody.description.orEmpty().contains("canonical standard padded base64"))
        assertTrue(requestBody.description.orEmpty().contains("bodyFormat"))
        val schemaJson = kotlinx.serialization.json.Json.encodeToString(JsonSchema.serializer(), requestSchema)
        assertTrue(schemaJson.contains("base64"))
        assertTrue(schemaJson.contains("contentType"))
        val typedParameters = assertNotNull(requestSchema.properties?.get("typedParameters")?.valueOrNull())
        assertTrue(typedParameters.required.orEmpty().contains("latitude"))
        val latitude = assertNotNull(typedParameters.properties?.get("latitude")?.valueOrNull())
        val latitudeValues = assertNotNull(latitude.properties?.get("values")?.valueOrNull())
        assertEquals(JsonType.NUMBER, latitudeValues.items?.valueOrNull()?.type)
        val boundedValues = assertNotNull(
            typedParameters.properties?.get("bounded")?.valueOrNull()?.properties?.get("values")?.valueOrNull(),
        )
        val boundedItem = assertNotNull(boundedValues.items?.valueOrNull())
        assertEquals(null, boundedItem.minimum)
        assertEquals(12.0, boundedItem.maximum)
        val fields = assertNotNull(typedParameters.properties?.get("fields")?.valueOrNull())
        val fieldsValues = assertNotNull(fields.properties?.get("values")?.valueOrNull())
        assertEquals(2, fieldsValues.minItems)
        assertEquals(5, fieldsValues.maxItems)
        val bodySchema = assertNotNull(requestSchema.properties?.get("body")?.valueOrNull())
        assertEquals(2, bodySchema.anyOf?.size)
        assertEquals(listOf(JsonType.STRING, JsonType.STRING), bodySchema.anyOf.orEmpty().map { it.valueOrNull()?.type })
        val okResponse = assertNotNull(operation.responses?.responses?.get(200) as? Value<Response>).value
        val responseSchema = assertNotNull(
            (okResponse.content?.get(ContentType.Application.Json)?.schema as? Value<JsonSchema>)?.value,
        )
        assertEquals(JsonType.ARRAY, responseSchema.type)
        val responseItemSchema = assertNotNull(responseSchema.items?.valueOrNull())
        assertEquals(3, responseItemSchema.anyOf?.size)
        assertEquals(
            setOf(JsonType.OBJECT, JsonType.INTEGER),
            responseItemSchema.anyOf.orEmpty().mapNotNull { it.valueOrNull()?.type }.toSet(),
        )
        assertEquals(setOf(200, 400, 404, 413, 415, 500, 502, 503, 504), operation.responses?.responses?.keys)
        assertEquals(
            setOf(SynapsePlugin.identity, JsonAdapter.identity),
            operation.`x-dicentra-aviator-pluginMaterialization`.orEmpty().keys,
        )
        assertEquals("Catalyst_${CatalystActionSelector.encode(ServiceLocator("source", ServiceMethods.GATHER))}", operation.operationId)
        assertEquals(generated.first, generator.generateFor(sourceRoute).first)
    }
}
