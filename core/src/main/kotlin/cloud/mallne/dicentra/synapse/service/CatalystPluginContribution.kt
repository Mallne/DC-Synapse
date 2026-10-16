package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.plugins.AviatorPluginInstance
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystResultCodec
import kotlinx.serialization.KSerializer

/** Host-owned provider for one Aviator plugin identity used by Catalyst execution. */
interface CatalystPluginContribution {
    val identity: String
    val parameterCodecs: List<KSerializer<*>> get() = emptyList()
    val resultCodecs: List<CatalystResultCodec> get() = emptyList()

    /** Must return a fresh instance for each action execution. */
    fun create(): AviatorPluginInstance
}
