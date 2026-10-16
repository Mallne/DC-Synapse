package cloud.mallne.dicentra.synapse.config

import cloud.mallne.dicentra.synapse.model.SynapseConfig
import cloud.mallne.dicentra.synapse.di.DI
import io.ktor.server.application.*
import io.ktor.server.config.*
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configureFrameworks(vararg hostModules: Module) {
    install(Koin) {
        slf4jLogger()
        modules(DI, module {
            single { this@configureFrameworks.environment.config.getAs<SynapseConfig>() }
        }, *hostModules)
    }
}
