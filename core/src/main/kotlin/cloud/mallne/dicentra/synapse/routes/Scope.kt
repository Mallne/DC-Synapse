package cloud.mallne.dicentra.synapse.routes

import cloud.mallne.dicentra.aviator.core.ServiceMethods
import cloud.mallne.dicentra.aviator.koas.parameters.Parameter
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.synapse.model.Configuration
import cloud.mallne.dicentra.synapse.model.ScopeRequest
import cloud.mallne.dicentra.synapse.model.User
import cloud.mallne.dicentra.synapse.service.DiscoveryGenerator
import cloud.mallne.dicentra.synapse.service.ScopeService
import cloud.mallne.dicentra.synapse.statics.verify
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.getValue

/**
 * Configures routing for scope-related operations and handles requests for creating, reading, and deleting scopes.
 *
 * This method provides routes for managing scopes with the following endpoints:
 *
 * - **GET /scope/{scope}**: Reads a list of attachments for the specified scope name,
 *   ensuring the authenticated user has sufficient permissions.
 * - **DELETE /scope/{scope}**: Deletes the specified scope name if the authenticated user has admin privileges.
 * - **POST /scope**: Creates a new scope based on the payload, provided it does not already exist
 *   and the authenticated user meets the required permissions.
 *
 * Authorization is enforced for all operations, and users must authenticate before using the endpoints.
 *
 * Request validations are performed to ensure:
 * - Required parameters are present.
 * - The authenticated user has adequate privileges based on the operation and scope.
 *
 * Any validation failures will result in appropriate HTTP error responses.
 */
fun Application.scope() {
    val scopeService by inject<ScopeService>()
    val discoveryGenerator by inject<DiscoveryGenerator>()
    val config by inject<Configuration>()

    discoveryGenerator.memorize {
        path("/scope/{scope}") {
            operation(
                method = HttpMethod.Get,
                locator = ServiceLocator("${config.server.baseLocator}Scope", ServiceMethods.GATHER),
                authenticationStrategy = DiscoveryGenerator.Companion.AuthenticationStrategy.MANDATORY,
                summary = "Get a specific scope",
                parameter = listOf(
                    Parameter(
                        name = "scope",
                        input = Parameter.Input.Path,
                    )
                )
            )
            operation(
                method = HttpMethod.Delete,
                locator = ServiceLocator("${config.server.baseLocator}Scope", ServiceMethods.DELETE),
                authenticationStrategy = DiscoveryGenerator.Companion.AuthenticationStrategy.MANDATORY,
                summary = "Deletes a specific scope",
                parameter = listOf(
                    Parameter(
                        name = "scope",
                        input = Parameter.Input.Path,
                    )
                )
            )
        }
        path("/scope") {
            operation(
                method = HttpMethod.Post,
                locator = ServiceLocator("${config.server.baseLocator}Scope", ServiceMethods.CREATE),
                authenticationStrategy = DiscoveryGenerator.Companion.AuthenticationStrategy.MANDATORY,
                summary = "Creates a new scope",
            )
        }
    }
    routing {
        authenticate {
            get("/scope/{scope}") {
                val scope = call.parameters["scope"]
                verify(scope != null) { HttpStatusCode.BadRequest to "You must provide a scope for this request!" }
                val user: User? = call.authentication.principal()
                verify(user != null) { HttpStatusCode.Unauthorized to "You need to be Authenticated for this request!" }
                verify(user.access.admin || user.access.superAdmin) { HttpStatusCode.Forbidden to "You must be at least admin for this request!" }
                verify(user.access.superAdmin || user.scopes.contains(scope)) { HttpStatusCode.Forbidden to "You must be a member of the Scope you are trying to obtain!" }
                val scopes = scopeService.readForName(scope)
                val response = ScopeRequest(
                    name = scope,
                    attachments = scopes.map { it.attaches }
                )
                call.respond(response)
            }
            delete("/scope/{scope}") {
                val scope = call.parameters["scope"]
                verify(scope != null) { HttpStatusCode.BadRequest to "You must provide a scope for this request!" }
                val user: User? = call.authentication.principal()
                verify(user != null) { HttpStatusCode.Unauthorized to "You need to be Authenticated for this request!" }
                verify(user.access.admin || user.access.superAdmin) { HttpStatusCode.Forbidden to "You must be at least admin for this request!" }
                verify(user.access.superAdmin || user.scopes.contains(scope)) { HttpStatusCode.Forbidden to "You must be a member of the Scope you are trying to obtain!" }
                scopeService.deleteByName(scope)
                call.respond(scope)
            }
            post<ScopeRequest>("/scope") { body ->
                val user: User? = call.authentication.principal()
                verify(user != null) { HttpStatusCode.Unauthorized to "You need to be Authenticated for this request!" }
                verify(user.access.admin || user.access.superAdmin) { HttpStatusCode.Forbidden to "You must be at least admin for this request!" }
                verify(user.access.superAdmin || user.scopes.contains(body.name) || body.attachments.contains(user.userScope)) { HttpStatusCode.Forbidden to "You must be a member of the Scope you are trying to create!" }
                val already = scopeService.readForName(body.name)
                verify(already.isNotEmpty()) { HttpStatusCode.Conflict to "The Scope '${body.name}' already exists!" }
                val scopes = body.toDTO()
                for (scope in scopes) {
                    scopeService.create(scope)
                }
                call.respond(body)
            }
        }
    }
}