package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.synapse.model.RequiresTransactionContext
import cloud.mallne.dicentra.synapse.model.dto.ScopeDTO
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.koin.core.annotation.Single

@Single
class ScopeService {
    object Scopes : IntIdTable() {
        val scopeName = varchar("scope_name", 255)
        val userId = varchar("user_id", 255)
        val type = enumeration<Types>("type")
        val updated = datetime("updated").defaultExpression(CurrentDateTime)

        init {
            uniqueIndex(scopeName, userId)
        }
    }

    @RequiresTransactionContext
    suspend fun createScope(scopeDTO: ScopeDTO) {
        Scopes.deleteWhere { Scopes.scopeName.eq(scopeDTO.name) }

        val map = mutableMapOf<String, Types>()

        map.putAll(scopeDTO.readers.associateWith { Types.READ })
        map.putAll(scopeDTO.writers.associateWith { Types.WRITE })
        map.putAll(scopeDTO.admins.associateWith { Types.ADMIN })

        map.forEach { (userId, type) ->
            Scopes.insert {
                it[Scopes.scopeName] = scopeDTO.name
                it[Scopes.userId] = userId
                it[Scopes.type] = type
            }
        }
    }

    @RequiresTransactionContext
    suspend fun readScope(scopeName: String): ScopeDTO? {
        val rows = Scopes.selectAll()
            .where { Scopes.scopeName.eq(scopeName) }
            .toList()

        if (rows.isEmpty()) return null

        val readers = rows.filter { it[Scopes.type] == Types.READ }.map { it[Scopes.userId] }
        val writers = rows.filter { it[Scopes.type] == Types.WRITE }.map { it[Scopes.userId] }
        val admins = rows.filter { it[Scopes.type] == Types.ADMIN }.map { it[Scopes.userId] }

        return ScopeDTO(name = scopeName, readers = readers, writers = writers, admins = admins)
    }

    @RequiresTransactionContext
    suspend fun deleteScope(scopeName: String) {
        Scopes.deleteWhere { Scopes.scopeName.eq(scopeName) }
    }

    @RequiresTransactionContext
    suspend fun getUserScopes(userId: String): Map<String, Types> {
        return Scopes.selectAll()
            .where { Scopes.userId.eq(userId) }
            .map { it[Scopes.scopeName] to it[Scopes.type] }
            .toList()
            .toMap()
    }

    companion object {
        enum class Types {
            READ,
            WRITE,
            ADMIN,
            USER;

            val canWrite: Boolean
                get() = this == WRITE || this == ADMIN || this == USER

            val canExpandOps: Boolean
                get() = this == ADMIN
        }
    }
}
