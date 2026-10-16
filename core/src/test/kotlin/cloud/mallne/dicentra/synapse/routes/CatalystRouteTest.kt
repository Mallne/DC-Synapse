package cloud.mallne.dicentra.synapse.routes

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.ServiceMethods
import cloud.mallne.dicentra.aviator.model.AviatorServiceUtils
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystRequest
import cloud.mallne.dicentra.synapse.model.User
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import cloud.mallne.dicentra.synapse.service.CatalystActionNotFoundException
import cloud.mallne.dicentra.synapse.service.CatalystActionSelector
import cloud.mallne.dicentra.synapse.service.ResolvedCatalystAction
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.auth.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.openapi.*
import io.ktor.openapi.ReferenceOr.Value
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalystRouteTest {
    private val locator = ServiceLocator("parcels", ServiceMethods.GATHER)
    private val validUser = User("Test", "test@example.com", "test", access = User.AccessLevels())
    private val invalidUser = User("Locked", "locked@example.com", "locked", locked = true, access = User.AccessLevels())

    @Test
    fun `anonymous request can execute only anonymously visible actions`(): Unit = testApplication {
        val publicAction = action("public-service")
        val privateAction = action("private-service")
        application {
            installRoutes(resolve = { _, user -> if (user == null) listOf(publicAction) else listOf(privateAction) })
        }

        val response = client.post(path()) {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[\"ok\"]", response.bodyAsText())
    }

    @Test
    fun `valid authenticated principal is passed to visible-action resolution`() = testApplication {
        val userAction = action("user-service")
        application {
            installRoutes(resolve = { _, user -> if (user?.username == "test") listOf(userAction) else emptyList() })
        }

        val response = client.post(path()) {
            bearerAuth("valid")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `invalid authenticated principal is rejected before action resolution`() = testApplication {
        var resolverCalled = false
        application {
            installRoutes(resolve = { _, _ ->
                resolverCalled = true
                listOf(action("service"))
            })
        }

        val response = client.post(path()) {
            bearerAuth("invalid-user")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(false, resolverCalled)
    }

    @Test
    fun `invalid selector is non-disclosing and does not resolve services`() = testApplication {
        var resolverCalled = false
        application {
            installRoutes(resolve = { _, _ ->
                resolverCalled = true
                emptyList()
            })
        }

        val response = client.post("/catalyst/not-base64!") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(false, resolverCalled)
    }

    @Test
    fun `inaccessible explicit identity maps to not found`() = testApplication {
        application {
            installRoutes(
                resolve = { _, _ -> listOf(action("visible-service")) },
                execute = { _, _ -> throw CatalystActionNotFoundException() },
            )
        }

        val response = client.post(path()) {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CatalystRequest.serializer(), CatalystRequest(identities = listOf("hidden-service"))))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `request body is capped before deserialization`() = testApplication {
        application {
            installRoutes(
                resolve = { _, _ -> listOf(action("service")) },
                maxRequestBytes = 8,
            )
        }

        val response = client.post(path()) {
            contentType(ContentType.Application.Json)
            setBody("{\"oversized\":true}")
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `provider execution begins only after authorized action resolution returns`() = testApplication {
        var resolutionComplete = false
        application {
            installRoutes(
                resolve = { _, _ ->
                    val resolved = action("service")
                    resolutionComplete = true
                    listOf(resolved)
                },
                execute = { _, _ ->
                    check(resolutionComplete) { "Execution started before the DB snapshot was released" }
                    JsonArray(listOf(JsonPrimitive("ok")))
                },
            )
        }

        val response = client.post(path()) {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    private fun Application.installRoutes(
        resolve: suspend (ServiceLocator, User?) -> List<ResolvedCatalystAction>,
        execute: suspend (List<ResolvedCatalystAction>, CatalystRequest) -> kotlinx.serialization.json.JsonElement =
            { _, _ -> JsonArray(listOf(JsonPrimitive("ok"))) },
        maxRequestBytes: Long = 1_048_576,
    ) {
        install(ContentNegotiation) { json(Json) }
        install(Authentication) {
            bearer("catalyst-test") {
                authenticate { credential ->
                    when (credential.token) {
                        "valid" -> validUser
                        "invalid-user" -> invalidUser
                        else -> null
                    }
                }
            }
        }
        routing {
            authenticate("catalyst-test", optional = true) {
                catalystEndpoint(resolve, execute, maxRequestBytes)
            }
        }
    }

    private fun path(): String = "/catalyst/${CatalystActionSelector.encode(locator)}"

    private fun action(id: String): ResolvedCatalystAction {
        val operation = Operation.build {
            operationId = "GetParcels"
            `x-dicentra-aviator-serviceDelegateCall` = locator
        }
        val definition = OpenApiDoc.build {
            `x-dicentra-aviator` = AviatorExtensionSpec.SpecVersion
            servers { server("https://provider.example") }
            info = OpenApiInfo(title = id, version = "1.0.0")
        }.copy(paths = mapOf("/parcels" to Value(PathItem(get = operation))))
        val service = APIServiceDTO(id = id, serviceDefinition = definition)
        val route = AviatorServiceUtils.extractServiceLocators(definition).single().second
        return ResolvedCatalystAction(service, route, locator)
    }
}
