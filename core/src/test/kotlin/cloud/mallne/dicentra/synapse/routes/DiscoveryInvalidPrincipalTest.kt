package cloud.mallne.dicentra.synapse.routes

import cloud.mallne.dicentra.synapse.model.User
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.koin.ktor.plugin.Koin
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryInvalidPrincipalTest {
    @Test
    fun `service discovery rejects locked and unauthorized principals before database access`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "security.groups.user" to "user",
                "security.groups.superadmin" to "admin",
                "data.url" to "",
                "server.cors.all" to "false",
                "catalyst.enabled" to "true",
            )
        }
        application {
            install(Koin)
            install(Authentication) {
                bearer {
                    authenticate { credential ->
                        when (credential.token) {
                            "locked" -> User("Locked", "", "locked", locked = true, access = User.AccessLevels())
                            "no-access" -> User("No access", "", "no-access", access = User.AccessLevels(user = false))
                            else -> null
                        }
                    }
                }
            }
            discovery()
        }

        for (token in listOf("locked", "no-access")) {
            val response = client.get("/services") { bearerAuth(token) }
            assertEquals(HttpStatusCode.NotFound, response.status, token)
        }
    }
}
