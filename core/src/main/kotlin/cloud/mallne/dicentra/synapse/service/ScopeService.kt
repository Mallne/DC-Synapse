package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.synapse.model.RequiresTransactionContext
import cloud.mallne.dicentra.synapse.model.dto.ScopeDTO
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.koin.core.annotation.Single

/**
 * Service class that provides CRUD operations for managing scope-related data.
 *
 * This service interacts with a PostgreSQL database using the Exposed library
 * and performs operations within transactional contexts provided by `DatabaseService`.
 *
 * Scope naming conventions:
 * - `scope:{name}` - Regular scope (e.g., "scope:john", "scope:settings")
 * - `admin:{name}` - Admin scope (e.g., "admin:settings" means admin of "scope:settings")
 *
 * @constructor Initializes the `ScopeService` and creates the `Scopes` table if it does not already exist.
 */
@Single
class ScopeService {
    object Scopes : IntIdTable() {
        val name = varchar("name", 255)
        val attaches = varchar("attaches", 255)
        val created = datetime("created").defaultExpression(CurrentDateTime)
    }

    /**
     * Table for mapping users to scopes with admin flag.
     * A user can be assigned to multiple scopes, and can be admin of multiple scopes.
     */
    object UserScopes : IntIdTable("user_scopes") {
        val userId = varchar("user_id", 255)
        val scopeName = varchar("scope_name", 255)
        val isAdmin = bool("is_admin").default(false)
        val created = datetime("created").defaultExpression(CurrentDateTime)
    }

    /**
     * Creates a new scope record in the database and returns its generated ID.
     *
     * @param scopeDTO the data transfer object containing the scope information
     *                 to be saved in the database. It includes the scope's name
     *                 and associated attachments.
     * @return the ID of the newly created scope record.
     */
    @RequiresTransactionContext
    suspend fun create(scopeDTO: ScopeDTO): Int = Scopes.insert {
        it[name] = scopeDTO.name
        it[attaches] = scopeDTO.attaches
    }[Scopes.id].value

    /**
     * Retrieves a `ScopeDTO` object corresponding to the specified ID from the database.
     *
     * @param id the unique identifier of the scope to be retrieved.
     * @return the `ScopeDTO` object if found, or `null` if no scope exists with the given ID.
     */
    @RequiresTransactionContext
    suspend fun read(id: Int): ScopeDTO? {
        return Scopes.selectAll()
            .where { Scopes.id eq id }
            .map { ScopeDTO(it[Scopes.id].value, it[Scopes.name], it[Scopes.attaches]) }
            .singleOrNull()
    }

    /**
     * Retrieves a list of `ScopeDTO` objects from the database that have an attachment
     * matching the provided parameter.
     *
     * @param attachment the attachment identifier used to filter the scopes in the database.
     * @return a list of `ScopeDTO` objects with the specified attachment.
     */
    @RequiresTransactionContext
    suspend fun readForAttachment(attachment: String): List<ScopeDTO> {
        return Scopes.selectAll()
            .where { Scopes.attaches eq attachment }
            .map { ScopeDTO(it[Scopes.id].value, it[Scopes.name], it[Scopes.attaches]) }
            .toList()
    }

    /**
     * Retrieves a list of `ScopeDTO` objects from the database where the scope name matches the given parameter.
     *
     * @param name the name of the scope to filter for in the database.
     * @return a list of `ScopeDTO` objects that have the specified name.
     */
    @RequiresTransactionContext
    suspend fun readForName(name: String): List<ScopeDTO> {
        return Scopes.selectAll()
            .where { Scopes.name eq name }
            .map { ScopeDTO(it[Scopes.id].value, it[Scopes.name], it[Scopes.attaches]) }
            .toList()
    }

    /**
     * Updates an existing scope record in the database using the provided data transfer object.
     *
     * @param scopeDTO the data transfer object containing the updated scope information.
     *                 This includes the scope's unique identifier, name, and associated attachments.
     */
    @RequiresTransactionContext
    suspend fun update(scopeDTO: ScopeDTO) {
        Scopes.update({ Scopes.id eq scopeDTO.id }) {
            it[name] = scopeDTO.name
            it[attaches] = scopeDTO.attaches
        }
    }

    /**
     * Deletes a scope record from the database based on the provided ID.
     *
     * @param id the unique identifier of the scope to be deleted.
     */
    @RequiresTransactionContext
    suspend fun delete(id: Int) {
        Scopes.deleteWhere { Scopes.id.eq(id) }
    }

    /**
     * Deletes a scope record from the database where the scope name matches the specified parameter.
     *
     * @param name the name of the scope to be deleted.
     */
    @RequiresTransactionContext
    suspend fun deleteByName(name: String) {
        Scopes.deleteWhere { Scopes.name.eq(name) }
    }

    // ==================== User-Scope Mapping Methods ====================

    /**
     * Assigns a user to a scope.
     *
     * @param userId The user's ID
     * @param scopeName The scope name (without prefix)
     * @param isAdmin Whether the user is admin of this scope
     */
    @RequiresTransactionContext
    suspend fun assignUserToScope(userId: String, scopeName: String, isAdmin: Boolean = false) {
        UserScopes.insert {
            it[this.userId] = userId
            it[this.scopeName] = scopeName
            it[this.isAdmin] = isAdmin
        }
    }

    /**
     * Removes a user's assignment to a scope.
     *
     * @param userId The user's ID
     * @param scopeName The scope name (without prefix)
     */
    @RequiresTransactionContext
    suspend fun removeUserFromScope(userId: String, scopeName: String) {
        UserScopes.deleteWhere {
            UserScopes.userId.eq(userId) and UserScopes.scopeName.eq(scopeName)
        }
    }

    /**
     * Removes all scope assignments for a user.
     *
     * @param userId The user's ID
     */
    @RequiresTransactionContext
    suspend fun removeAllScopesForUser(userId: String) {
        UserScopes.deleteWhere { UserScopes.userId.eq(userId) }
    }

    /**
     * Gets all scope assignments for a user.
     *
     * @param userId The user's ID
     * @return List of ScopeDTO with name and isAdmin flag
     */
    @RequiresTransactionContext
    suspend fun getScopeAssignmentsForUser(userId: String): List<UserScopeAssignment> {
        return UserScopes.selectAll()
            .where { UserScopes.userId.eq(userId) }
            .map { UserScopeAssignment(it[UserScopes.scopeName], it[UserScopes.isAdmin]) }
            .toList()
    }

    /**
     * Gets all full scope names for a user (including admin scopes).
     * Returns: ["scope:john", "scope:settings", "admin:settings"]
     *
     * @param userId The user's ID
     * @return List of full scope names
     */
    @RequiresTransactionContext
    suspend fun getScopesForUser(userId: String): List<String> {
        val assignments = getScopeAssignmentsForUser(userId)
        val scopes = mutableListOf<String>()

        for (assignment in assignments) {
            // Add the regular scope
            scopes.add(scope(assignment.scopeName))
            // If admin, add the admin scope
            if (assignment.isAdmin) {
                scopes.add(adminScope(assignment.scopeName))
            }
        }

        return scopes
    }

    /**
     * Checks if a user is admin of a specific scope.
     *
     * @param userId The user's ID
     * @param scopeName The scope name (without prefix)
     * @return true if user is admin of the scope
     */
    @RequiresTransactionContext
    suspend fun isAdminOf(userId: String, scopeName: String): Boolean {
        return UserScopes.selectAll()
            .where { UserScopes.userId.eq(userId) and UserScopes.scopeName.eq(scopeName) and UserScopes.isAdmin.eq(true) }
            .singleOrNull() != null
    }

    /**
     * Gets all users in a scope.
     *
     * @param scopeName The scope name (without prefix)
     * @return List of user IDs
     */
    @RequiresTransactionContext
    suspend fun getUsersInScope(scopeName: String): List<String> {
        return UserScopes.selectAll()
            .where { UserScopes.scopeName.eq(scopeName) }
            .map { it[UserScopes.userId] }
            .toList()
    }

    /**
     * Gets all admin users for a scope.
     *
     * @param scopeName The scope name (without prefix)
     * @return List of admin user IDs
     */
    @RequiresTransactionContext
    suspend fun getAdminsInScope(scopeName: String): List<String> {
        return UserScopes.selectAll()
            .where { UserScopes.scopeName.eq(scopeName) and UserScopes.isAdmin.eq(true) }
            .map { it[UserScopes.userId] }
            .toList()
    }

    /**
     * Updates the admin status for a user's scope assignment.
     *
     * @param userId The user's ID
     * @param scopeName The scope name (without prefix)
     * @param isAdmin The new admin status
     */
    @RequiresTransactionContext
    suspend fun updateAdminStatus(userId: String, scopeName: String, isAdmin: Boolean) {
        UserScopes.update({ UserScopes.userId.eq(userId) and UserScopes.scopeName.eq(scopeName) }) {
            it[this.isAdmin] = isAdmin
        }
    }

    // ==================== Prefix Helpers ====================

    /**
     * Data class representing a user's scope assignment.
     */
    data class UserScopeAssignment(
        val scopeName: String,
        val isAdmin: Boolean
    )

    companion object Prefix {
        const val SCOPE_PREFIX = "scope:"
        const val ADMIN_PREFIX = "admin:"

        /**
         * Creates a full scope name from a scope name.
         * @param name The scope name (e.g., "john", "settings")
         * @return The full scope name (e.g., "scope:john", "scope:settings")
         */
        fun scope(name: String) = SCOPE_PREFIX + name

        /**
         * Creates an admin scope name from a scope name.
         * @param name The scope name (e.g., "settings")
         * @return The admin scope name (e.g., "admin:settings")
         */
        fun adminScope(name: String) = ADMIN_PREFIX + name

        /**
         * Checks if a scope is an admin scope.
         * @param scope The full scope name
         * @return true if scope starts with "admin:"
         */
        fun isAdminScope(scope: String): Boolean = scope.startsWith(ADMIN_PREFIX)

        /**
         * Checks if a scope is a regular scope.
         * @param scope The full scope name
         * @return true if scope starts with "scope:"
         */
        fun isRegularScope(scope: String): Boolean = scope.startsWith(SCOPE_PREFIX)

        /**
         * Extracts the scope name from a scope or admin scope.
         * @param scope The full scope name (e.g., "scope:settings" or "admin:settings")
         * @return The extracted scope name (e.g., "settings") or null if invalid
         */
        fun getScopeName(scope: String): String? {
            return when {
                scope.startsWith(SCOPE_PREFIX) -> scope.removePrefix(SCOPE_PREFIX)
                scope.startsWith(ADMIN_PREFIX) -> scope.removePrefix(ADMIN_PREFIX)
                else -> null
            }
        }

        /**
         * Creates the user scope for a username (convenience method).
         * @param username The username
         * @return The user's scope (e.g., "scope:john")
         */
        fun user(username: String) = scope(username)
    }
}