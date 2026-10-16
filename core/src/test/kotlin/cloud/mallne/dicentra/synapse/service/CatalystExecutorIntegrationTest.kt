package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-pluginMaterialization`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.ServiceMethods
import cloud.mallne.dicentra.aviator.core.NoBody
import cloud.mallne.dicentra.aviator.core.plugins.BasicPluginActivationScope
import cloud.mallne.dicentra.aviator.model.AviatorServiceUtils
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.aviator.client.ktor.KtorAviatorServiceConverter
import cloud.mallne.dicentra.aviator.plugin.adapter.json.JsonAdapter
import cloud.mallne.dicentra.aviator.plugin.adapter.xml.XmlAdapter
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystRequest
import cloud.mallne.dicentra.aviator.plugin.synapse.SynapsePlugin
import cloud.mallne.dicentra.aviator.plugin.synapse.SynapsePluginInstance
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import cloud.mallne.dicentra.synapse.model.Catalyst
import cloud.mallne.dicentra.synapse.model.Database
import cloud.mallne.dicentra.synapse.model.Security
import cloud.mallne.dicentra.synapse.model.SecurityGroups
import cloud.mallne.dicentra.synapse.model.Server
import cloud.mallne.dicentra.synapse.model.ServerCors
import cloud.mallne.dicentra.synapse.model.SynapseConfig
import cloud.mallne.dicentra.synapse.statics.Serialization
import cloud.mallne.dicentra.aviator.core.execution.RequestParameter
import cloud.mallne.dicentra.aviator.core.execution.RequestParameters
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystParameterValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import io.ktor.openapi.*
import io.ktor.openapi.ReferenceOr.Value
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalystExecutorIntegrationTest {
    private val locator = ServiceLocator("parcels", ServiceMethods.GATHER)

    @Test
    fun `executes through Aviator and flattens results in deterministic service order`(): Unit = runBlocking {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url.host
            val body = when (request.url.host) {
                "provider-a.example" -> "[1,2]"
                "provider-b.example" -> "3"
                else -> error("Unexpected provider ${request.url.host}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })
        val executor = CatalystExecutor(
            listOf(JsonAdapterCatalystContribution()),
            client = client,
            allowedHosts = listOf("provider-a.example", "provider-b.example"),
            resolveProviderHost = ::publicTestResolution
        )
        try {
            val result = executor.execute(
                listOf(action("b", "provider-b.example"), action("a", "provider-a.example")),
                CatalystRequest(),
            )

            assertEquals("[1,2,3]", result.toString())
            assertEquals(listOf("provider-a.example", "provider-b.example"), requests)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `partial upstream failure yields gateway failure and never an apparent success`(): Unit = runBlocking {
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "provider-a.example") {
                respond("{\"ok\":true}", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            } else {
                respond("provider error", status = io.ktor.http.HttpStatusCode.BadGateway)
            }
        })
        val executor = CatalystExecutor(
            listOf(JsonAdapterCatalystContribution()),
            client = client,
            allowedHosts = listOf("provider-a.example", "provider-b.example"),
            resolveProviderHost = ::publicTestResolution
        )
        try {
            val failure = assertFailsWith<CatalystPartialExecutionException> {
                executor.execute(
                    listOf(action("a", "provider-a.example"), action("b", "provider-b.example")),
                    CatalystRequest(),
                )
            }
            assertEquals(listOf(kotlinx.serialization.json.JsonObject(mapOf("ok" to JsonPrimitive(true)))), failure.completedResults)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `XML provider responses are decoded by Aviator XML adapter before Catalyst JSON output`(): Unit = runBlocking {
        val client = HttpClient(MockEngine {
            respond("<value>42</value>", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Xml.toString()))
        })
        val executor = CatalystExecutor(
            listOf(JsonAdapterCatalystContribution(), XmlAdapterCatalystContribution()),
            client = client,
            allowedHosts = listOf("provider-a.example"),
            resolveProviderHost = ::publicTestResolution,
        )
        try {
            val result = executor.execute(listOf(action("a", "provider-a.example", includeXml = true)), CatalystRequest())
            assertEquals("[\"<value>42</value>\"]", result.toString())
        } finally {
            executor.close()
        }
    }

    @Test
    fun `explicit JSON null remains a JSON value in the aggregate`(): Unit = runBlocking {
        val client = HttpClient(MockEngine {
            respond("null", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })
        val executor = CatalystExecutor(listOf(JsonAdapterCatalystContribution()), client = client, allowedHosts = listOf("provider-a.example"), resolveProviderHost = ::publicTestResolution)
        try {
            val result = executor.execute(listOf(action("a", "provider-a.example")), CatalystRequest())
            assertEquals("[null]", result.toString())
        } finally {
            executor.close()
        }
    }

    @Test
    fun `generic JSON output matches direct Aviator execution`(): Unit = runBlocking {
        val client = HttpClient(MockEngine {
            respond("{\"items\":[1,2]}", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })
        val target = action("a", "provider-a.example")
        val executor = CatalystExecutor(listOf(JsonAdapterCatalystContribution()), client = client, allowedHosts = listOf("provider-a.example"), resolveProviderHost = ::publicTestResolution)
        try {
            val pluginScope = BasicPluginActivationScope().apply { add(JsonAdapter.install { }) }
            val directService = KtorAviatorServiceConverter(client, Json).build(target.service.serviceDefinition) {
                pluginScope.registry.forEach(::add)
            }.single()
            val direct = directService.requestContextful<JsonElement, NoBody>().result
            val gateway = executor.execute(listOf(target), CatalystRequest())

            assertEquals(JsonArray(listOf(requireNotNull(direct))), gateway)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `upstream response bytes are capped before Aviator deserialization`(): Unit = runBlocking {
        val client = HttpClient(MockEngine {
            respond("{\"oversized\":true}", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })
        val executor = CatalystExecutor(
            listOf(JsonAdapterCatalystContribution()),
            client = client,
            maxResponseBytes = 8,
            allowedHosts = listOf("provider-a.example"),
            resolveProviderHost = ::publicTestResolution,
        )
        try {
            assertFailsWith<CatalystUpstreamFailureException> {
                executor.execute(listOf(action("a", "provider-a.example")), CatalystRequest())
            }
        } finally {
            executor.close()
        }
    }

    @Test
    fun `generated gateway definition automatically activates Synapse plugin and sends typed request envelope`(): Unit = runBlocking {
        var invokedPath: String? = null
        var invokedMethod: io.ktor.http.HttpMethod? = null
        var wireRequest: CatalystRequest? = null
        val client = HttpClient(MockEngine { request ->
            invokedPath = request.url.encodedPath
            invokedMethod = request.method
            val content = request.body as TextContent
            wireRequest = Json.decodeFromString(CatalystRequest.serializer(), content.text)
            respond("{\"ok\":true}", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })
        val source = action("original", "provider-a.example")
        val generator = CatalystGenerator(
            SynapseConfig(
                security = Security(groups = SecurityGroups()),
                data = Database(),
                server = Server(cors = ServerCors()),
                catalyst = Catalyst(serverName = "catalyst.example"),
            ),
            Serialization(),
        )
        val generatedPath = generator.generateFor(source.route)
        val gatewayDefinition = generator.generateWrapper(listOf(generatedPath))
        val gatewayService = KtorAviatorServiceConverter(client, Json).build(gatewayDefinition) {
            // Installed inactive on purpose; generated per-operation materialization should activate it.
            add(SynapsePlugin.install { })
            add(JsonAdapter.install { })
        }.single()
        assertEquals(true, gatewayService.plugins.filterIsInstance<SynapsePluginInstance>().single().configurationBundle.active)

        try {
            val result = gatewayService.requestContextful<JsonElement, NoBody>(
                requestParams = RequestParameters(
                    mutableMapOf(
                        "enabled" to RequestParameter.Single(true, valueSerializer = Boolean.serializer()),
                    ),
                ),
            ).result

            assertEquals("/catalyst/${CatalystActionSelector.encode(locator)}", invokedPath)
            assertEquals(io.ktor.http.HttpMethod.Post, invokedMethod)
            assertEquals(Json.decodeFromString<JsonElement>("{\"ok\":true}"), result)
            assertEquals(1, wireRequest?.version)
            assertEquals(
                CatalystParameterValue(CatalystParameterValue.Cardinality.SINGLE, listOf(JsonPrimitive(true)), Boolean.serializer().descriptor.serialName),
                wireRequest?.typedParameters?.get("enabled"),
            )
        } finally {
            client.close()
        }
    }

    private fun publicTestResolution(host: String) = listOf(InetAddress.getByName("8.8.8.8"))

    @Test
    fun `generated client wraps pre-serialized XML as JSON instead of sending XML to browser upstream`(): Unit = runBlocking {
        val bytes = "<wfs:GetFeature xmlns:wfs=\"http://www.opengis.net/wfs/2.0\"/>\r\n".encodeToByteArray()
        var called = false
        val client = HttpClient(MockEngine { request ->
            called = true
            assertEquals("catalyst.example", request.url.host)
            assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
            val envelope = Json.decodeFromString(CatalystRequest.serializer(), (request.body as TextContent).text)
            assertEquals(cloud.mallne.dicentra.aviator.plugin.synapse.CatalystBodyFormat.Base64("application/xml"), envelope.bodyFormat)
            kotlin.test.assertContentEquals(bytes, kotlin.io.encoding.Base64.Default.decode((envelope.body as JsonPrimitive).content))
            respond("[]", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val generator = CatalystGenerator(
            SynapseConfig(
                security = Security(groups = SecurityGroups()), data = Database(),
                server = Server(cors = ServerCors()), catalyst = Catalyst(serverName = "catalyst.example"),
            )
        )
        val definition = generator.generateWrapper(listOf(generator.generateFor(action("wfs", "provider-a.example").route)))
        try {
            val service = KtorAviatorServiceConverter(client, Json).build(definition) {
                add(SynapsePlugin.install {})
                add(JsonAdapter.install {})
            }.single()
            service.requestContextful<JsonElement, cloud.mallne.dicentra.aviator.core.io.RawRequestBody>(
                requestBody = cloud.mallne.dicentra.aviator.core.io.RawRequestBody(bytes, "application/xml"),
            )
            assertEquals(true, called)
        } finally {
            client.close()
        }
    }

    @Test
    fun `public service definition host can be approved without duplicating deployment allowlist`() = runBlocking {
        val host = "public-provider.example"
        var requested = false
        val client = HttpClient(MockEngine { request ->
            requested = true
            assertEquals(host, request.url.host)
            respond("{\"ok\":true}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val executor = CatalystExecutor(
            listOf(JsonAdapterCatalystContribution()),
            client = client,
            allowedHosts = emptyList(),
            allowPublicServiceHosts = true,
            resolveProviderHost = ::publicTestResolution,
        )
        try {
            assertEquals("[{\"ok\":true}]", executor.execute(listOf(action("public", host)), CatalystRequest()).toString())
            assertEquals(true, requested)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `scoped service hosts still require the deployment allowlist`(): Unit = runBlocking {
        var called = false
        val guardedClient = HttpClient(MockEngine { called = true; error("Unapproved scoped host reached upstream") })
        val executor = CatalystExecutor(
            listOf(JsonAdapterCatalystContribution()),
            client = guardedClient,
            allowedHosts = emptyList(),
            allowPublicServiceHosts = true,
            resolveProviderHost = ::publicTestResolution,
        )
        try {
            val scoped = action("scoped", "scoped-provider.example").let { it.copy(service = it.service.copy(scope = "tenant")) }
            val failure = kotlin.test.assertFailsWith<CatalystUpstreamFailureException> {
                executor.execute(listOf(scoped), CatalystRequest())
            }
            kotlin.test.assertIs<CatalystOutboundPolicyException>(failure.cause)
            assertEquals(false, called)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `executor binds declared provider plugin inputs absent from OpenAPI parameters`(): Unit = runBlocking {
        val contract = CatalystParameterContract(
            name = "pluginFlag",
            cardinality = CatalystParameterValue.Cardinality.SINGLE,
            serializer = Boolean.serializer(),
        )
        val contribution = object : CatalystPluginContribution {
            override val identity = JsonAdapter.identity
            override val parameterCodecs = listOf(Boolean.serializer())
            override val parameterContracts = listOf(contract)
            override fun create() = JsonAdapter.install { }
        }
        val client = HttpClient(MockEngine {
            respond("{\"ok\":true}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val executor = CatalystExecutor(
            listOf(contribution),
            client = client,
            allowedHosts = listOf("provider-a.example"),
            resolveProviderHost = ::publicTestResolution,
        )
        try {
            val request = CatalystRequest(
                typedParameters = mapOf(
                    contract.name to CatalystParameterValue(
                        contract.cardinality,
                        listOf(JsonPrimitive(true)),
                        contract.codec,
                    ),
                ),
            )

            assertEquals("[{\"ok\":true}]", executor.execute(listOf(action("a", "provider-a.example")), request).toString())
        } finally {
            executor.close()
        }
    }

    private fun action(id: String, host: String, includeXml: Boolean = false): ResolvedCatalystAction {
        val materializations = buildMap {
            put(JsonAdapter.identity, kotlinx.serialization.json.JsonObject(emptyMap()))
            if (includeXml) put(XmlAdapter.identity, kotlinx.serialization.json.JsonObject(emptyMap()))
        }
        val operation = Operation.build {
            operationId = "GetParcels"
            `x-dicentra-aviator-serviceDelegateCall` = locator
            `x-dicentra-aviator-pluginMaterialization` = materializations
        }
        val definition = OpenApiDoc.build {
            `x-dicentra-aviator` = AviatorExtensionSpec.SpecVersion
            servers { server("https://$host") }
            info = OpenApiInfo(title = id, version = "1.0.0")
        }.copy(paths = mapOf("/parcels" to Value(PathItem(get = operation))))
        val service = APIServiceDTO(id = id, serviceDefinition = definition)
        val route = AviatorServiceUtils.extractServiceLocators(definition).single().second
        return ResolvedCatalystAction(service, route, locator)
    }
}
