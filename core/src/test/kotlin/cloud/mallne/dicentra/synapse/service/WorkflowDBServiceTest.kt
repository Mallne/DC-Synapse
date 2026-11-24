package cloud.mallne.dicentra.synapse.service

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkflowDBServiceTest {
    private val document = Json.parseToJsonElement(
        """{"arazzo":"1.1.0","info":{"title":"Example","version":"1"},"sourceDescriptions":[{"name":"api","url":"urn:service:example","type":"openapi"}],"workflows":[{"workflowId":"read","steps":[{"stepId":"fetch","operationId":"getItem"}]}],"x-note":"preserved"}""",
    )

    @Test
    fun `validates supported workflow without discarding extensions`() {
        val workflow = StoredWorkflow("example", "tenant", document)
        WorkflowDBService().validate(workflow)
        assertTrue(workflow.document.toString().contains("\"x-note\":\"preserved\""))
    }

    @Test
    fun `unsupported workflow features are rejected before storage`() {
        val unsupported = Json.parseToJsonElement(document.toString().replace(
            "\"operationId\":\"getItem\"",
            "\"operationPath\":\"/items\"",
        ))
        val failure = assertFailsWith<InvalidWorkflowException> {
            WorkflowDBService().validate(StoredWorkflow("example", "tenant", unsupported))
        }
        assertTrue(failure.diagnostics.any { it.startsWith("unsupported-") })
    }

    @Test
    fun `storage rejects invalid identities and scopes`() {
        assertFailsWith<IllegalArgumentException> { WorkflowDBService().validate(StoredWorkflow("", null, document)) }
        assertFailsWith<IllegalArgumentException> { WorkflowDBService().validate(StoredWorkflow("id", "", document)) }
        assertFailsWith<IllegalArgumentException> { WorkflowDBService().validate(StoredWorkflow("builtin:reserved", "tenant", document)) }
        WorkflowDBService().validate(StoredWorkflow("builtin:reserved", null, document, builtin = true))
        assertEquals("tenant", StoredWorkflow("id", "tenant", document).scope)
    }
}
