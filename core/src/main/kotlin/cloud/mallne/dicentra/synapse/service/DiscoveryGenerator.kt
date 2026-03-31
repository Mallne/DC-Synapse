package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.synapse.model.Configuration
import io.ktor.openapi.*
import io.ktor.server.auth.*
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class DiscoveryGenerator(
    @Provided val configuration: Configuration,
) {

    fun boilerplate(): OpenApiDoc {
        val schema = if (configuration.server.tlsEnabled) "https://" else "http://"
        return OpenApiDoc.build {
            `x-dicentra-aviator` = AviatorExtensionSpec.SpecVersion
            servers {
                server(schema + configuration.server.hostname)
            }
            info = OpenApiInfo(
                title = configuration.server.info,
                description = configuration.server.description,
                version = AviatorExtensionSpec.SpecVersion
            )
        }
    }

    companion object {
        fun Security.Builder.bearer(scopes: List<String> = emptyList()) {
            requirement(AuthenticationRouteSelector.DEFAULT_NAME, scopes)
        }
    }
}