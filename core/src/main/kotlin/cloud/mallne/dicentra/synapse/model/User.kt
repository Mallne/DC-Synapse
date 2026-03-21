package cloud.mallne.dicentra.synapse.model

import cloud.mallne.dicentra.synapse.service.ScopeService
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
    private var isAdminOfScopes: Set<String> = emptySet()

    @RequiresTransactionContext
    suspend fun attachScopes(scopeService: ScopeService) {
        val assignments = scopeService.getScopeAssignmentsForUser(username)
        dbScopes = assignments.map { ScopeService.scope(it.scopeName) }
        isAdminOfScopes = assignments.filter { it.isAdmin }.map { it.scopeName }.toSet()
    }

    val valid
        get() = !locked && access.user
    
    val scopes
        get() = dbScopes + userScope + adminScopes
    
    val userScope
        get() = ScopeService.scope(username)
    
    /**
     * Returns admin scopes for scopes this user is admin of.
     * e.g., if user is admin of "settings" scope, returns ["admin:settings"]
     */
    val adminScopes
        get() = isAdminOfScopes.map { ScopeService.adminScope(it) }

    /**
     * Checks if user is admin of a specific scope.
     * SuperAdmin can admin any scope.
     */
    fun isAdminOf(scopeName: String): Boolean = 
        access.superAdmin || isAdminOfScopes.contains(scopeName)

    /**
     * Checks if user has admin privileges (for any scope).
     * SuperAdmin OR has at least one admin:* scope assignment.
     */
    val isAdmin: Boolean
        get() = access.superAdmin || isAdminOfScopes.isNotEmpty()

    @Serializable
    data class AccessLevels(
        val user: Boolean = true,
        val admin: Boolean = false,
        val superAdmin: Boolean = false,  // OAuth role - separate security concern
    )
}
