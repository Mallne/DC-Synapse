package cloud.mallne.dicentra.synapse.model

import cloud.mallne.dicentra.synapse.statics.Client
import io.ktor.client.call.*
import io.ktor.client.request.*
import org.slf4j.LoggerFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class OAuthConfig(
    val enabled: Boolean = true,
    val issuer: String,
    val scopes: String,
    val clientId: String,
    val clientSecret: String,
    val groups: Groups
) {
    constructor(configuration: Configuration) : this(
        enabled = configuration.security.enabled,
        issuer = configuration.security.issuer,
        scopes = configuration.security.scopes,
        clientId = configuration.security.clientId,
        clientSecret = configuration.security.clientSecret,
        groups = Groups(
            user = configuration.security.groups.user,
            admin = configuration.security.groups.admin,
            superAdmin = configuration.security.groups.superAdmin
        )
    )

    lateinit var oidcConfig: OIDCConfig

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

    data class Groups(
        val user: String,
        val admin: String,
        val superAdmin: String,
    )

    companion object {
        private val log = LoggerFactory.getLogger(OAuthConfig::class.java)
    }
}