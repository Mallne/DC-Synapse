package cloud.mallne.dicentra.synapse.model

import cloud.mallne.dicentra.synapse.service.ScopeService
import cloud.mallne.dicentra.synapse.service.ScopeService.Companion.Types
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val name: String,
    val email: String,
    val username: String,
    val locked: Boolean = false,
    val access: AccessLevels,
) {
    private var dbScopes: Map<String, Types> = mapOf()

    @RequiresTransactionContext
    suspend fun attachScopes(scopeService: ScopeService) {
        dbScopes = scopeService.getUserScopes(username)
    }

    val valid
        get() = !locked && access.user

    val scopes
        get() = dbScopes + userScope

    val userScope
        get() = username to Types.USER

    fun isAdminOf(scopeName: String): Boolean {
        if (access.superAdmin) return true
        return dbScopes.any { it.value.canExpandOps && scopeName == it.key }
    }

    fun canWriteTo(scopeName: String): Boolean {
        if (access.superAdmin) return true
        return dbScopes.any { it.value.canWrite && scopeName == it.key }
    }

    fun isDirectMember(scopeName: String): Boolean {
        return dbScopes.any { scopeName == it.key }
    }

    @Serializable
    data class AccessLevels(
        val user: Boolean = true,
        val superAdmin: Boolean = false,
    )
}
