package cloud.mallne.dicentra.synapse.config

import cloud.mallne.dicentra.synapse.model.SynapseConfig
import cloud.mallne.dicentra.synapse.statics.Serialization
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.requestvalidation.*

fun Application.configureHTTP() {
    val config = environment.config.getAs<SynapseConfig>()
    install(ForwardedHeaders) // WARNING: for security, do not include this if not behind a reverse proxy
    install(XForwardedHeaders) // WARNING: for security, do not include this if not behind a reverse proxy
    configureCatalystCors(environment.config.corsHosts(), config.server.cors.all)
    install(CachingHeaders) {
        options { call, outgoingContent ->
            when (outgoingContent.contentType?.withoutParameters()) {
                ContentType.Text.CSS -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 24 * 60 * 60))
                else -> null
            }
        }
    }
    install(Compression)
    install(ContentNegotiation) {
        json(Serialization())
    }
    install(RequestValidation) {
    }
    install(AutoHeadResponse)
}

/** Environment-backed YAML values are scalars; HOCON/YAML lists remain supported. */
internal fun ApplicationConfig.corsHosts(): List<String> {
    val value = propertyOrNull("server.cors.hosts") ?: return emptyList()
    val hosts = when (value.type) {
        ApplicationConfigValue.Type.SINGLE -> value.getString().split(',')
        ApplicationConfigValue.Type.LIST -> value.getList()
        else -> throw ApplicationConfigurationException("server.cors.hosts must be a host list or comma-separated string")
    }
    return hosts.map(String::trim).filter(String::isNotEmpty).distinct()
}

/** Shared with endpoint tests so browser preflight exercises the deployment CORS policy. */
internal fun Application.configureCatalystCors(hosts: List<String>, all: Boolean = false) {
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        if (all) {
            anyHost()
        }
        if (hosts.isNotEmpty()) {
            for (string in hosts) {
                allowHost(string)
            }
        }
    }
}
