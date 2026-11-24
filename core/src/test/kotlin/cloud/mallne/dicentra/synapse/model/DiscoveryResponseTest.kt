package cloud.mallne.dicentra.synapse.model

import cloud.mallne.dicentra.synapse.service.StoredWorkflow
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryResponseTest {
    @Test
    fun discoveryResponseCarriesOpenApiServicesAndArazzoWorkflows() {
        val workflow = StoredWorkflow(
            id = "weather",
            scope = "tenant:one",
            document = Json.parseToJsonElement(
                """{"arazzo":"1.1.0","info":{"title":"Weather","version":"1"},"sourceDescriptions":[{"name":"api","url":"weather.json","type":"openapi"}],"workflows":[],"x-note":"preserved"}""",
            ),
        )
        val service = OpenApiDoc.build {
            info = OpenApiInfo(title = "catalog", version = "1")
        }
        val encoded = Json.encodeToString(
            DiscoveryResponse(
                services = listOf(service),
                workflows = listOf(workflow),
            ),
        )
        val response = Json.decodeFromString<DiscoveryResponse>(encoded)

        assertEquals("catalog", response.services.single().info.title)
        assertEquals("weather", response.workflows.single().id)
        assertEquals("tenant:one", response.workflows.single().scope)
        assertEquals("1.1.0", response.workflows.single().document.jsonObject["arazzo"]?.jsonPrimitive?.content)
        assertEquals("preserved", response.workflows.single().document.jsonObject["x-note"]?.jsonPrimitive?.content)
    }
}
