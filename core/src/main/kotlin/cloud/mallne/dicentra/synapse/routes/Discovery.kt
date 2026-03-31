package cloud.mallne.dicentra.synapse.routes

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.ServiceMethods
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.synapse.model.Configuration
import cloud.mallne.dicentra.synapse.model.DiscoveryRequest
import cloud.mallne.dicentra.synapse.model.DiscoveryResponse
import cloud.mallne.dicentra.synapse.model.User
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO.Companion.transform
import cloud.mallne.dicentra.synapse.service.APIDBService
import cloud.mallne.dicentra.synapse.service.CatalystGenerator
import cloud.mallne.dicentra.synapse.service.DatabaseService
import cloud.mallne.dicentra.synapse.service.DiscoveryGenerator
import cloud.mallne.dicentra.synapse.service.DiscoveryGenerator.Companion.bearer
import cloud.mallne.dicentra.synapse.service.ScopeService
import cloud.mallne.dicentra.synapse.statics.ServiceDefinitionGroupRule
import cloud.mallne.dicentra.synapse.statics.ServiceDefinitionTransformationType
import cloud.mallne.dicentra.synapse.statics.verify
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.*
import io.ktor.utils.io.*
import org.koin.ktor.ext.inject

/**
 * Configures the discovery-related endpoints for the application. This method
 * sets up a routing structure for handling service discovery, retrieval, creation,
 * updates, and deletions.
 *
 * The following routes are defined:
 *
 * - A GET endpoint to fetch public and user-specific services. If a user is
 *   authenticated, their scopes are used to filter accessible services. Supports
 *   optional authentication for public data access.
 *
 * - A GET endpoint at `/services` to retrieve all available services in the system.
 *   This route requires authentication and admin or superadmin privileges.
 *
 * - A GET endpoint at `/services/{id}` to retrieve a specific service by its ID.
 *   This route requires authentication and enforces scope restrictions for access.
 *
 * - A GET endpoint at `/services/scope/{scope}` to retrieve services linked to a
 *   specific scope. This route requires authentication and verifies that the user
 *   belongs to the specified scope or is a superadmin.
 *
 * - A POST endpoint to create or update service definitions. Scope verification
 *   ensures the user has adequate permissions to publish or modify services.
 *
 * - A DELETE endpoint at `/services/{id}` to remove a specific service by its ID.
 *   Deletion is scope-protected, requiring superadmin rights for public services
 *   and appropriate permissions for scoped services.
 *
 * Authentication and authorization are central to all routes, with various levels
 * of access restrictions based on user roles (e.g., admin, superadmin) and scopes.
 * Responses include relevant HTTP status codes and error messages for failed
 * verification checks.
 */
@OptIn(ExperimentalKtorApi::class)
fun Application.discovery() {
    val catalystGenerator by inject<CatalystGenerator>()
    val apiService by inject<APIDBService>()
    val discoveryGenerator by inject<DiscoveryGenerator>()
    val config by inject<Configuration>()
    val scopeService by inject<ScopeService>()
    val db by inject<DatabaseService>()
    routing {
        authenticate(optional = true) {
            get("/services") {
                val user: User? = call.authentication.principal()
                db {
                    user?.attachScopes(scopeService)
                    val services = apiService.readPublic().toMutableList()
                    if (user != null) {
                        services.addAll(apiService.readForScopes(user.scopes.keys))
                    }
                    val transformationType = ServiceDefinitionTransformationType.fromString(
                        call.request.queryParameters["transformationType"]
                            ?: ServiceDefinitionTransformationType.Auto.name
                    )

                    val groupRule = ServiceDefinitionGroupRule.fromString(
                        call.request.queryParameters["groupRule"]
                            ?: ServiceDefinitionGroupRule.ServiceLocator.name
                    )
                    val thisServer = discoveryGenerator.boilerplate() + call.application.routingRoot.descendants()

                    val definitions = services.transform(
                        requestedTransformationType = transformationType,
                        requestedRule = groupRule,
                        catalystGenerator = catalystGenerator,
                    ) + thisServer
                    val response = DiscoveryResponse(
                        user,
                        definitions,
                    )
                    call.respond(response)
                }
            }.describe {
                `x-dicentra-aviator-serviceDelegateCall` =
                    ServiceLocator("${config.server.baseLocator}DiscoveryBundle", ServiceMethods.GATHER)
                summary = "Get all available services"
                operationId = "ServiceDiscovery"
                security {
                    optional()
                    bearer()
                }
            }
        }
        authenticate {
            get("/services/{id}") {
                val id = call.parameters["id"]
                verify(id != null) { HttpStatusCode.BadRequest to "You must enter an ID!" }
                val user: User? = call.authentication.principal()
                db {
                    user?.attachScopes(scopeService)
                    verify(user != null) { HttpStatusCode.Unauthorized to "You need to be Authenticated for this request!" }
                    val inDB = apiService.read(id)
                    verify(inDB != null) { HttpStatusCode.NotFound to "No Service Definition with this ID present!" }
                    verify(inDB.scope == null || user.scopes.contains(inDB.scope)) {
                        HttpStatusCode.Forbidden to "You must be a member of the Scope of the Service Definition you are trying to obtain!"
                    }
                    val discoveryResponse = DiscoveryResponse(
                        user,
                        listOf(inDB.serviceDefinition),
                    )
                    call.respond(discoveryResponse)
                }
            }.describe {
                `x-dicentra-aviator-serviceDelegateCall` =
                    ServiceLocator("${config.server.baseLocator}DiscoveryEndpoint", ServiceMethods.GATHER)
                summary = "Get a specific service definition"
                operationId = "SpecificService"
                security {
                    bearer()
                }
            }

            get("/services/scope/{scope}") {
                val scope = call.parameters["scope"]
                val user: User? = call.authentication.principal()
                db {
                    user?.attachScopes(scopeService)
                    verify(user != null) { HttpStatusCode.Unauthorized to "You need to be Authenticated for this request!" }
                    verify(scope != null) { HttpStatusCode.BadRequest to "You must enter a Scope!" }
                    verify(user.access.superAdmin || user.scopes.contains(scope)) { HttpStatusCode.Forbidden to "You must be a member of the Scope you are trying to obtain!" }
                    val inDB = scope.let { apiService.readForScope(it) }
                    val discoveryResponse = DiscoveryResponse(
                        user,
                        inDB.map { it.serviceDefinition }.toList(),
                    )
                    call.respond(discoveryResponse)
                }
            }.describe {
                `x-dicentra-aviator-serviceDelegateCall` =
                    ServiceLocator("${config.server.baseLocator}DiscoveryScope", ServiceMethods.GATHER)
                summary = "Get all services linked to a specific scope"
                operationId = "ScopedServices"
                security {
                    bearer()
                }
            }

            post<DiscoveryRequest>("/services") { body: DiscoveryRequest ->
                val user: User? = call.authentication.principal()
                db {
                    user?.attachScopes(scopeService)
                    verify(user != null) { HttpStatusCode.Unauthorized to "You need to be Authenticated for this request!" }
                    val publicReq = body.forScope == null
                    if (publicReq) {
                        verify(user.access.superAdmin) { HttpStatusCode.Forbidden to "You need to be a superadmin to publish public Service Definitions!" }
                    }
                    verify(publicReq || user.access.superAdmin || user.isAdminOf(body.forScope)) {
                        HttpStatusCode.Forbidden to "You must be a member of the Scope you are trying to publish the Service Definitions to!"
                    }
                    val inDB = apiService.read(body.id)
                    if (inDB != null) {
                        val inDBScope = inDB.scope
                        verify(user.access.superAdmin || inDBScope == null || user.isAdminOf(inDBScope)) {
                            HttpStatusCode.Forbidden to "The Service Definition with the id: ${body.id} is already in DB and you are not eligible to alter this resource!"
                        }
                        apiService.update(body.toDTO())
                    } else {
                        apiService.create(body.toDTO())
                    }
                    call.respond(body.id)
                }
            }.describe {
                `x-dicentra-aviator-serviceDelegateCall` =
                    ServiceLocator("${config.server.baseLocator}DiscoveryEndpoint", ServiceMethods.UPSERT)
                summary = "Create or update a service definition"
                operationId = "ServiceUpsert"
                security {
                    bearer()
                }
            }

            delete("/services/{id}") {
                val id = call.parameters["id"]
                verify(id != null) { HttpStatusCode.BadRequest to "You must enter an ID!" }
                val user: User? = call.authentication.principal()
                db {
                    user?.attachScopes(scopeService)
                    verify(user != null) { HttpStatusCode.Unauthorized to "You need to be Authenticated for this request!" }
                    val inDB = apiService.read(id)
                    verify(inDB != null) { HttpStatusCode.NotFound to "No Service Definition with this ID present!" }
                    if (inDB.scope == null) {
                        verify(user.access.superAdmin) {
                            HttpStatusCode.Forbidden to "You must be a Superadmin to delete a public Service Definition!"
                        }
                    }
                    verify(user.access.superAdmin || user.isAdminOf(inDB.scope!!)) {
                        HttpStatusCode.Forbidden to "You are not able to delete a public Service Definition!"
                    }
                    apiService.delete(inDB.id)
                    call.respond(inDB.id)
                }
            }.describe {
                `x-dicentra-aviator-serviceDelegateCall` =
                    ServiceLocator("${config.server.baseLocator}DiscoveryEndpoint", ServiceMethods.DELETE)
                summary = "Delete a specific service definition"
                operationId = "DeleteService"
                security {
                    bearer()
                }
            }
        }
    }
}