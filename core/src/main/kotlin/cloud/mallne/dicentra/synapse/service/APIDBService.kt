package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.koas.OpenAPI
import cloud.mallne.dicentra.synapse.model.RequiresTransactionContext
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import cloud.mallne.dicentra.synapse.statics.Serialization
import cloud.mallne.dicentra.synapse.statics.ServiceDefinitionTransformationType
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.json.jsonb
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.koin.core.annotation.Single

/**
 * Service responsible for managing API service data within a database. It provides operations
 * to create, read, update and delete API service entries. The service interacts with the
 * database using the provided `DatabaseService` instance.
 *
 * @constructor Initializes the APIDBService with the given `DatabaseService` and ensures
 *              the underlying database table schema is created during initialization.
 */
@Single
class APIDBService {
    object APIServiceData : IdTable<String>() {
        val service = jsonb<OpenAPI>("service", Serialization())
        val scope = varchar("scope", 255).nullable()
        val created = datetime("created").defaultExpression(CurrentDateTime)
        val nativeTransformable = bool("native_transformable").default(true)
        val catalystTransformable = bool("catalyst_transformable").default(true)
        val mcpEnabled = bool("mcp_enabled").default(true)
        val builtin = bool("builtin").default(false)
        val preferredTransform = enumeration<ServiceDefinitionTransformationType>("preferred_transform").default(
            ServiceDefinitionTransformationType.Auto
        )
        override val id: Column<EntityID<String>> = varchar("id", 36).entityId()
    }

    /**
     * Creates a new API service record in the database and returns its generated ID.
     *
     * @param apiService the data transfer object containing the API service information
     *                   to be saved in the database. It includes the service's ID, definition,
     *                   and optional scope.
     * @return the ID of the newly created API service record as a string.
     */
    @RequiresTransactionContext
    suspend fun create(apiService: APIServiceDTO): String =
        APIServiceData.insert {
            it[id] = apiService.id
            it[service] = apiService.serviceDefinition
            it[scope] = apiService.scope
            it[created] = CurrentDateTime
            it[nativeTransformable] = apiService.nativeTransformable
            it[catalystTransformable] = apiService.catalystTransformable
            it[mcpEnabled] = apiService.mcpEnabled
            it[builtin] = apiService.builtin
            it[preferredTransform] = apiService.preferredTransform
        }[APIServiceData.id].value

    /**
     * Retrieves a single API service record from the database based on the specified ID.
     *
     * @param id the unique identifier of the API service to be retrieved.
     * @return an `APIServiceDTO` object representing the retrieved API service if found, or `null` if no matching record exists.
     */
    @RequiresTransactionContext
    suspend fun read(id: String): APIServiceDTO? {
        return APIServiceData.selectAll()
            .where { APIServiceData.id eq id }
            .map {
                APIServiceDTO(
                    id = it[APIServiceData.id].value,
                    serviceDefinition = it[APIServiceData.service],
                    scope = it[APIServiceData.scope],
                    created = it[APIServiceData.created],
                    nativeTransformable = it[APIServiceData.nativeTransformable],
                    catalystTransformable = it[APIServiceData.catalystTransformable],
                    mcpEnabled = it[APIServiceData.mcpEnabled],
                    builtin = it[APIServiceData.builtin],
                    preferredTransform = it[APIServiceData.preferredTransform],
                )
            }
            .singleOrNull()
    }

    /**
     * Retrieves a list of `APIServiceDTO` objects from the database that match the specified scope.
     *
     * @param scope the scope string used to filter the API services. If null, services with no scope will be retrieved.
     * @return a list of `APIServiceDTO` objects that match the given scope. Returns an empty list if no matching records are found.
     */
    @RequiresTransactionContext
    suspend fun readForScope(scope: String): List<APIServiceDTO> {
        return APIServiceData.selectAll()
            .where { APIServiceData.scope eq scope }
            .map {
                APIServiceDTO(
                    it[APIServiceData.id].value,
                    it[APIServiceData.service],
                    it[APIServiceData.scope],
                    created = it[APIServiceData.created],
                    nativeTransformable = it[APIServiceData.nativeTransformable],
                    catalystTransformable = it[APIServiceData.catalystTransformable],
                    mcpEnabled = it[APIServiceData.mcpEnabled],
                    builtin = it[APIServiceData.builtin],
                    preferredTransform = it[APIServiceData.preferredTransform],
                )
            }
            .toList()
    }

    /**
     * Retrieves a list of `APIServiceDTO` objects from the database that match the specified scope.
     *
     * @return a list of `APIServiceDTO` objects that match the given scope. Returns an empty list if no matching records are found.
     */
    @RequiresTransactionContext
    suspend fun readPublic(): List<APIServiceDTO> {
        return APIServiceData.selectAll()
            .where { APIServiceData.scope eq null }
            .map {
                APIServiceDTO(
                    it[APIServiceData.id].value,
                    it[APIServiceData.service],
                    it[APIServiceData.scope],
                    created = it[APIServiceData.created],
                    nativeTransformable = it[APIServiceData.nativeTransformable],
                    catalystTransformable = it[APIServiceData.catalystTransformable],
                    mcpEnabled = it[APIServiceData.mcpEnabled],
                    builtin = it[APIServiceData.builtin],
                    preferredTransform = it[APIServiceData.preferredTransform],
                )
            }
            .toList()
    }

    /**
     * Retrieves a list of `APIServiceDTO` objects from the database that match the given scopes.
     *
     * @param scope a list of scope strings used to filter the API services. Only services
     *              with scopes included in this list will be retrieved.
     * @return a list of `APIServiceDTO` objects that match the provided scopes. If no services
     *         match, an empty list is returned.
     */
    @RequiresTransactionContext
    suspend fun readForScopes(scope: List<String>): List<APIServiceDTO> {
        return APIServiceData.selectAll()
            .where { APIServiceData.scope inList scope }
            .map {
                APIServiceDTO(
                    it[APIServiceData.id].value,
                    it[APIServiceData.service],
                    it[APIServiceData.scope],
                    created = it[APIServiceData.created],
                    nativeTransformable = it[APIServiceData.nativeTransformable],
                    catalystTransformable = it[APIServiceData.catalystTransformable],
                    mcpEnabled = it[APIServiceData.mcpEnabled],
                    builtin = it[APIServiceData.builtin],
                    preferredTransform = it[APIServiceData.preferredTransform],
                )
            }
            .toList()
    }

    @RequiresTransactionContext
    suspend fun readBuiltin(): List<APIServiceDTO> {
        return APIServiceData.selectAll()
            .where { APIServiceData.builtin eq true }
            .map {
                APIServiceDTO(
                    it[APIServiceData.id].value,
                    it[APIServiceData.service],
                    it[APIServiceData.scope],
                    created = it[APIServiceData.created],
                    nativeTransformable = it[APIServiceData.nativeTransformable],
                    catalystTransformable = it[APIServiceData.catalystTransformable],
                    mcpEnabled = it[APIServiceData.mcpEnabled],
                    builtin = it[APIServiceData.builtin],
                    preferredTransform = it[APIServiceData.preferredTransform],
                )
            }
            .toList()
    }

    /**
     * Updates an existing API service record in the database using the provided data transfer object.
     *
     * @param apiService the data transfer object containing the updated API service information,
     *                   including the service's unique identifier, new service definition,
     *                   and optional scope.
     */
    @RequiresTransactionContext
    suspend fun update(apiService: APIServiceDTO) {
        APIServiceData.update({ APIServiceData.id eq apiService.id }) {
            it[service] = apiService.serviceDefinition
            it[scope] = apiService.scope
            it[nativeTransformable] = apiService.nativeTransformable
            it[catalystTransformable] = apiService.catalystTransformable
            it[mcpEnabled] = apiService.mcpEnabled
            it[builtin] = apiService.builtin
            it[preferredTransform] = apiService.preferredTransform
        }
    }

    /**
     * Deletes an API service record from the database based on the provided ID.
     *
     * @param id the unique identifier of the API service to be deleted.
     */
    @RequiresTransactionContext
    suspend fun delete(id: String) {
        APIServiceData.deleteWhere { APIServiceData.id eq id }
    }
}