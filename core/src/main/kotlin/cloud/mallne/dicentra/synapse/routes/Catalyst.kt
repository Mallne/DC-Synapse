package cloud.mallne.dicentra.synapse.routes

import cloud.mallne.dicentra.synapse.model.User
import cloud.mallne.dicentra.synapse.model.SynapseConfig
import cloud.mallne.dicentra.synapse.service.APIDBService
import cloud.mallne.dicentra.synapse.service.DatabaseService
import cloud.mallne.dicentra.synapse.service.ScopeService
import cloud.mallne.dicentra.synapse.service.CatalystActionResolver
import cloud.mallne.dicentra.synapse.service.CatalystActionSelector
import cloud.mallne.dicentra.synapse.service.CatalystExecutor
import cloud.mallne.dicentra.synapse.service.ResolvedCatalystAction
import cloud.mallne.dicentra.synapse.service.MissingCatalystPluginException
import cloud.mallne.dicentra.synapse.service.CatalystUpstreamFailureException
import cloud.mallne.dicentra.synapse.service.CatalystPartialExecutionException
import cloud.mallne.dicentra.synapse.service.CatalystActionNotFoundException
import cloud.mallne.dicentra.synapse.service.CatalystInvalidRequestException
import cloud.mallne.dicentra.synapse.service.CatalystExecutionTimeoutException
import cloud.mallne.dicentra.synapse.service.CatalystTargetLimitException
import cloud.mallne.dicentra.synapse.service.CatalystAggregateLimitException
import cloud.mallne.dicentra.synapse.service.CatalystOutboundPolicyException
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystRequest
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import org.koin.ktor.ext.inject

fun Application.catalyst() {
    val config by inject<SynapseConfig>()
    val apiService by inject<APIDBService>()
    val scopeService by inject<ScopeService>()
    val db by inject<DatabaseService>()
    val resolver by inject<CatalystActionResolver>()
    val executor by inject<CatalystExecutor>()
    monitor.subscribe(ApplicationStopped) { executor.close() }
    routing {
        authenticate(optional = config.catalyst.anonymous) {
            catalystEndpoint(
                resolveActions = { locator, user ->
                    val visibleServices = db {
                        user?.attachScopes(scopeService)
                        apiService.readPublic() + if (user == null) emptyList() else apiService.readForScopes(user.scopes.keys)
                    }
                    resolver.resolve(locator, visibleServices)
                },
                executeActions = executor::execute,
                maxRequestBytes = config.catalyst.maxRequestBytes,
            )
        }
    }
}

internal fun Route.catalystEndpoint(
    resolveActions: suspend (ServiceLocator, User?) -> List<ResolvedCatalystAction>,
    executeActions: suspend (List<ResolvedCatalystAction>, CatalystRequest) -> kotlinx.serialization.json.JsonElement,
    maxRequestBytes: Long = 1_048_576,
) {
    post("/catalyst/{selector}") {
        val locator = CatalystActionSelector.decode(call.parameters["selector"] ?: "")
        if (locator == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        val user: User? = call.authentication.principal()
        if (user != null && !user.valid) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        val actions = resolveActions(locator, user)
        if (actions.isEmpty()) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        val request = try {
            if (!call.request.contentType().match(io.ktor.http.ContentType.Application.Json)) {
                call.respond(HttpStatusCode.UnsupportedMediaType)
                return@post
            }
            val declaredLength = call.request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > maxRequestBytes) {
                call.respond(HttpStatusCode.PayloadTooLarge)
                return@post
            }
            val bytes = call.receiveChannel().readBuffer(maxRequestBytes + 1).readByteArray()
            if (bytes.size.toLong() > maxRequestBytes) {
                call.respond(HttpStatusCode.PayloadTooLarge)
                return@post
            }
            Json.decodeFromString(CatalystRequest.serializer(), bytes.decodeToString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        try {
            call.respond(executeActions(actions, request))
        } catch (_: MissingCatalystPluginException) {
            call.respond(HttpStatusCode.ServiceUnavailable, JsonObject(mapOf("error" to JsonPrimitive("Action unavailable"))))
        } catch (_: CatalystUpstreamFailureException) {
            call.respond(HttpStatusCode.BadGateway, JsonObject(mapOf("error" to JsonPrimitive("Upstream execution failed"))))
        } catch (_: CatalystInvalidRequestException) {
            call.respond(HttpStatusCode.BadRequest)
        } catch (_: CatalystPartialExecutionException) {
            call.respond(HttpStatusCode.BadGateway, JsonObject(mapOf("error" to JsonPrimitive("One or more upstream executions failed"))))
        } catch (_: CatalystExecutionTimeoutException) {
            call.respond(HttpStatusCode.GatewayTimeout, JsonObject(mapOf("error" to JsonPrimitive("Catalyst execution timed out"))))
        } catch (_: CatalystTargetLimitException) {
            call.respond(HttpStatusCode.PayloadTooLarge, JsonObject(mapOf("error" to JsonPrimitive("Too many selected services"))))
        } catch (_: CatalystAggregateLimitException) {
            call.respond(HttpStatusCode.BadGateway, JsonObject(mapOf("error" to JsonPrimitive("Aggregated response exceeds configured limit"))))
        } catch (_: CatalystOutboundPolicyException) {
            call.respond(HttpStatusCode.BadGateway, JsonObject(mapOf("error" to JsonPrimitive("Provider destination rejected by outbound policy"))))
        } catch (_: CatalystActionNotFoundException) {
            call.respond(HttpStatusCode.NotFound)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, JsonObject(mapOf("error" to JsonPrimitive("Action execution failed"))))
        }
    }
}
