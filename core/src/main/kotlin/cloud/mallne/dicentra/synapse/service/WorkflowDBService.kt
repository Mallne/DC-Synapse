package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.orchestration.arazzo.validation.ArazzoValidationResult
import cloud.mallne.dicentra.aviator.orchestration.arazzo.validation.ArazzoValidator
import cloud.mallne.dicentra.synapse.model.RequiresTransactionContext
import cloud.mallne.dicentra.synapse.statics.Serialization
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.json.jsonb
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.koin.core.annotation.Single

/** A stored document remains JSON so inert Arazzo extensions survive a round trip. */
@Serializable
data class StoredWorkflow(val id: String, val scope: String?, val document: JsonElement, val builtin: Boolean = false)

class InvalidWorkflowException(val diagnostics: List<String>) : IllegalArgumentException("Unsupported Arazzo workflow")

@Single
class WorkflowDBService {
    object Workflows : IdTable<String>("synapse_workflows") {
        override val id: Column<EntityID<String>> = varchar("id", 36).entityId()
        val scope = varchar("scope", 255).nullable()
        val document = jsonb<JsonElement>("document", Serialization())
        val builtin = bool("builtin").default(false)
        val created = datetime("created").defaultExpression(CurrentDateTime)
        override val primaryKey = PrimaryKey(id)
    }

    /** Validates the current executable profile before it can become stored data. */
    fun validate(workflow: StoredWorkflow) {
        require(workflow.id.isNotBlank() && workflow.id.length <= 36)
        require(workflow.builtin || !workflow.id.startsWith(BUILTIN_PREFIX)) { "Reserved workflow identity" }
        require(workflow.scope == null || workflow.scope.isNotBlank() && workflow.scope.length <= 255)
        when (val validation = ArazzoValidator.parseAndValidate(workflow.document.toString())) {
            is ArazzoValidationResult.Valid -> Unit
            is ArazzoValidationResult.Invalid -> throw InvalidWorkflowException(
                validation.diagnostics.map { "${it.code}:${it.location}" },
            )
        }
    }

    companion object {
        const val BUILTIN_PREFIX = "builtin:"
    }

    @RequiresTransactionContext
    suspend fun create(workflow: StoredWorkflow) {
        validate(workflow)
        Workflows.insert {
            it[id] = workflow.id
            it[scope] = workflow.scope
            it[document] = workflow.document
            it[builtin] = workflow.builtin
        }
    }

    /** Atomically create a workflow without replacing an existing identity. */
    @RequiresTransactionContext
    suspend fun createIfAbsent(workflow: StoredWorkflow): Boolean {
        validate(workflow)
        return Workflows.insertIgnoreAndGetId {
            it[id] = workflow.id
            it[scope] = workflow.scope
            it[document] = workflow.document
            it[builtin] = workflow.builtin
        } != null
    }

    /** The scope predicate is part of the query, not a filter applied after loading a document. */
    @RequiresTransactionContext
    suspend fun readVisible(id: String, scopes: Collection<String>): StoredWorkflow? {
        val public = Workflows.selectAll().where { (Workflows.id eq id) and (Workflows.scope eq null) }
            .map { StoredWorkflow(it[Workflows.id].value, it[Workflows.scope], it[Workflows.document], it[Workflows.builtin]) }
            .singleOrNull()
        if (public != null || scopes.isEmpty()) return public
        return Workflows.selectAll().where { (Workflows.id eq id) and (Workflows.scope inList scopes) }
            .map { StoredWorkflow(it[Workflows.id].value, it[Workflows.scope], it[Workflows.document], it[Workflows.builtin]) }
            .singleOrNull()
    }

    @RequiresTransactionContext
    suspend fun listVisible(scopes: Collection<String>): List<StoredWorkflow> {
        val public = Workflows.selectAll().where { Workflows.scope eq null }
            .map { StoredWorkflow(it[Workflows.id].value, it[Workflows.scope], it[Workflows.document], it[Workflows.builtin]) }
            .toList()
        val scoped = if (scopes.isEmpty()) emptyList() else Workflows.selectAll()
            .where { Workflows.scope inList scopes }
            .map { StoredWorkflow(it[Workflows.id].value, it[Workflows.scope], it[Workflows.document], it[Workflows.builtin]) }
            .toList()
        return (public + scoped).sortedBy { it.id }
    }

    @RequiresTransactionContext
    suspend fun delete(id: String, scope: String?) {
        Workflows.deleteWhere { (Workflows.id eq id) and (Workflows.scope eq scope) }
    }

    /** Re-ingest only host-owned public documents; never replace a user's existing document. */
    @RequiresTransactionContext
    suspend fun upsertBuiltin(workflow: StoredWorkflow) {
        require(workflow.builtin && workflow.scope == null)
        validate(workflow)
        val existing = Workflows.selectAll().where { Workflows.id eq workflow.id }
            .map { it[Workflows.builtin] && it[Workflows.scope] == null }
            .singleOrNull()
        if (existing == null) {
            create(workflow)
        } else {
            require(existing) { "Builtin workflow identity is owned by another document" }
            Workflows.update({ (Workflows.id eq workflow.id) and (Workflows.builtin eq true) }) {
                it[document] = workflow.document
            }
        }
    }
}
