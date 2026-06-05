package cloud.mallne.dicentra.synapse.model

import cloud.mallne.dicentra.synapse.statics.ServiceDefinitionTransformationType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
data class Security(
    val enabled: Boolean = false,
    val issuer: String = "",
    @SerialName("authorization_endpoint")
    val authorizationEndpoint: String = "$issuer/protocol/openid-connect/auth",
    @SerialName("token_endpoint")
    val tokenEndpoint: String = "$issuer/protocol/openid-connect/token",
    @SerialName("introspection_endpoint")
    val introspectionEndpoint: String = "$issuer/protocol/openid-connect/token/introspect",
    val scopes: String = "",
    @SerialName("client_id")
    val clientId: String = "",
    @SerialName("client_secret")
    val clientSecret: String = "",
    val groups: SecurityGroups,
) {
    @OptIn(ExperimentalEncodingApi::class)
    fun encodedCredentials() = Base64.encode("$clientId:$clientSecret".toByteArray())
}

@Serializable
data class SecurityGroups(
    val user: String = "",
    val superadmin: String = "",
)


@Serializable
data class Database(
    val url: String = "",
    val user: String = "",
    val password: String = "",
    val schema: String = "synapse",
    val migrations: List<String> = listOf(),
    val callbacks: List<String> = listOf(),
    @SerialName("auto_create_delta")
    val autoCreateDelta: Boolean = false,
    @SerialName("migration_name")
    val migrationName: String = "V0__create.generated"
)

@Serializable
data class ServerCors(
    val all: Boolean = false,
    val hosts: List<String> = listOf()
)

@Serializable
data class Server(
    val cors: ServerCors,
    val hostname: String = "0.0.0.0",
    @SerialName("tls_enabled")
    val tlsEnabled: Boolean = true,
    val info: String = "DiCentra Synapse",
    val description: String = "A discovery endpoint for Aviator services.",
    @SerialName("base_locator")
    val baseLocator: String = "synapse",
    @SerialName("discovery_exclusions")
    val discoveryExclusions: List<String> = listOf()
)

@Serializable
data class Catalyst(
    val enabled: Boolean = true,
    val anonymous: Boolean = true,
    @SerialName("server_name")
    val serverName: String = "0.0.0.0",
    @SerialName("tls_enabled")
    val tlsEnabled: Boolean = true,
    val title: String = "Synapse Catalyst",
    val description: String = "Make Requests to a stored Service in a single Tenant setup."
)

@Serializable
data class SynapseConfig(
    val security: Security,
    val data: Database,
    val server: Server,
    val catalyst: Catalyst,
    val preferredTransform: ServiceDefinitionTransformationType = ServiceDefinitionTransformationType.Native
) {
    init {
        require(preferredTransform != ServiceDefinitionTransformationType.Auto) { "Auto is not an allowed preferred transform" }
        require(preferredTransform.canUse(this)) { "Preferred transform is not allowed" }
    }
}