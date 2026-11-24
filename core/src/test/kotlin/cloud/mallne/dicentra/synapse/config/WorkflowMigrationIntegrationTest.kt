package cloud.mallne.dicentra.synapse.config

import cloud.mallne.dicentra.synapse.model.Catalyst
import cloud.mallne.dicentra.synapse.model.Database
import cloud.mallne.dicentra.synapse.model.Security
import cloud.mallne.dicentra.synapse.model.SecurityGroups
import cloud.mallne.dicentra.synapse.model.Server
import cloud.mallne.dicentra.synapse.model.ServerCors
import cloud.mallne.dicentra.synapse.model.SynapseConfig
import org.flywaydb.core.Flyway
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowMigrationIntegrationTest {
    @Test
    fun `legacy shared history retains workflows and applies later host migrations`() {
        val url = System.getenv("CATALYST_TEST_DATABASE_URL")
        assumeTrue("Set CATALYST_TEST_DATABASE_URL to a disposable PostgreSQL database", !url.isNullOrBlank())
        val user = System.getenv("CATALYST_TEST_DATABASE_USER") ?: "postgres"
        val password = System.getenv("CATALYST_TEST_DATABASE_PASSWORD") ?: "postgres"
        val schema = "migration_test_${UUID.randomUUID().toString().replace("-", "")}"
        val migrations = Files.createTempDirectory("synapse-legacy-migrations-")
        Files.writeString(migrations.resolve("V0__host.sql"), "CREATE TABLE host_test (id INTEGER PRIMARY KEY);")
        val config = SynapseConfig(
            security = Security(groups = SecurityGroups()),
            data = Database(url = requireNotNull(url), user = user, password = password, schema = schema,
                migrations = listOf("filesystem:$migrations")),
            server = Server(cors = ServerCors()),
            catalyst = Catalyst(),
        )
        fun legacy() = Flyway.configure().defaultSchema(schema)
            .dataSource("jdbc:$url", user, password)
            .locations("filesystem:$migrations", "classpath:synapse/migration")
            .outOfOrder(true)
            .load()
        legacy().migrate()
        Files.writeString(migrations.resolve("V1__host_update.sql"), "ALTER TABLE host_test ADD COLUMN label TEXT;")

        flyway(config)

        val applied = legacy().info().applied().mapNotNull { it.version?.toString() }.toSet()
        assertEquals(setOf("0", "1", "2026112301", "2026112302"), applied)
    }

    @Test
    fun `host versions remain independent of bundled workflow versions`() {
        val url = System.getenv("CATALYST_TEST_DATABASE_URL")
        assumeTrue("Set CATALYST_TEST_DATABASE_URL to a disposable PostgreSQL database", !url.isNullOrBlank())
        val user = System.getenv("CATALYST_TEST_DATABASE_USER") ?: "postgres"
        val password = System.getenv("CATALYST_TEST_DATABASE_PASSWORD") ?: "postgres"
        val schema = "migration_test_${UUID.randomUUID().toString().replace("-", "")}"
        val migrations = Files.createTempDirectory("synapse-host-migrations-")
        Files.writeString(migrations.resolve("V0__host.sql"), "CREATE TABLE host_test (id INTEGER PRIMARY KEY);")
        val config = SynapseConfig(
            security = Security(groups = SecurityGroups()),
            data = Database(url = requireNotNull(url), user = user, password = password, schema = schema,
                migrations = listOf("filesystem:$migrations")),
            server = Server(cors = ServerCors()),
            catalyst = Catalyst(),
        )

        flyway(config)
        Files.writeString(migrations.resolve("V1__host_update.sql"), "ALTER TABLE host_test ADD COLUMN label TEXT;")
        flyway(config)

        fun current(table: String): String = Flyway.configure()
            .defaultSchema(schema)
            .dataSource("jdbc:$url", user, password)
            .table(table)
            .locations(if (table == "flyway_schema_history") "filesystem:$migrations" else "classpath:synapse/migration")
            .load().info().current().version.toString()
        assertEquals("1", current("flyway_schema_history"))
        assertEquals("2026112302", current("synapse_workflow_schema_history"))
    }
}
