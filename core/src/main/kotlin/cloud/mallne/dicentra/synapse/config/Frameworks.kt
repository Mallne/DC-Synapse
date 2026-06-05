package cloud.mallne.dicentra.synapse.config

import cloud.mallne.dicentra.synapse.model.SynapseConfig
import io.ktor.server.application.*
import io.ktor.server.config.*
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configureFrameworks() {
    install(Koin) {
        slf4jLogger()
        modules(module {
            single { this@configureFrameworks.environment.config.getAs<SynapseConfig>() }
        })
    }
}
