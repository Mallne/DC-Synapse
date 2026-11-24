package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.aviator.orchestration.arazzo.validation.ArazzoValidationResult
import cloud.mallne.dicentra.aviator.orchestration.arazzo.validation.ArazzoValidator
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import io.ktor.openapi.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class WorkflowOperationIndexTest {
    private fun service(id: String): APIServiceDTO {
        val definition = OpenApiDoc.build {
            `x-dicentra-aviator` = AviatorExtensionSpec.SpecVersion
            info = OpenApiInfo(title = id, version = "1")
            servers { server("https://provider.example") }
        }.copy(paths = mapOf("/data" to ReferenceOr.Value(PathItem(get = Operation.build {
            operationId = "getData"
            `x-dicentra-aviator-serviceDelegateCall` = ServiceLocator("data")
        }))))
        return APIServiceDTO(id = id, serviceDefinition = definition)
    }

    private fun document(source: String = "urn:synapse:service:one", operation: String = "\$sourceDescriptions.api.getData") =
        assertIs<ArazzoValidationResult.Valid>(ArazzoValidator.parseAndValidate(
            """{"arazzo":"1.1.0","info":{"title":"Example","version":"1"},"sourceDescriptions":[{"name":"api","url":"$source","type":"openapi"}],"workflows":[{"workflowId":"read","steps":[{"stepId":"fetch","operationId":"$operation"}]}]}""",
        )).document

    @Test
    fun `preflight selects the source qualified action rather than another matching locator`() {
        val workflow = document()
        val selected = WorkflowOperationIndex(workflow, listOf(service("one"), service("two")))
            .preflight(workflow.workflows.single()).getValue("fetch")
        assertEquals("one", selected.first.sourceId)
        assertEquals("one", selected.second.service.id)
        assertEquals(64, selected.first.sourceRevision.length)
    }

    @Test
    fun `invisible sources and network source URLs fail before dispatch`() {
        val invisible = document()
        val network = document("https://example.org/openapi.json")
        assertFailsWith<IllegalArgumentException> {
            WorkflowOperationIndex(invisible, listOf(service("two"))).preflight(invisible.workflows.single())
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowOperationIndex(network, listOf(service("one"))).preflight(network.workflows.single())
        }
    }

    @Test
    fun `workflow cannot execute services opted out of Catalyst`() {
        assertFailsWith<IllegalArgumentException> {
            val document = document()
            WorkflowOperationIndex(document, listOf(service("one").copy(catalystTransformable = false)))
                .preflight(document.workflows.single())
        }
    }

    @Test
    fun `unknown operation fails preflight`() {
        val document = document(operation = "\$sourceDescriptions.api.missing")
        assertFailsWith<IllegalArgumentException> {
            WorkflowOperationIndex(document, listOf(service("one"))).preflight(document.workflows.single())
        }
    }

    @Test
    fun `host source mappings are confined to visible services`() {
        val document = document("urn:host:weather")
        val visible = listOf(service("one"))
        val index = WorkflowOperationIndex(document, visible) { source, _ ->
            if (source == "urn:host:weather") "one" else null
        }
        assertEquals("one", index.preflight(document.workflows.single()).getValue("fetch").second.service.id)
        assertFailsWith<IllegalArgumentException> {
            WorkflowOperationIndex(document, visible) { _, _ -> "invisible" }.preflight(document.workflows.single())
        }
    }

    @Test
    fun `preflight ignores inaccessible sources used only by other workflows`() {
        val document = assertIs<ArazzoValidationResult.Valid>(ArazzoValidator.parseAndValidate(
            """{"arazzo":"1.1.0","info":{"title":"Example","version":"1"},"sourceDescriptions":[{"name":"visible","url":"urn:synapse:service:one","type":"openapi"},{"name":"hidden","url":"urn:synapse:service:two","type":"openapi"}],"workflows":[{"workflowId":"read","steps":[{"stepId":"fetch","operationId":"${'$'}sourceDescriptions.visible.getData"}]},{"workflowId":"other","steps":[{"stepId":"fetch","operationId":"${'$'}sourceDescriptions.hidden.getData"}]}]}""",
        )).document
        val index = WorkflowOperationIndex(document, listOf(service("one")))
        assertEquals("one", index.preflight(document.workflows[0]).getValue("fetch").second.service.id)
        assertFailsWith<IllegalArgumentException> { index.preflight(document.workflows[1]) }
        val unqualified = document.workflows[0].copy(
            steps = listOf(document.workflows[0].steps.single().copy(operationId = "getData")),
        )
        assertFailsWith<IllegalArgumentException> { index.preflight(unqualified) }
    }
}
