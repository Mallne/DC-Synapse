package cloud.mallne.dicentra.synapse.routes

import cloud.mallne.dicentra.synapse.model.SynapseConfig
import cloud.mallne.dicentra.synapse.model.User
import cloud.mallne.dicentra.synapse.service.DatabaseService
import cloud.mallne.dicentra.synapse.service.ScopeService
import cloud.mallne.dicentra.synapse.service.StoredWorkflow
import cloud.mallne.dicentra.synapse.service.WorkflowDBService
import cloud.mallne.dicentra.synapse.service.APIDBService
import cloud.mallne.dicentra.synapse.service.CatalystExecutor
import cloud.mallne.dicentra.synapse.service.CatalystAggregateLimitException
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import cloud.mallne.dicentra.aviator.orchestration.model.ExecutionResult
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.ServiceMethods
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.openapi.*
import io.ktor.openapi.jsonSchema
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.koin.ktor.ext.inject

/** Workflow visibility and publication use the same scope model as service definitions. */
fun Application.workflows() {
    val workflows by inject<WorkflowDBService>()
    val scopes by inject<ScopeService>()
    val db by inject<DatabaseService>()
    val config by inject<SynapseConfig>()
    val apiService by inject<APIDBService>()
    val executor by inject<CatalystExecutor>()
    routing {
        authenticate(optional = true) {
            get("/workflows") {
                val user = call.authentication.principal<User>()
                if (user != null && !user.valid) return@get call.respond(HttpStatusCode.NotFound)
                val visible = db {
                    user?.attachScopes(scopes)
                    workflows.listVisible(user?.scopes?.keys.orEmpty())
                }
                call.respond(visible)
            }.describe {
                `x-dicentra-aviator-serviceDelegateCall` =
                    ServiceLocator("${config.server.baseLocator}WorkflowDiscovery", ServiceMethods.GATHER)
                summary = "Discover visible Arazzo workflow documents"
                operationId = "WorkflowDiscovery"
                responses {
                    HttpStatusCode.OK {
                        schema = jsonSchema<List<StoredWorkflow>>()
                    }
                }
            }
            get("/workflows/{id}") {
                val user = call.authentication.principal<User>()
                if (user != null && !user.valid) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.NotFound)
                val workflow = db {
                    user?.attachScopes(scopes)
                    workflows.readVisible(id, user?.scopes?.keys.orEmpty())
                }
                if (workflow == null) call.respond(HttpStatusCode.NotFound)
                else call.respond(workflow)
            }
        }
        authenticate {
            post("/workflows") {
                val user = call.authentication.principal<User>()
                if (user == null || !user.valid) {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@post
                }
                if (!call.request.contentType().match(ContentType.Application.Json)) {
                    call.respond(HttpStatusCode.UnsupportedMediaType)
                    return@post
                }
                val limit = config.catalyst.maxRequestBytes
                if (call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { it > limit } == true) {
                    call.respond(HttpStatusCode.PayloadTooLarge)
                    return@post
                }
                val workflow = try {
                    val bytes = call.receiveChannel().readBuffer(limit + 1).readByteArray()
                    if (bytes.size.toLong() > limit) {
                        call.respond(HttpStatusCode.PayloadTooLarge)
                        return@post
                    }
                    Json.decodeFromString(StoredWorkflow.serializer(), bytes.decodeToString())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                try {
                    require(!workflow.builtin) { "Builtin workflows are host-managed" }
                    workflows.validate(workflow)
                } catch (_: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                val status = db {
                    user.attachScopes(scopes)
                    val permitted = if (workflow.scope == null) user.access.superAdmin else user.canWriteTo(workflow.scope)
                    when {
                        !permitted -> HttpStatusCode.Forbidden
                        workflows.createIfAbsent(workflow) -> HttpStatusCode.Created
                        else -> HttpStatusCode.Conflict
                    }
                }
                call.respond(status)
            }
        }
        if (config.catalyst.enabled) authenticate(optional = config.catalyst.anonymous) {
            workflowExecutionEndpoint(
                maxRequestBytes = config.catalyst.maxRequestBytes,
                resolve = { id, user ->
                    db {
                        user?.attachScopes(scopes)
                        val workflow = workflows.readVisible(id, user?.scopes?.keys.orEmpty())
                        workflow?.let {
                            val services = apiService.readPublic() +
                                if (user == null) emptyList() else apiService.readForScopes(user.scopes.keys)
                            it to services
                        }
                    }
                },
                execute = executor::executeWorkflow,
            )
        }
    }
}

internal fun Route.workflowExecutionEndpoint(
    maxRequestBytes: Long,
    resolve: suspend (String, User?) -> Pair<StoredWorkflow, List<APIServiceDTO>>?,
    execute: suspend (StoredWorkflow, String, List<APIServiceDTO>, JsonElement) -> ExecutionResult,
) {
    post("/workflows/{id}/{workflowId}/execute") {
        val user = call.authentication.principal<User>()
        if (user != null && !user.valid) return@post call.respond(HttpStatusCode.NotFound)
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val workflowId = call.parameters["workflowId"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val (workflow, services) = resolve(id, user) ?: return@post call.respond(HttpStatusCode.NotFound)
        if (!call.request.contentType().match(ContentType.Application.Json)) {
            return@post call.respond(HttpStatusCode.UnsupportedMediaType)
        }
        if (call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { it > maxRequestBytes } == true) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge)
        }
        val inputs = try {
            val bytes = call.receiveChannel().readBuffer(maxRequestBytes + 1).readByteArray()
            if (bytes.size.toLong() > maxRequestBytes) return@post call.respond(HttpStatusCode.PayloadTooLarge)
            Json.parseToJsonElement(bytes.decodeToString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }
        try {
            call.respond(execute(workflow, workflowId, services, inputs))
        } catch (_: CatalystAggregateLimitException) {
            call.respond(HttpStatusCode.BadGateway)
        } catch (timeout: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw timeout
            call.respond(HttpStatusCode.GatewayTimeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}
