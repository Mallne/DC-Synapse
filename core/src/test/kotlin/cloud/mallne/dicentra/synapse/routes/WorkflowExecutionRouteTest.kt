package cloud.mallne.dicentra.synapse.routes

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import cloud.mallne.dicentra.synapse.service.CatalystExecutor
import cloud.mallne.dicentra.synapse.service.CatalystAggregateLimitException
import cloud.mallne.dicentra.synapse.service.JsonAdapterCatalystContribution
import cloud.mallne.dicentra.synapse.service.StoredWorkflow
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.openapi.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowExecutionRouteTest {
    @Test
    fun `aggregate limit failure returns bad gateway`() = testApplication {
        val workflow = StoredWorkflow("doc", null, Json.parseToJsonElement("{}"))
        application {
            install(Authentication)
            routing {
                workflowExecutionEndpoint(1024, { _, _ -> workflow to emptyList() },
                    { _, _, _, _ -> throw CatalystAggregateLimitException() })
            }
        }
        val response = client.post("/workflows/doc/wf/execute") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadGateway, response.status)
    }

    @Test
    fun `route executes a visible stored workflow through Aviator`() = testApplication {
        var calls = 0
        val provider = HttpClient(MockEngine {
            calls++
            respond("{\"value\":7}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val executor = CatalystExecutor(listOf(JsonAdapterCatalystContribution()), client = provider,
            allowedHosts = listOf("provider.example"), resolveProviderHost = { listOf(InetAddress.getByName("8.8.8.8")) })
        val definition = OpenApiDoc.build {
            `x-dicentra-aviator` = AviatorExtensionSpec.SpecVersion
            info = OpenApiInfo(title = "provider", version = "1")
            servers { server("https://provider.example") }
        }.copy(paths = mapOf("/data" to ReferenceOr.Value(PathItem(get = Operation.build {
            operationId = "getData"
            `x-dicentra-aviator-serviceDelegateCall` = ServiceLocator("data")
        }))))
        val workflow = StoredWorkflow("doc", null, Json.parseToJsonElement(
            """{"arazzo":"1.1.0","info":{"title":"test","version":"1"},"sourceDescriptions":[{"name":"api","url":"urn:synapse:service:provider","type":"openapi"}],"workflows":[{"workflowId":"wf","steps":[{"stepId":"one","operationId":"${'$'}sourceDescriptions.api.getData","outputs":{"value":"${'$'}response.body#/value"}}],"outputs":{"result":"${'$'}steps.one.outputs.value"}}]}""",
        ))
        application {
            install(Authentication)
            install(ContentNegotiation) { json() }
            routing {
                workflowExecutionEndpoint(1024, { _, _ -> workflow to listOf(APIServiceDTO(id = "provider", serviceDefinition = definition)) }, executor::executeWorkflow)
            }
        }
        try {
            val response = client.post("/workflows/doc/wf/execute") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\\\"result\\\":7"))
            assertEquals(1, calls)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `invisible workflow never invokes execution`() = testApplication {
        var called = false
        application {
            install(Authentication)
            routing {
                workflowExecutionEndpoint(1024, { _, _ -> null }, { _, _, _, _ -> called = true; error("unreachable") })
            }
        }
        val response = client.post("/workflows/hidden/wf/execute") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(false, called)
    }
}
