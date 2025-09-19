package cloud.mallne.dicentra.synapse.model

import cloud.mallne.dicentra.synapse.helper.toBooleanish
import cloud.mallne.dicentra.synapse.statics.Client
import cloud.mallne.dicentra.synapse.statics.ServiceDefinitionTransformationType
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class Configuration(
    val application: Application
) {
    val security = SecurityConfiguration(application)
    val data = DatabaseConfiguration(application)
    val server = ServerConfiguration(application)
    val catalyst = CatalystConfiguration(application)
    val preferredTransform: ServiceDefinitionTransformationType = ServiceDefinitionTransformationType.fromString(
        application.environment.config.tryGetString("preferred_transform")
            ?: ServiceDefinitionTransformationType.Native.name
    )

    init {
        require(preferredTransform != ServiceDefinitionTransformationType.Auto) { "Auto is not an allowed preferred transform" }
        require(preferredTransform.canUse(this)) { "Preferred transform is not allowed" }
    }

    companion object Nested {
        class ServerConfiguration(val application: Application) {
            val cors = ServerCorsConfiguration(application)
            val hostname = application.environment.config.tryGetString("server.hostname") ?: "0.0.0.0"
            val tlsEnabled = application.environment.config.tryGetString("server.tls_enabled")?.toBooleanish() ?: true
            val info = application.environment.config.tryGetString("server.info") ?: "DiCentra Synapse"
            val description = application.environment.config.tryGetString("server.description")
                ?: "A discovery endpoint for Aviator services."
            val baseLocator = application.environment.config.tryGetString("server.base_locator") ?: "synapse"
            val discoveryExclusions =
                application.environment.config.tryGetString("server.discovery_exclusions")?.split(",")
                    ?.filter { it.isNotBlank() } ?: listOf()

            companion object Nested {
                class ServerCorsConfiguration(val application: Application) {
                    val all: Boolean =
                        application.environment.config.tryGetString("server.cors.all")?.toBooleanish() ?: false
                    val hosts: List<String> =
                        application.environment.config.tryGetString("server.cors.hosts")?.split(",") ?: listOf()
                }
            }
        }

        class CatalystConfiguration(val application: Application) {
            val enabled = application.environment.config.tryGetString("catalyst.enabled")?.toBooleanish() ?: true
            val anonymous = application.environment.config.tryGetString("catalyst.anonymous")?.toBooleanish() ?: true
            val serverName = application.environment.config.tryGetString("catalyst.server_name")
                ?: application.environment.config.tryGetString("server.hostname") ?: "0.0.0.0"
            val tlsEnabled = application.environment.config.tryGetString("catalyst.tls_enabled")?.toBooleanish() ?: true
            val title = application.environment.config.tryGetString("catalyst.title") ?: "Synapse Catalyst"
            val description = application.environment.config.tryGetString("catalyst.description")
                ?: "Make Requests to a stored Service in a single Tenant setup."
        }

        class SecurityConfiguration(val application: Application) {
            val enabled: Boolean =
                application.environment.config.tryGetString("security.enabled")?.toBooleanish() ?: false
            val issuer = application.environment.config.tryGetString("security.issuer") ?: ""
            val scopes = application.environment.config.tryGetString("security.scopes") ?: ""
            val clientId = application.environment.config.tryGetString("security.client_id") ?: ""
            val clientSecret = application.environment.config.tryGetString("security.client_secret") ?: ""
            val groups = SecurityGroupsConfiguration(application)

            lateinit var oidcConfig: OIDCConfig

            init {
                runBlocking {
                    configure()
                }
            }

            @OptIn(ExperimentalEncodingApi::class)
            fun encodedCredentials() = Base64.encode("$clientId:$clientSecret".toByteArray())

            suspend fun configure() {
                if (enabled) {
                    val client = Client()
                    oidcConfig = client.get("$issuer/.well-known/openid-configuration").body()
                    log.info("Using oidc config: $oidcConfig")
                } else {
                    log.info("Authentication is disabled")
                }
            }

            companion object Nested {
                private val log = LoggerFactory.getLogger(SecurityConfiguration::class.java)

                class SecurityGroupsConfiguration(val application: Application) {
                    val user = application.environment.config.tryGetString("security.groups.user") ?: ""
                    val admin = application.environment.config.tryGetString("security.groups.admin") ?: ""
                    val superAdmin = application.environment.config.tryGetString("security.groups.superadmin") ?: ""
                }
            }
        }

        class DatabaseConfiguration(val application: Application) {
            val url = application.environment.config.tryGetString("data.url") ?: ""
            val user = application.environment.config.tryGetString("data.user") ?: ""
            val password = application.environment.config.tryGetString("data.password") ?: ""
            val schema = application.environment.config.tryGetString("data.schema") ?: "synapse"
            val migrationDirectory = application.environment.config.tryGetStringList("data.migrations") ?: listOf()
            val autoCreateDelta =
                application.environment.config.tryGetString("data.auto_create_delta")?.toBooleanish() ?: false
            val migrationName =
                application.environment.config.tryGetString("data.migration_name") ?: "V0__create.generated"
        }
    }
}