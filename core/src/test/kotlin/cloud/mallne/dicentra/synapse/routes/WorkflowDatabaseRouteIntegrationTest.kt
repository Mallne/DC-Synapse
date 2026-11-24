package cloud.mallne.dicentra.synapse.routes

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.synapse.model.*
import cloud.mallne.dicentra.synapse.model.Security
import cloud.mallne.dicentra.synapse.model.Server
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import cloud.mallne.dicentra.synapse.service.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.openapi.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.junit.Assume.assumeTrue
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.net.InetAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowDatabaseRouteIntegrationTest {
    @Test
    fun `authenticated scoped workflow loads from postgres and executes its visible service`() = runBlocking {
        val url = System.getenv("CATALYST_TEST_DATABASE_URL")
        assumeTrue("Set CATALYST_TEST_DATABASE_URL to a disposable database", !url.isNullOrBlank())
        val username = System.getenv("CATALYST_TEST_DATABASE_USER") ?: "postgres"
        val password = System.getenv("CATALYST_TEST_DATABASE_PASSWORD") ?: "postgres"
        Flyway.configure().dataSource("jdbc:$url", username, password)
            .locations("classpath:synapse/migration").load().migrate()
        val config = SynapseConfig(
            security = Security(groups = SecurityGroups()),
            data = Database(url = requireNotNull(url), user = username, password = password, schema = "public"),
            server = Server(cors = ServerCors()),
            catalyst = Catalyst(anonymous = true),
        )
        val db = DatabaseService(config)
        val services = APIDBService()
        val store = WorkflowDBService()
        val scopes = ScopeService()
        db { SchemaUtils.create(APIDBService.APIServiceData, ScopeService.Scopes) }
        val serviceId = UUID.randomUUID().toString()
        val documentId = UUID.randomUUID().toString()
        val api = OpenApiDoc.build {
            `x-dicentra-aviator` = AviatorExtensionSpec.SpecVersion
            info = OpenApiInfo(title = "scoped provider", version = "1")
            servers { server("https://provider.example") }
        }.copy(paths = mapOf(
            "/data" to ReferenceOr.Value(PathItem(
                get = Operation.build {
                    operationId = "getData"
                    `x-dicentra-aviator-serviceDelegateCall` = ServiceLocator("data")
                },
                post = Operation.build {
                    operationId = "postData"
                    `x-dicentra-aviator-serviceDelegateCall` = ServiceLocator("data")
                }.copy(requestBody = ReferenceOr.Value(RequestBody(
                    required = true,
                    content = mapOf(ContentType.Application.Json to MediaType(schema = ReferenceOr.Value(JsonSchema(type = JsonType.OBJECT)))),
                ))),
            )),
        ))
        val document = Json.parseToJsonElement(
            """{"arazzo":"1.1.0","info":{"title":"test","version":"1"},"sourceDescriptions":[{"name":"api","url":"urn:synapse:service:$serviceId","type":"openapi"}],"workflows":[{"workflowId":"wf","steps":[{"stepId":"one","operationId":"${'$'}sourceDescriptions.api.getData","outputs":{"value":"${'$'}response.body#/value"}}],"outputs":{"result":"${'$'}steps.one.outputs.value"}},{"workflowId":"submit","steps":[{"stepId":"one","operationId":"${'$'}sourceDescriptions.api.postData","requestBody":{"message":"hello"}}]}]}""",
        )
        db {
            services.create(APIServiceDTO(id = serviceId, serviceDefinition = api, scope = "user:alice"))
            store.create(StoredWorkflow(documentId, "user:alice", document))
        }
        var calls = 0
        var postedBody: String? = null
        val executor = CatalystExecutor(listOf(JsonAdapterCatalystContribution()),
            client = HttpClient(MockEngine {
                calls++
                if (it.method == HttpMethod.Post) {
                    postedBody = (it.body as TextContent).text
                }
                respond("{\"value\":7}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }), allowedHosts = listOf("provider.example"), resolveProviderHost = { listOf(InetAddress.getByName("8.8.8.8")) })
        try {
            testApplication {
                application {
                    install(Koin) {
                        modules(module {
                            single { config }
                            single { db }
                            single { services }
                            single { store }
                            single { scopes }
                            single { executor }
                        })
                    }
                    install(ContentNegotiation) { json() }
                    install(Authentication) {
                        bearer {
                            authenticate { credential ->
                                if (credential.token == "alice") User("Alice", "a@example.test", "alice", access = User.AccessLevels())
                                else null
                            }
                        }
                    }
                    workflows()
                }
                val anonymousWorkflows = client.get("/workflows")
                assertEquals(HttpStatusCode.OK, anonymousWorkflows.status)
                assertTrue(!anonymousWorkflows.bodyAsText().contains(documentId))
                val visibleWorkflows = client.get("/workflows") { bearerAuth("alice") }
                assertEquals(HttpStatusCode.OK, visibleWorkflows.status)
                assertTrue(visibleWorkflows.bodyAsText().contains(documentId))
                val duplicate = client.post("/workflows") {
                    bearerAuth("alice")
                    contentType(ContentType.Application.Json)
                    setBody("""{"id":"$documentId","scope":"user:alice","document":$document}""")
                }
                assertEquals(HttpStatusCode.Conflict, duplicate.status)
                val path = "/workflows/$documentId/wf/execute"
                val anonymous = client.post(path) { contentType(ContentType.Application.Json); setBody("{}") }
                assertEquals(HttpStatusCode.NotFound, anonymous.status)
                assertEquals(0, calls)
                val authorized = client.post(path) {
                    bearerAuth("alice")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                assertEquals(HttpStatusCode.OK, authorized.status)
                assertTrue(authorized.bodyAsText().contains("\\\"result\\\":7"))
                assertEquals(1, calls)
                val submitted = client.post("/workflows/$documentId/submit/execute") {
                    bearerAuth("alice")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                assertEquals(HttpStatusCode.OK, submitted.status)
                assertEquals("""{"message":"hello"}""", postedBody)
            }
        } finally {
            executor.close()
        }
    }
}
