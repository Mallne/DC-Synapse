package cloud.mallne.dicentra.synapse.model

import cloud.mallne.dicentra.synapse.service.ScopeService
import cloud.mallne.dicentra.synapse.service.ScopeService.Companion.Types
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

data class User(
    val name: String,
    val email: String,
    val username: String,
    val locked: Boolean = false,
    val access: AccessLevels,
) {
    @Transient
    private var dbScopes: Map<String, Types> = mapOf()

    @RequiresTransactionContext
    suspend fun attachScopes(scopeService: ScopeService) {
        dbScopes = scopeService.getUserScopes(username)
    }

    @EncodeDefault
    val valid
        get() = !locked && access.user

    @EncodeDefault
    val scopes
        get() = dbScopes + userScope

    @EncodeDefault
    val userScope
        get() = "user:$username" to Types.USER

    fun isAdminOf(scopeName: String): Boolean {
        if (access.superAdmin) return true
        return dbScopes.any { it.value.canExpandOps && scopeName == it.key }
    }

    fun canWriteTo(scopeName: String): Boolean {
        if (access.superAdmin) return true
        return scopes.any { it.value.canWrite && scopeName == it.key }
    }

    fun isDirectMember(scopeName: String): Boolean {
        return scopes.any { scopeName == it.key }
    }

    fun toDTO() = UserDTO(
        name = name,
        email = email,
        username = username,
        locked = locked,
        access = access,
        scopes = scopes,
        userScope = userScope,
        valid = valid
    )

    @Serializable
    data class UserDTO(
        val name: String,
        val email: String,
        val username: String,
        val locked: Boolean = false,
        val access: AccessLevels,
        val scopes: Map<String, Types> = mapOf(),
        val userScope: Pair<String, Types> = "user:$username" to Types.USER,
        val valid: Boolean = !locked && access.user,
    )

    @Serializable
    data class AccessLevels(
        val user: Boolean = true,
        val superAdmin: Boolean = false,
    )
}
