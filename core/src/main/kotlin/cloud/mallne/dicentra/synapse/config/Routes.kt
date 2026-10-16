package cloud.mallne.dicentra.synapse.config

import cloud.mallne.dicentra.synapse.model.SynapseConfig
import cloud.mallne.dicentra.synapse.routes.catalyst
import cloud.mallne.dicentra.synapse.routes.discovery
import cloud.mallne.dicentra.synapse.routes.scopeRoutes
import cloud.mallne.dicentra.synapse.routes.user
import io.ktor.server.application.*
import io.ktor.server.config.*

fun Application.routes() {
    discovery()
    if (environment.config.getAs<SynapseConfig>().catalyst.enabled) {
        catalyst()
    }
    scopeRoutes()
    user()
}
