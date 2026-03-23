package cloud.mallne.dicentra.synapse.routes

import cloud.mallne.dicentra.aviator.core.ServiceMethods
import cloud.mallne.dicentra.aviator.koas.extensions.ReferenceOr
import cloud.mallne.dicentra.aviator.koas.io.Schema
import cloud.mallne.dicentra.aviator.koas.parameters.Parameter
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.synapse.model.Configuration
import cloud.mallne.dicentra.synapse.model.ScopeCreateRequest
import cloud.mallne.dicentra.synapse.model.ScopeUpdateAttachesRequest
import cloud.mallne.dicentra.synapse.model.User
import cloud.mallne.dicentra.synapse.service.DatabaseService
import cloud.mallne.dicentra.synapse.service.DiscoveryGenerator
import cloud.mallne.dicentra.synapse.service.ScopeService
import cloud.mallne.dicentra.synapse.statics.verify
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.scopeRoutes() {
    val scopeService by inject<ScopeService>()
    val discoveryGenerator by inject<DiscoveryGenerator>()
    val config by inject<Configuration>()
    val db by inject<DatabaseService>()

    discoveryGenerator.memorize {
        path("/scope/{scope}") {
            operation(
                id = "GetScope",
                method = HttpMethod.Get,
                locator = ServiceLocator("${config.server.baseLocator}Scope", ServiceMethods.GATHER),
                authenticationStrategy = DiscoveryGenerator.Companion.AuthenticationStrategy.MANDATORY,
                summary = "Get a scope with its members and admins",
                parameter = listOf(
                    Parameter(
                        name = "scope",
                        input = Parameter.Input.Path,
                        schema = ReferenceOr.value(Schema(type = Schema.Type.Basic.String))
                    )
                )
            )
            operation(
                id = "UpdateScopeAttaches",
                method = HttpMethod.Patch,
                locator = ServiceLocator("${config.server.baseLocator}Scope", ServiceMethods.UPSERT),
                authenticationStrategy = DiscoveryGenerator.Companion.AuthenticationStrategy.MANDATORY,
                summary = "Update the attaches (members) of a scope - requires scope admin",
                parameter = listOf(
                    Parameter(
                        name = "scope",
                        input = Parameter.Input.Path,
                        schema = ReferenceOr.value(Schema(type = Schema.Type.Basic.String))
                    )
                )
            )
            operation(
                id = "DeleteScope",
                method = HttpMethod.Delete,
                locator = ServiceLocator("${config.server.baseLocator}Scope", ServiceMethods.DELETE),
                authenticationStrategy = DiscoveryGenerator.Companion.AuthenticationStrategy.MANDATORY,
                summary = "Delete a scope - requires superadmin",
                parameter = listOf(
                    Parameter(
                        name = "scope",
                        input = Parameter.Input.Path,
                        schema = ReferenceOr.value(Schema(type = Schema.Type.Basic.String))
                    )
                )
            )
        }
        path("/scope") {
            operation(
                id = "CreateScope",
                method = HttpMethod.Post,
                locator = ServiceLocator("${config.server.baseLocator}Scope", ServiceMethods.CREATE),
                authenticationStrategy = DiscoveryGenerator.Companion.AuthenticationStrategy.MANDATORY,
                summary = "Create a new scope - requires superadmin",
            )
        }
    }

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

                    val isMember = scopeDTO.attaches.contains(user.username)
                    val isAdmin = user.isAdminOf(scopeName)
                    verify(user.access.superAdmin || isMember || isAdmin) {
                        HttpStatusCode.Forbidden to "You must be a member or admin of this scope!"
                    }

                    call.respond(scopeDTO)
                }
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
            }

            patch<ScopeUpdateAttachesRequest>("/scope/{scope}") { body ->
                val scopeName = call.parameters["scope"]
                verify(scopeName != null) { HttpStatusCode.BadRequest to "You must provide a scope!" }
                val user: User? = call.authentication.principal()
                verify(user != null) { HttpStatusCode.Unauthorized to "You must be authenticated!" }

                db {
                    user.attachScopes(scopeService)
                    val resolvedScopeName = scopeName
                    verify(user.isAdminOf(resolvedScopeName)) {
                        HttpStatusCode.Forbidden to "You must be an admin of this scope to update members!"
                    }

                    scopeService.updateAttaches(resolvedScopeName, body.attaches)
                    val updated = scopeService.readScope(resolvedScopeName)
                    call.respond(updated!!)
                }
            }

            post<ScopeCreateRequest>("/scope") { body ->
                val user: User? = call.authentication.principal()
                verify(user != null) { HttpStatusCode.Unauthorized to "You must be authenticated!" }

                db {
                    user.attachScopes(scopeService)
                    verify(user.access.superAdmin) { HttpStatusCode.Forbidden to "Only superadmins can create or update scopes!" }

                    scopeService.createScope(body.toDTO())
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }
}
