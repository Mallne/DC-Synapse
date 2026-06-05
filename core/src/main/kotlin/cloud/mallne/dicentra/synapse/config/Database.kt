package cloud.mallne.dicentra.synapse.config

import cloud.mallne.dicentra.synapse.model.SynapseConfig
import cloud.mallne.dicentra.synapse.service.APIDBService
import cloud.mallne.dicentra.synapse.service.DatabaseService
import cloud.mallne.dicentra.synapse.service.ScopeService
import io.ktor.server.application.*
import io.ktor.server.config.*
import kotlinx.coroutines.launch
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.jetbrains.exposed.v1.core.ExperimentalDatabaseMigrationApi
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.migration.r2dbc.MigrationUtils
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.io.File

fun Application.configureDatabase(vararg tables: Table = arrayOf(APIDBService.APIServiceData, ScopeService.Scopes)) {
    val config = environment.config.getAs<SynapseConfig>()
    val databaseService by inject<DatabaseService>()
    val log = LoggerFactory.getLogger("ConfigureDatabase")
    launch {
        log.info("Starting up database connection")
        if (config.data.autoCreateDelta) {
            log.info("Attempting to create a migration script")
            databaseService {
                val created = migration(config = config, tables = tables)
                log.info("Created Migration script {}", created.absolutePath)
            }
            this@configureDatabase.engine.stop()
        }
        log.info("Applying migration script")
        val result = flyway(config)
        log.info(result.toString())
    }
}

@OptIn(ExperimentalDatabaseMigrationApi::class)
private suspend fun migration(config: SynapseConfig, vararg tables: Table): File {
    val path = config.data.migrations.first()
    val location = File(path)
    location.mkdirs()
    val created = MigrationUtils.generateMigrationScript(
        tables = tables,
        scriptDirectory = path,
        scriptName = config.data.migrationName
    )
    return created
}

private fun flyway(config: SynapseConfig): MigrateResult =
    Flyway.configure()
        .defaultSchema(config.data.schema)
        .dataSource("jdbc:${config.data.url}", config.data.user, config.data.password)
        .locations(*config.data.migrations.toTypedArray())
        .callbackLocations(*config.data.callbacks.toTypedArray())
        .load()
        .migrate()