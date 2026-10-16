package cloud.mallne.dicentra.synapse.di

import cloud.mallne.dicentra.aviator.core.plugins.AviatorPluginInstance
import cloud.mallne.dicentra.aviator.plugin.adapter.json.JsonAdapter
import cloud.mallne.dicentra.synapse.model.*
import cloud.mallne.dicentra.synapse.service.CatalystExecutor
import cloud.mallne.dicentra.synapse.service.CatalystPluginContribution
import kotlinx.serialization.KSerializer
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertContains

class CatalystCompositionTest {
    @Test
    fun `synapse and host plugin contributions aggregate through koin`() {
        val hostContribution = object : CatalystPluginContribution {
            override val identity = "test:host-plugin"
            override val parameterCodecs: List<KSerializer<*>> = emptyList()
            override fun create(): AviatorPluginInstance = JsonAdapter.install { }
        }
        val hostModule = module {
            single { testConfig() }
            single<CatalystPluginContribution> { hostContribution }
        }
        val application = koinApplication { modules(DI, hostModule) }

        try {
            val contributions = application.koin.getAll<CatalystPluginContribution>()
            assertContains(contributions.map { it.identity }, JsonAdapter.identity)
            assertContains(contributions.map { it.identity }, hostContribution.identity)
            application.koin.get<CatalystExecutor>().close()
        } finally {
            application.close()
        }
    }

    private fun testConfig() = SynapseConfig(
        security = Security(groups = SecurityGroups()),
        data = Database(),
        server = Server(cors = ServerCors()),
        catalyst = Catalyst(),
    )
}
