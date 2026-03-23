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
    private var dbScopes: List<String> = listOf()

    @RequiresTransactionContext
    suspend fun attachScopes(scopeService: ScopeService) {
        dbScopes = scopeService.getUserScopes(username)
    }

    val valid
        get() = !locked && access.user

    val scopes
        get() = dbScopes + userScope

    val userScope
        get() = Types.USER.scope(username).name()

    fun isAdminOf(scopeName: String): Boolean {
        if (access.superAdmin) return true
        if (scopeName == userScope) return true
        return dbScopes.any { Types.ADMIN.canExpandOps(it) && it == Types.ADMIN.scope(scopeName).name() }
    }

    fun canWriteTo(scopeName: String): Boolean {
        if (access.superAdmin) return true
        return dbScopes.any { Types.WRITE.canWrite(it) && it == Types.WRITE.scope(scopeName).name() }
    }

    fun isDirectMember(scopeName: String): Boolean {
        return dbScopes.any { Types.READ.isDirectMember(it) && it == Types.READ.scope(scopeName).name() }
    }

    @Serializable
    data class AccessLevels(
        val user: Boolean = true,
        val superAdmin: Boolean = false,
    )
}
