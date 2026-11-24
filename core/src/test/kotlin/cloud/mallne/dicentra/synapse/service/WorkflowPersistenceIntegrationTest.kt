package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.synapse.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** CATALYST_TEST_DATABASE_URL must point at a disposable PostgreSQL database. */
class WorkflowPersistenceIntegrationTest {
    @Test
    fun `migration stores original document and filters reads by scope`() = runBlocking {
        val url = System.getenv("CATALYST_TEST_DATABASE_URL")
        assumeTrue("Set CATALYST_TEST_DATABASE_URL to a disposable PostgreSQL database", !url.isNullOrBlank())
        val user = System.getenv("CATALYST_TEST_DATABASE_USER") ?: "postgres"
        val password = System.getenv("CATALYST_TEST_DATABASE_PASSWORD") ?: "postgres"
        Flyway.configure().dataSource("jdbc:$url", user, password)
            .locations("classpath:synapse/migration").load().migrate()
        val db = DatabaseService(SynapseConfig(
            security = Security(groups = SecurityGroups()),
            data = Database(url = requireNotNull(url), user = user, password = password, schema = "public"),
            server = Server(cors = ServerCors()),
            catalyst = Catalyst(),
        ))
        val store = WorkflowDBService()
        val document = Json.parseToJsonElement(
            """{"arazzo":"1.1.0","info":{"title":"Example","version":"1"},"sourceDescriptions":[{"name":"api","url":"urn:synapse:service:example","type":"openapi"}],"workflows":[{"workflowId":"read","steps":[{"stepId":"fetch","operationId":"getItem"}]}],"x-note":"preserved"}""",
        )
        val scoped = StoredWorkflow(UUID.randomUUID().toString(), "tenant-a", document)
        val public = StoredWorkflow(UUID.randomUUID().toString(), null, document)
        db { store.create(scoped); store.create(public) }
        assertEquals(scoped, db { store.readVisible(scoped.id, listOf("tenant-a")) })
        assertNull(db { store.readVisible(scoped.id, listOf("tenant-b")) })
        assertNull(db { store.readVisible(scoped.id, emptyList()) })
        assertEquals(public, db { store.readVisible(public.id, emptyList()) })
        val anonymous = db { store.listVisible(emptyList()) }.map { it.id }
        val tenant = db { store.listVisible(listOf("tenant-a")) }.map { it.id }
        assertTrue(public.id in anonymous)
        assertFalse(scoped.id in anonymous)
        assertTrue(scoped.id in tenant)
        val builtin = StoredWorkflow(UUID.randomUUID().toString(), null, document, builtin = true)
        db { store.upsertBuiltin(builtin) }
        db { store.upsertBuiltin(builtin) }
        assertEquals(builtin, db { store.readVisible(builtin.id, emptyList()) })
        assertFailsWith<IllegalArgumentException> {
            db { store.upsertBuiltin(public.copy(builtin = true)) }
        }
        assertEquals(public, db { store.readVisible(public.id, emptyList()) })
    }
}
