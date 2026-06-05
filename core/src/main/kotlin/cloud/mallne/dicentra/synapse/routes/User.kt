package cloud.mallne.dicentra.synapse.routes

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.ServiceMethods
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.synapse.model.SynapseConfig
import cloud.mallne.dicentra.synapse.model.User
import cloud.mallne.dicentra.synapse.service.DatabaseService
import cloud.mallne.dicentra.synapse.service.ScopeService
import cloud.mallne.dicentra.synapse.statics.verify
import io.ktor.http.*
import io.ktor.openapi.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.*
import io.ktor.utils.io.*
import org.koin.ktor.ext.inject

/**
 * Registers the `/user` route under the Ktor application's routing.
 * The route is authenticated and allows a user to retrieve their own profile data.
 *
 * The user must be authenticated to access this endpoint. If the authentication is absent or invalid,
 * the route responds with an HTTP 401 Unauthorized status along with an appropriate error message.
 *
 * If authentication succeeds, the route responds with the authenticated user's data.
 */
@OptIn(ExperimentalKtorApi::class)
fun Application.user() {
    val config = environment.config.getAs<SynapseConfig>()
    val scopeService by inject<ScopeService>()
    val db by inject<DatabaseService>()

    routing {
        authenticate {
            get("/user") {
                val user: User? = call.authentication.principal()
                db {
                    user?.attachScopes(scopeService)
                }
                verify(user != null) { HttpStatusCode.Unauthorized to "You need to be Authenticated for this request!" }
                call.respond(user.toDTO())
            }.describe {
                operationId = "Userinformation"
                summary = "Get the current user's profile"
                `x-dicentra-aviator-serviceDelegateCall` =
                    ServiceLocator("${config.server.baseLocator}User", ServiceMethods.GATHER)
                responses {
                    HttpStatusCode.OK {
                        schema = jsonSchema<User.UserDTO>()
                    }
                    HttpStatusCode.Unauthorized {
                        ContentType.Text.Plain()
                    }
                }
            }
        }
    }
}