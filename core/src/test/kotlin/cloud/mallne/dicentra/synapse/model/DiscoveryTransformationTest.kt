package cloud.mallne.dicentra.synapse.model

import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import cloud.mallne.dicentra.synapse.statics.ServiceDefinitionTransformationType
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscoveryTransformationTest {
    private val config = SynapseConfig(
        security = Security(groups = SecurityGroups()),
        data = Database(),
        server = Server(cors = ServerCors()),
        catalyst = Catalyst(),
    )
    private val definition = OpenApiDoc.build {
        info = OpenApiInfo(title = "Native resource", version = "1.0.0")
    }

    @Test
    fun `Catalyst discovery retains native-only services`() {
        val service = APIServiceDTO(serviceDefinition = definition, catalystTransformable = false)
        assertEquals(ServiceDefinitionTransformationType.Native, service.transformOrNull(config, ServiceDefinitionTransformationType.Catalyst))
    }

    @Test
    fun `Catalyst-capable services still use Catalyst`() {
        val service = APIServiceDTO(serviceDefinition = definition)
        assertEquals(ServiceDefinitionTransformationType.Catalyst, service.transformOrNull(config, ServiceDefinitionTransformationType.Catalyst))
    }

    @Test
    fun `native fallback never exposes services that disallow native discovery`() {
        val service = APIServiceDTO(serviceDefinition = definition, nativeTransformable = false, catalystTransformable = false)
        assertNull(service.transformOrNull(config, ServiceDefinitionTransformationType.Catalyst))
    }

    @Test
    fun `disabled Catalyst does not silently downgrade Catalyst-capable services`() {
        val service = APIServiceDTO(serviceDefinition = definition)
        assertNull(service.transformOrNull(config.copy(catalyst = config.catalyst.copy(enabled = false)), ServiceDefinitionTransformationType.Catalyst))
    }
}
