package cloud.mallne.dicentra.synapse.routes

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.ServiceMethods
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.synapse.model.Configuration
import cloud.mallne.dicentra.synapse.model.User
import cloud.mallne.dicentra.synapse.model.dto.ScopeDTO
import cloud.mallne.dicentra.synapse.service.DatabaseService
import cloud.mallne.dicentra.synapse.service.DiscoveryGenerator.Companion.bearer
import cloud.mallne.dicentra.synapse.service.ScopeService
import cloud.mallne.dicentra.synapse.statics.verify
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.*
import io.ktor.utils.io.*
import org.koin.ktor.ext.inject

@OptIn(ExperimentalKtorApi::class)
fun Application.scopeRoutes() {
    val scopeService by inject<ScopeService>()
    val config by inject<Configuration>()
    val db by inject<DatabaseService>()

    routing {
        authenticate {
            get("/scope/{scope}") {
                val scopeName = call.parameters["scope"]
                verify(scopeName != null) { HttpStatusCode.BadRequest to "You must provide a scope!" }
                val user: User? = call.authentication.principal()
                verify(user != null) { HttpStatusCode.Unauthorized to "You must be authenticated!" }

                db {
                    user.attachScopes(scopeService)

                    val scopeDTO = scopeService.readScope(scopeName) ?: return@db call.respond(HttpStatusCode.NotFound)

                    verify(user.access.superAdmin || user.isDirectMember(scopeDTO.name)) {
                        HttpStatusCode.Forbidden to "You must be a member or admin of this scope!"
                    }

                    call.respond(scopeDTO)
                }
            }.describe {
                operationId = "GetScope"
                `x-dicentra-aviator-serviceDelegateCall` =
                    ServiceLocator("${config.server.baseLocator}Scope", ServiceMethods.GATHER)
                summary = "Get a scope with its members and admins"
                security { bearer() }

            }

            delete("/scope/{scope}") {
                val scopeName = call.parameters["scope"]
                verify(scopeName != null) { HttpStatusCode.BadRequest to "You must provide a scope!" }
                val user: User? = call.authentication.principal()
                verify(user != null) { HttpStatusCode.Unauthorized to "You must be authenticated!" }

                db {
                    user.attachScopes(scopeService)
                    verify(user.access.superAdmin) { HttpStatusCode.Forbidden to "Only superadmins can delete scopes!" }

                    scopeService.deleteScope(scopeName)
                    call.respond(HttpStatusCode.NoContent)
                }
            }.describe {
                operationId = "DeleteScope"
                `x-dicentra-aviator-serviceDelegateCall` =
                    ServiceLocator("${config.server.baseLocator}Scope", ServiceMethods.DELETE)
                summary = "Delete a scope - requires superadmin"
                security { bearer() }
            }

            patch<ScopeDTO>("/scope/{scope}") { body ->
                val scopeName = call.parameters["scope"]
                verify(scopeName != null) { HttpStatusCode.BadRequest to "You must provide a scope!" }
                val user: User? = call.authentication.principal()
                verify(user != null) { HttpStatusCode.Unauthorized to "You must be authenticated!" }

                db {
                    user.attachScopes(scopeService)
                    verify(user.access.superAdmin || user.isAdminOf(scopeName)) {
                        HttpStatusCode.Forbidden to "You must be an admin of this scope to update members!"
                    }

                    scopeService.createScope(body)
                    val updated = scopeService.readScope(scopeName) ?: return@db call.respond(HttpStatusCode.NotFound)
                    call.respond(updated)
                }
            }.describe {
                operationId = "UpdateScopeAttaches"
                summary = "Update the attaches (members) of a scope - requires scope admin"
                `x-dicentra-aviator-serviceDelegateCall` =
                    ServiceLocator("${config.server.baseLocator}Scope", ServiceMethods.UPSERT)
                security { bearer() }
            }

            post<ScopeDTO>("/scope") { body ->
                val user: User? = call.authentication.principal()
                verify(user != null) { HttpStatusCode.Unauthorized to "You must be authenticated!" }

                db {
                    user.attachScopes(scopeService)
                    verify(user.access.superAdmin) {
                        HttpStatusCode.Forbidden to "Only superadmins can create or update scopes using POST Requests!"
                    }

                    scopeService.createScope(body)
                    call.respond(HttpStatusCode.OK)
                }
            }.describe {
                operationId = "CreateScope"
                `x-dicentra-aviator-serviceDelegateCall` =
                    ServiceLocator("${config.server.baseLocator}Scope", ServiceMethods.CREATE)
                summary = "Create a new scope - requires superadmin"
                security { bearer() }
            }
        }
    }
}
