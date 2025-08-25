package cloud.mallne.dicentra.synapse.routes

import cloud.mallne.dicentra.synapse.model.Configuration
import cloud.mallne.dicentra.synapse.model.DiscoveryResponse
import cloud.mallne.dicentra.synapse.model.User
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO.Companion.transform
import cloud.mallne.dicentra.synapse.service.APIDBService
import cloud.mallne.dicentra.synapse.service.CatalystGenerator
import cloud.mallne.dicentra.synapse.statics.ServiceDefinitionGroupRule
import cloud.mallne.dicentra.synapse.statics.ServiceDefinitionTransformationType
import cloud.mallne.dicentra.synapse.statics.verify
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.toList
import org.koin.ktor.ext.inject

fun Application.catalyst() {
    val config by inject<Configuration>()
    val catalystGenerator by inject<CatalystGenerator>()
    val apiService by inject<APIDBService>()
    routing {
        authenticate(optional = true) {
            get("/catalyst") {
                val user: User? = call.authentication.principal()
                    val services = apiService.readPublic().toList().toMutableList()
                    if (user != null) {
                        apiService.readForScopes(user.scopes).collect {
                            services.add(it)
                        }
                    }
                    val transformationType = ServiceDefinitionTransformationType.fromString(
                        call.request.queryParameters["transformationType"]
                            ?: ServiceDefinitionTransformationType.Auto.name
                    )

                    val groupRule = ServiceDefinitionGroupRule.fromString(
                        call.request.queryParameters["groupRule"]
                            ?: ServiceDefinitionGroupRule.ServiceLocator.name
                    )
                    val definitions = services.transform(
                        requestedTransformationType = transformationType,
                        requestedRule = groupRule,
                        catalystGenerator = catalystGenerator,
                    )
                    val response = DiscoveryResponse(
                        user,
                        definitions,
                    )
                    call.respond(response)
            }
        }
    }
}