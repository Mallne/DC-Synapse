package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-pluginMaterialization`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.plugin.synapse.SynapseExtensionSpec.`x-dicentra-synapse-aggregateContracts`
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO.Companion.transform
import cloud.mallne.dicentra.synapse.statics.ServiceDefinitionTransformationType
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

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
    fun `generated multi-provider inline response unions can be parsed by Aviator`() {
        fun provider(id: String) = APIServiceDTO(id = id,
            serviceDefinition = generator.json.decodeFromString<OpenApiDoc>("""
                {
                  "openapi":"3.1.0", "info":{"title":"$id","version":"1.0.0"},
                  "paths":{"/items":{"get":{
                    "operationId":"items", "x-dicentra-aviator-serviceDelegateCall":"&.items>gather",
                    "responses":{
                      "200":{"description":"Items","content":{"application/json":{"schema":{
                        "type":"array","items":{"type":"object","properties":{"$id":{"type":"string"}}}
                      }}}},
                      "201":{"description":"Created","content":{"application/json":{"schema":{
                        "type":"object","properties":{"${id}Created":{"type":"boolean"}}
                      }}}}
                    }
                  }}}
                }
            """))
        val output = listOf(provider("first"), provider("second"))
            .transform(ServiceDefinitionTransformationType.Catalyst, catalystGenerator = generator).single()
        val restored = generator.json.decodeFromString<OpenApiDoc>(generator.json.encodeToString(OpenApiDoc.serializer(), output))
        val route = AviatorServiceUtils.extractServiceLocators(restored).single().second
        val outputModel = route.returnType.types.getValue(HttpStatusCode.OK).types
            .getValue(ContentType.Application.Json).model as Model.Collection.List
        assertEquals(4, (outputModel.inner as Model.Union).cases.size)
        assertEquals(2, route.`x-dicentra-synapse-aggregateContracts`.size)
    }

    @Test
    fun `component namespacing preserves discriminator targets and literal reference payloads`() {
        val source = generator.json.decodeFromString<OpenApiDoc>("""
            {
              "openapi":"3.1.0", "info":{"title":"Pets","version":"1.0.0"},
              "paths":{"/pets":{"get":{
                "operationId":"pets", "x-dicentra-aviator-serviceDelegateCall":"&.pets>gather",
                "responses":{"200":{"description":"Pets","content":{"application/json":{
                  "schema":{"type":"array","items":{"${'$'}ref":"#/components/schemas/Pet"}}
                }}}}
              }}},
              "components":{"schemas":{
                "Pet":{"oneOf":[{"${'$'}ref":"#/components/schemas/Cat"},{"${'$'}ref":"#/components/schemas/Dog"}],
                  "discriminator":{"propertyName":"kind","mapping":{"cat":"#/components/schemas/Cat","dog":"Dog"}}},
                "Cat":{"type":"object","properties":{"kind":{"type":"string"}}},
                "Dog":{"type":"object","properties":{"kind":{"type":"string"}}},
                "Literal":{"type":"object","default":{"${'$'}ref":"#/components/schemas/Cat"},
                  "example":{"${'$'}ref":"#/components/schemas/Cat"},
                  "properties":{"default":{"${'$'}ref":"#/components/schemas/Cat"}}}
              }}
            }
        """)
        val output = listOf(APIServiceDTO(id = "pets", serviceDefinition = source))
            .transform(ServiceDefinitionTransformationType.Catalyst, catalystGenerator = generator).single()
        val schemas = generator.json.encodeToJsonElement(output).jsonObject.getValue("components")
            .jsonObject.getValue("schemas").jsonObject
        val cat = schemas.keys.single { it.endsWith("_Cat") }
        val dog = schemas.keys.single { it.endsWith("_Dog") }
        val pet = schemas.entries.single { it.key.endsWith("_Pet") }.value.jsonObject
        val mapping = pet.getValue("discriminator").jsonObject.getValue("mapping").jsonObject
        assertEquals("#/components/schemas/$cat", mapping.getValue("cat").jsonPrimitive.content)
        assertEquals(dog, mapping.getValue("dog").jsonPrimitive.content)
        val literal = schemas.entries.single { it.key.endsWith("_Literal") }.value.jsonObject
        assertEquals("#/components/schemas/Cat", literal.getValue("default").jsonObject
            .getValue("${'$'}ref").jsonPrimitive.content)
        assertEquals(literal["default"], literal["example"])
        assertEquals("#/components/schemas/$cat", literal.getValue("properties").jsonObject
            .getValue("default").jsonObject.getValue("${'$'}ref").jsonPrimitive.content)
    }

    @Test
    fun `operations without operationId retain separate request schemas`() {
        val source = generator.json.decodeFromString<OpenApiDoc>("""
            {
              "openapi":"3.1.0", "info":{"title":"Source","version":"1.0.0"},
              "paths":{
                "/text":{"post":{
                  "x-dicentra-aviator-serviceDelegateCall":"&.text>gather",
                  "requestBody":{"required":true,"content":{"application/json":{"schema":{"${'$'}ref":"#/components/schemas/Text"}}}}
                }},
                "/number":{"post":{
                  "x-dicentra-aviator-serviceDelegateCall":"&.number>gather",
                  "requestBody":{"required":true,"content":{"application/json":{"schema":{"${'$'}ref":"#/components/schemas/Number"}}}}
                }}
              },
              "components":{"schemas":{"Text":{"type":"string"},"Number":{"type":"number"}}}
            }
        """)
        val output = listOf(APIServiceDTO(id = "source", serviceDefinition = source))
            .transform(ServiceDefinitionTransformationType.Catalyst, catalystGenerator = generator).single()
        val operationReferences = generator.json.encodeToJsonElement(output).jsonObject.getValue("paths")
            .jsonObject.values.map { path ->
                path.jsonObject.getValue("post").jsonObject.getValue("requestBody").jsonObject
                    .getValue("content").jsonObject.getValue("application/json").jsonObject
                    .getValue("schema").jsonObject.getValue("anyOf").toString()
            }
        assertEquals(2, operationReferences.distinct().size,
            "Different original actions must not reference the same receiving request schema")
        val schemas = generator.json.encodeToJsonElement(output).jsonObject.getValue("components")
            .jsonObject.getValue("schemas").jsonObject
        assertEquals(2, schemas.keys.count { it.startsWith("CatalystRequest_") })
        assertEquals(setOf("string", "number"), schemas.filterKeys { it.startsWith("CatalystRequest_") }
            .values.map { it.jsonObject.getValue("properties").jsonObject.getValue("body")
                .jsonObject.getValue("type").jsonPrimitive.content }.toSet())
    }

    @Test
    fun `many providers retain distinct public contracts in one Catalyst operation`() {
        fun provider(id: String, field: String, unit: String, type: String): APIServiceDTO {
            val spec = generator.json.decodeFromString<OpenApiDoc>("""
                {
                  "openapi":"3.1.0", "x-dicentra-aviator":"1.0.0",
                  "info":{"title":"$id", "version":"1.0.0", "license":{"name":"License $id"},
                    "termsOfService":"https://$id.example/terms"},
                  "externalDocs":{"url":"https://$id.example/docs","description":"Documentation $id"},
                  "servers":[{"url":"https://$id.example"}],
                  "paths":{"/parcels":{"get":{
                    "operationId":"parcels",
                    "x-dicentra-aviator-serviceDelegateCall":"&.curator.parcelService>gather",
                    "x-dicentra-aviator-pluginMaterialization":{"private-provider-plugin":{"secret":"must-not-leak"}},
                    "x-dicentra-aviator-serviceOptions":{
                      "parcelLinkReference":"$id", "keys":[{"identifier":"$field","unit":"$unit"}]
                    },
                    "parameters":[{"name":"$field","in":"query","schema":{"type":"$type"}}],
                    "responses":{"200":{"description":"parcels","content":{"application/json":{
                      "schema":{"type":"array","items":{"${'$'}ref":"#/components/schemas/Parcel"}}
                    }}}}
                  }}},
                  "components":{"schemas":{"Parcel":{"type":"object","properties":{"$field":{"type":"$type"}}}}}
                }
            """)
            return APIServiceDTO(id = id, serviceDefinition = spec)
        }
        val sources = listOf(provider("hessen", "area", "squareMeters", "number"),
            provider("other", "district", "hectare", "string"))
        val output = sources.transform(ServiceDefinitionTransformationType.Catalyst, catalystGenerator = generator)
        assertEquals(1, output.size)
        val wire = generator.json.encodeToString(OpenApiDoc.serializer(), output.single())
        assertTrue(wire.contains("x-dicentra-synapse-aggregateContracts"))
        assertFalse(wire.contains("x-dicentra-aviator-aggregateContracts"))
        val restored = generator.json.decodeFromString<OpenApiDoc>(wire)
        assertEquals(1, restored.paths.size)
        val route = AviatorServiceUtils.extractServiceLocators(restored).single().second
        val contracts = route.`x-dicentra-synapse-aggregateContracts`
        assertEquals(listOf("hessen", "other"), contracts.map { it.identity })
        assertEquals(sources.map { it.serviceDefinition.info }, contracts.map { it.info })
        assertEquals(listOf("License hessen", "License other"), contracts.map { it.info.license?.name })
        assertEquals(listOf("hessen", "other"), contracts.map { it.info.title })
        assertEquals(listOf("1.0.0", "1.0.0"), contracts.map { it.info.version })
        assertEquals(listOf("https://hessen.example/terms", "https://other.example/terms"),
            contracts.map { it.info.termsOfService })
        assertEquals(sources.map { it.serviceDefinition.externalDocs }, contracts.map { it.externalDocs })
        assertEquals(listOf("hessen", "other"), contracts.map {
            it.options!!.deserialize(JsonElement.serializer()).jsonObject.getValue("parcelLinkReference").jsonPrimitive.content
        })
        assertFalse(wire.contains("private-provider-plugin"))
        assertFalse(wire.contains("must-not-leak"))
        assertEquals(setOf(SynapsePlugin.identity, JsonAdapter.identity),
            route.`x-dicentra-aviator-pluginMaterialization`.orEmpty().keys)
        val schemaNames = generator.json.encodeToJsonElement(restored).jsonObject.getValue("components")
            .jsonObject.getValue("schemas").jsonObject.keys
        assertEquals(2, schemaNames.count { it.endsWith("_Parcel") })
        assertEquals(2, schemaNames.count { it.startsWith("CatalystRequest_") })
        schemaNames.filter { it.endsWith("_Parcel") }.forEach { name ->
            assertTrue(wire.contains("#/components/schemas/$name"))
        }
        val contractWire = generator.json.encodeToJsonElement(restored).jsonObject.getValue("paths")
            .jsonObject.values.single().jsonObject.getValue("post").jsonObject
            .getValue("x-dicentra-synapse-aggregateContracts")
        assertFalse(contractWire.toString().contains("requestSchema"))
        assertFalse(contractWire.toString().contains("responseSchema"))
        assertTrue(wire.contains("squareMeters"))
        assertTrue(wire.contains("hectare"))
        assertTrue(wire.contains("anyOf"))
    }

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
        assertEquals("SourceAction through Catalyst", operation.summary)
        assertEquals(generated.first, generator.generateFor(sourceRoute).first)
    }
}
