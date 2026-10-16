package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.synapse.model.Catalyst
import cloud.mallne.dicentra.synapse.model.Database
import cloud.mallne.dicentra.synapse.model.Security
import cloud.mallne.dicentra.synapse.model.SecurityGroups
import cloud.mallne.dicentra.synapse.model.Server
import cloud.mallne.dicentra.synapse.model.ServerCors
import cloud.mallne.dicentra.synapse.model.SynapseConfig
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import cloud.mallne.dicentra.synapse.routes.catalystEndpoint
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.ServiceMethods
import cloud.mallne.dicentra.aviator.model.AviatorServiceUtils
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.openapi.*
import io.ktor.openapi.ReferenceOr.Value
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Opt-in R2DBC proof; provide CATALYST_TEST_DATABASE_URL to run against a disposable PostgreSQL database. */
class DatabaseTransactionBoundaryIntegrationTest {
    @Test
    fun `database lookup transaction is finished before the next action transaction begins`() = runBlocking {
        val url = System.getenv("CATALYST_TEST_DATABASE_URL")
        assumeTrue("Set CATALYST_TEST_DATABASE_URL to run the PostgreSQL integration check", !url.isNullOrBlank())

        val database = DatabaseService(
            SynapseConfig(
                security = Security(groups = SecurityGroups()),
                data = Database(
                    url = requireNotNull(url),
                    user = System.getenv("CATALYST_TEST_DATABASE_USER") ?: "postgres",
                    password = System.getenv("CATALYST_TEST_DATABASE_PASSWORD") ?: "postgres",
                    schema = "public",
                ),
                server = Server(cors = ServerCors()),
                catalyst = Catalyst(),
            ),
        )

        val action = testAction()
        var resolutionTransactionId: Long? = null
        var executionTransactionId: Long? = null
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    catalystEndpoint(
                        resolveActions = { _, _ ->
                            resolutionTransactionId = database { transactionId() }
                            listOf(action)
                        },
                        executeActions = { _, _ ->
                            // This callback models provider invocation; it must not inherit the lookup transaction.
                            executionTransactionId = database { transactionId() }
                            JsonArray(listOf(JsonPrimitive("ok")))
                        },
                    )
                }
            }

            val response = client.post("/catalyst/${CatalystActionSelector.encode(action.locator)}") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            response.bodyAsText()
        }

        assertNotEquals(resolutionTransactionId, executionTransactionId)
    }

    private fun testAction(): ResolvedCatalystAction {
        val locator = ServiceLocator("db-transaction-boundary", ServiceMethods.GATHER)
        val operation = Operation.build {
            operationId = "BoundaryCheck"
            `x-dicentra-aviator-serviceDelegateCall` = locator
        }
        val definition = OpenApiDoc.build {
            `x-dicentra-aviator` = AviatorExtensionSpec.SpecVersion
            servers { server("https://provider.example") }
            info = OpenApiInfo(title = "Boundary check", version = "1")
        }.copy(paths = mapOf("/action" to Value(PathItem(get = operation))))
        val route = AviatorServiceUtils.extractServiceLocators(definition).single().second
        return ResolvedCatalystAction(APIServiceDTO(serviceDefinition = definition), route, locator)
    }

    private suspend fun org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction.transactionId(): Long =
        requireNotNull(requireNotNull(exec("SELECT txid_current()") { row ->
            row.get(0, Long::class.javaObjectType)
        }).toList().single())
}
