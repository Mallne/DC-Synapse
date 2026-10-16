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
    @SerialName("max_targets")
    val maxTargets: Int = 32,
    @SerialName("max_concurrent_targets")
    val maxConcurrentTargets: Int = 4,
    @SerialName("max_request_bytes")
    val maxRequestBytes: Long = 1_048_576,
    @SerialName("max_response_bytes")
    val maxResponseBytes: Long = 16_777_216,
    @SerialName("max_aggregate_response_bytes")
    val maxAggregateResponseBytes: Long = 33_554_432,
    @SerialName("allowed_hosts")
    val allowedHosts: String = "",
    /** Allow exact hosts from public service definitions, which only superadmins may publish. */
    @SerialName("allow_public_service_hosts")
    val allowPublicServiceHosts: Boolean = false,
    @SerialName("per_target_timeout_millis")
    val perTargetTimeoutMillis: Long = 15_000,
    @SerialName("overall_timeout_millis")
    val overallTimeoutMillis: Long = 30_000,
    @SerialName("server_name")
    val serverName: String = "0.0.0.0",
    @SerialName("tls_enabled")
    val tlsEnabled: Boolean = true,
    val title: String = "Synapse Catalyst",
    val description: String = "Make Requests to a stored Service in a single Tenant setup."
) {
    init {
        require(maxTargets > 0) { "Catalyst max_targets must be positive" }
        require(maxConcurrentTargets > 0) { "Catalyst max_concurrent_targets must be positive" }
        require(maxRequestBytes in 1..Int.MAX_VALUE.toLong()) { "Catalyst max_request_bytes must be between 1 and Int.MAX_VALUE" }
        require(maxResponseBytes in 1 until Int.MAX_VALUE.toLong()) { "Catalyst max_response_bytes must be between 1 and Int.MAX_VALUE - 1" }
        require(maxAggregateResponseBytes in 1 until Int.MAX_VALUE.toLong()) { "Catalyst max_aggregate_response_bytes must be between 1 and Int.MAX_VALUE - 1" }
        require(perTargetTimeoutMillis > 0) { "Catalyst per_target_timeout_millis must be positive" }
        require(overallTimeoutMillis > 0) { "Catalyst overall_timeout_millis must be positive" }
    }
}

@Serializable
data class Telemetry(
    val endpoint: String = "",
    @SerialName("service_name")
    val serviceName: String = "synapse",
)

@Serializable
data class SynapseConfig(
    val security: Security,
    val data: Database,
    val server: Server,
    val catalyst: Catalyst,
    val preferredTransform: ServiceDefinitionTransformationType = ServiceDefinitionTransformationType.Native,
    val telemetry: Telemetry = Telemetry(),
) {
    init {
        require(preferredTransform != ServiceDefinitionTransformationType.Auto) { "Auto is not an allowed preferred transform" }
        require(preferredTransform.canUse(this)) { "Preferred transform is not allowed" }
    }
}
