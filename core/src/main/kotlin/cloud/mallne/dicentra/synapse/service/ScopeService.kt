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
import org.koin.core.annotation.Single

@Single
class ScopeService {
    object Scopes : IntIdTable() {
        val scopeName = varchar("scope_name", 255)
        val userId = varchar("user_id", 255)
        val role = varchar("role", 20)
        val created = datetime("created").defaultExpression(CurrentDateTime)

        init {
            uniqueIndex(scopeName, userId)
        }
    }

    @RequiresTransactionContext
    suspend fun createScope(scopeDTO: ScopeDTO) {
        val fullScopeName = Types.WRITE.scope(scopeDTO.name).name()

        Scopes.deleteWhere { Scopes.scopeName.eq(fullScopeName) }

        scopeDTO.attaches.forEach { userId ->
            Scopes.insert {
                it[Scopes.scopeName] = fullScopeName
                it[Scopes.userId] = userId
                it[Scopes.role] = "member"
            }
        }

        scopeDTO.attachesAdmin.forEach { userId ->
            Scopes.insert {
                it[Scopes.scopeName] = fullScopeName
                it[Scopes.userId] = userId
                it[Scopes.role] = "admin"
            }
        }
    }

    @RequiresTransactionContext
    suspend fun readScope(scopeName: String): ScopeDTO? {
        val fullScopeName = Types.WRITE.scope(scopeName).name()
        val rows = Scopes.selectAll()
            .where { Scopes.scopeName.eq(fullScopeName) }
            .toList()

        if (rows.isEmpty()) return null

        val attaches = rows.filter { it[Scopes.role] == "member" }.map { it[Scopes.userId] }
        val attachesAdmin = rows.filter { it[Scopes.role] == "admin" }.map { it[Scopes.userId] }

        return ScopeDTO(name = scopeName, attaches = attaches, attachesAdmin = attachesAdmin)
    }

    @RequiresTransactionContext
    suspend fun updateAttaches(scopeName: String, attaches: List<String>) {
        val fullScopeName = Types.WRITE.scope(scopeName).name()

        Scopes.deleteWhere { Scopes.scopeName.eq(fullScopeName) and Scopes.role.eq("member") }

        attaches.forEach { userId ->
            Scopes.insert {
                it[Scopes.scopeName] = fullScopeName
                it[Scopes.userId] = userId
                it[Scopes.role] = "member"
            }
        }
    }

    @RequiresTransactionContext
    suspend fun deleteScope(scopeName: String) {
        val fullScopeName = Types.WRITE.scope(scopeName).name()
        Scopes.deleteWhere { Scopes.scopeName.eq(fullScopeName) }
    }

    @RequiresTransactionContext
    suspend fun getUserScopes(userId: String): List<String> {
        return Scopes.selectAll()
            .where { Scopes.userId.eq(userId) }
            .map { it[Scopes.scopeName] }
            .toList()
    }

    @RequiresTransactionContext
    suspend fun isAdminOf(userId: String, scopeName: String): Boolean {
        val fullScopeName = Types.ADMIN.scope(scopeName).name()
        return Scopes.selectAll()
            .where { Scopes.scopeName.eq(fullScopeName) and Scopes.userId.eq(userId) and Scopes.role.eq("admin") }
            .singleOrNull() != null
    }

    @RequiresTransactionContext
    suspend fun getUsersInScope(scopeName: String): List<String> {
        val fullScopeName = Types.WRITE.scope(scopeName).name()
        return Scopes.selectAll()
            .where { Scopes.scopeName.eq(fullScopeName) }
            .map { it[Scopes.userId] }
            .toList()
    }

    companion object {
        sealed interface ScopePart {
            companion object {
                private val allowedDelimiters: List<Char> = listOf(':')
                fun extract(maybePrefixedScope: String): ScopePart {
                    val delimiter = maybePrefixedScope.firstOrNull { allowedDelimiters.contains(it) }
                    val scopePart = delimiter?.let { maybePrefixedScope.substringBefore(it) }
                    val namePart = delimiter?.let { maybePrefixedScope.substringAfter(it) } ?: maybePrefixedScope
                    return when {
                        delimiter != null && scopePart != null -> object : ScopeName {
                            override val prefix: String = scopePart
                            override val delimiter: String = delimiter.toString()
                            override val scope: String = namePart
                        }

                        else -> object : ScopeSelector {
                            override val scope: String = maybePrefixedScope
                        }
                    }
                }
            }
        }

        interface ScopeDomain {
            val prefix: String
            val delimiter: String

            val selector: String
                get() = "$prefix$delimiter"
        }

        interface ScopeSelector : ScopePart {
            val scope: String
        }

        interface ScopeName : ScopeDomain, ScopeSelector {
            fun name(): String = "$prefix$delimiter$scope"
        }

        enum class Types(override val prefix: String, override val delimiter: String = ":") : ScopeDomain {
            READ("read"),
            WRITE("write"),
            ADMIN("admin"),
            USER("user");

            fun scope(name: String) = object : ScopeName {
                override val scope: String = name
                override val delimiter: String = this@Types.delimiter
                override val prefix: String = this@Types.prefix
            }

            fun isDirectMember(maybePrefixedScope: String): Boolean {
                return when (val sc = ScopePart.extract(maybePrefixedScope)) {
                    is ScopeName -> sc.prefix == prefix
                    is ScopeSelector -> false
                }
            }

            fun canWrite(maybePrefixedScope: String): Boolean {
                return when (val sc = ScopePart.extract(maybePrefixedScope)) {
                    is ScopeName -> {
                        (prefix != ADMIN.prefix && sc.prefix == ADMIN.prefix) || prefix == USER.prefix || prefix == WRITE.prefix
                    }

                    is ScopeSelector -> false
                }
            }

            fun canExpandOps(maybePrefixedScope: String): Boolean {
                return when (val sc = ScopePart.extract(maybePrefixedScope)) {
                    is ScopeName -> (this != ADMIN && sc.prefix == ADMIN.prefix)
                    is ScopeSelector -> false
                }
            }
        }
    }
}
