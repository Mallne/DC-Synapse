package cloud.mallne.dicentra.synapse.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
data class IntrospectionResponse(
    val exp: Long,
    val iat: Long,
    val scope: String,
    val acr: String,
    @SerialName("email_verified")
    val emailVerified: Boolean = false,
    val name: String,
    val groups: List<String> = listOf(),
    @SerialName("preferred_username")
    val preferredUsername: String,
    val active: Boolean = false,
    @SerialName("token_type")
    val tokenType: String,
    val email: String,
    @SerialName("given_name")
    val givenName: String,
    @SerialName("family_name")
    val familyName: String,
    @SerialName("client_id")
    val clientId: String,
) {
    fun toUser(config: Security): User {
        val acl = User.AccessLevels(
            user = groups.contains(config.groups.user),
            superAdmin = groups.contains(config.groups.superadmin)
        )
        val locked = config.enabled && !active && !emailVerified
        return User(name, email, preferredUsername, locked, acl)
    }
}