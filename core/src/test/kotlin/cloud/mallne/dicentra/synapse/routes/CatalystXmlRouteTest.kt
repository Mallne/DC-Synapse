package cloud.mallne.dicentra.synapse.routes

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-pluginMaterialization`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.ServiceMethods
import cloud.mallne.dicentra.aviator.model.AviatorServiceUtils
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.aviator.plugin.adapter.json.JsonAdapter
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystRequest
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystBodyFormat
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import cloud.mallne.dicentra.synapse.service.*
import cloud.mallne.dicentra.synapse.config.configureCatalystCors
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.openapi.*
import io.ktor.openapi.ReferenceOr.Value
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.net.InetAddress
import kotlin.io.encoding.Base64
import kotlin.test.*

class CatalystXmlRouteTest {
    private val host = "www.geoproxy.geoportal-th.de"
    private val origin = "https://areaassist.example"
    private val locator = ServiceLocator("parcels", ServiceMethods.GATHER)
    private val path = "/catalyst/${CatalystActionSelector.encode(locator)}"
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<wfs:GetFeature xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:fes="http://www.opengis.net/fes/2.0" xmlns:gml="http://www.opengis.net/gml/3.2" service="WFS" version="2.0.0">
  <wfs:Query typeNames="Flurstueck"><fes:Filter><fes:BBOX><gml:Envelope srsName="EPSG:4326"><gml:lowerCorner>50.9 11.0</gml:lowerCorner><gml:upperCorner>51.0 11.1</gml:upperCorner></gml:Envelope></fes:BBOX></fes:Filter></wfs:Query>
</wfs:GetFeature>""".replace("\n", "\r\n")

    @Test
    fun `browser JSON envelope forwards exact XML POST without upstream CORS headers`() = testApplication {
        var calls = 0
        val executor = executor { request ->
            calls++
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(host, request.url.host)
            assertEquals("/geoproxy/services/adv_alkis_wfs", request.url.encodedPath)
            assertEquals("application/xml", request.body.contentType.toString())
            assertContentEquals(xml.encodeToByteArray(), (request.body as OutgoingContent.ByteArrayContent).bytes())
        }
        application { setup(executor) }
        try {
            val preflight = client.options(path) {
                header(HttpHeaders.Origin, origin)
                header(HttpHeaders.AccessControlRequestMethod, "POST")
                header(HttpHeaders.AccessControlRequestHeaders, "content-type,authorization")
            }
            assertEquals(HttpStatusCode.OK, preflight.status)
            assertEquals(origin, preflight.headers[HttpHeaders.AccessControlAllowOrigin])
            val response = client.post(path) {
                header(HttpHeaders.Origin, origin)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(CatalystRequest.serializer(), envelope()))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(origin, response.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals(1, calls)
            val denied = client.options(path) {
                header(HttpHeaders.Origin, "https://untrusted.example")
                header(HttpHeaders.AccessControlRequestMethod, "POST")
            }
            assertNull(denied.headers[HttpHeaders.AccessControlAllowOrigin])
        } finally {
            executor.close()
        }
    }

    @Test
    fun `invalid XML envelopes and undeclared media types are rejected before upstream`() = testApplication {
        val executor = executor { fail("Invalid body reached upstream") }
        application { setup(executor) }
        try {
            val requests = listOf(
                envelope().copy(body = JsonObject(emptyMap())),
                envelope().copy(bodyFormat = CatalystBodyFormat.Base64("application/octet-stream")),
                envelope().copy(bodyFormat = CatalystBodyFormat.Base64("not-a-media-type")),
                envelope().copy(bodyFormat = CatalystBodyFormat.Base64("text/xml")),
                envelope().copy(body = JsonPrimitive("%%%=")),
                envelope().copy(body = JsonPrimitive("")),
                envelope().copy(body = JsonPrimitive("AB==")),
                envelope().copy(bodyFormat = CatalystBodyFormat.Base64("application/xml\r\nX-Evil: yes")),
            )
            for (request in requests) {
                assertEquals(HttpStatusCode.BadRequest, client.post(path) {
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(CatalystRequest.serializer(), request))
                }.status)
            }
            for (malformed in listOf("{\"bodyFormat\":{\"type\":\"base64\"}}", "{\"bodyFormat\":{\"type\":\"unknown\"}}")) {
                assertEquals(HttpStatusCode.BadRequest, client.post(path) {
                    contentType(ContentType.Application.Json)
                    setBody(malformed)
                }.status)
            }
        } finally {
            executor.close()
        }
    }

    @Test
    fun `XML envelope is subject to existing request byte limit`() = testApplication {
        val executor = executor { fail("Oversized body reached upstream") }
        application { setup(executor, maxBytes = 128) }
        try {
            assertEquals(HttpStatusCode.PayloadTooLarge, client.post(path) {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(CatalystRequest.serializer(), envelope()))
            }.status)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `serialized bytes and charset survive without a UTF-8 roundtrip`() = testApplication {
        val bytes = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><parcel>Thüringen</parcel>".toByteArray(Charsets.UTF_16)
        var calls = 0
        val executor = executor { request ->
            calls++
            assertEquals(ContentType.parse("text/xml; charset=UTF-16"), request.body.contentType)
            assertContentEquals(bytes, (request.body as OutgoingContent.ByteArrayContent).bytes())
        }
        application { setup(executor, mediaType = ContentType.Text.Xml) }
        try {
            val envelope = CatalystRequest(
                body = JsonPrimitive(Base64.Default.encode(bytes)),
                bodyFormat = CatalystBodyFormat.Base64("text/xml; charset=UTF-16"),
            )
            assertEquals(HttpStatusCode.OK, client.post(path) {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(CatalystRequest.serializer(), envelope))
            }.status)
            assertEquals(1, calls)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `JSON-only action cannot receive serialized XML`() = testApplication {
        val executor = executor { fail("Undeclared XML reached upstream") }
        application { setup(executor, mediaType = ContentType.Application.Json) }
        try {
            assertEquals(HttpStatusCode.BadRequest, client.post(path) {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(CatalystRequest.serializer(), envelope()))
            }.status)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `JSON action body still forwards as JSON`() = testApplication {
        var calls = 0
        val executor = executor { request ->
            calls++
            assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
            assertEquals("{\"ok\":true}", (request.body as io.ktor.http.content.TextContent).text)
        }
        application { setup(executor, mediaType = ContentType.Application.Json) }
        try {
            assertEquals(HttpStatusCode.OK, client.post(path) {
                contentType(ContentType.Application.Json)
                setBody("{\"body\":{\"ok\":true}}")
            }.status)
            assertEquals(1, calls)
        } finally {
            executor.close()
        }
    }

    private fun envelope() = CatalystRequest(body = JsonPrimitive(Base64.Default.encode(xml.encodeToByteArray())), bodyFormat = CatalystBodyFormat.Base64("application/xml"))

    private fun executor(check: (io.ktor.client.request.HttpRequestData) -> Unit) = CatalystExecutor(
        listOf(JsonAdapterCatalystContribution()),
        client = HttpClient(MockEngine { request ->
            check(request)
            respond("{\"ok\":true}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }),
        allowedHosts = listOf(host),
        resolveProviderHost = { listOf(InetAddress.getByName("8.8.8.8")) },
    )

    private fun Application.setup(executor: CatalystExecutor, maxBytes: Long = 1_048_576, mediaType: ContentType = ContentType.Application.Xml) {
        install(ContentNegotiation) { json() }
        install(Authentication) {}
        configureCatalystCors(listOf("areaassist.example"))
        val operation = Operation.build {
            operationId = "GetWfsParcels"
            `x-dicentra-aviator-serviceDelegateCall` = locator
            `x-dicentra-aviator-pluginMaterialization` = mapOf(JsonAdapter.identity to JsonObject(emptyMap()))
        }.copy(
            requestBody = Value(
                RequestBody(
                    required = true,
                    content = mapOf(mediaType to MediaType(schema = Value(JsonSchema(type = JsonType.OBJECT)))),
                )
            ),
        )
        val definition = OpenApiDoc.build {
            `x-dicentra-aviator` = AviatorExtensionSpec.SpecVersion
            servers { server("https://$host") }
            info = OpenApiInfo(title = "WFS", version = "1")
        }.copy(paths = mapOf("/geoproxy/services/adv_alkis_wfs" to Value(PathItem(post = operation))))
        val service = APIServiceDTO(id = "wfs", serviceDefinition = definition)
        val action = ResolvedCatalystAction(service, AviatorServiceUtils.extractServiceLocators(definition).single().second, locator)
        routing { catalystEndpoint({ _, _ -> listOf(action) }, executor::execute, maxBytes) }
    }
}
