package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.plugins.AviatorPluginInstance
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystResultCodec
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystParameterValue
import kotlinx.serialization.KSerializer

/** Host-owned provider for one Aviator plugin identity used by Catalyst execution. */
interface CatalystPluginContribution {
    val identity: String
    /** Infrastructure adapters are active for every Catalyst upstream execution. */
    val activateByDefault: Boolean get() = false
    /** True when this adapter constructs a provider request body from typed inputs. */
    val handlesRequestBody: Boolean get() = false
    val parameterCodecs: List<KSerializer<*>> get() = emptyList()
    /** Inputs consumed by this provider plugin even when they are not upstream OpenAPI parameters. */
    val parameterContracts: List<CatalystParameterContract> get() = emptyList()
    val resultCodecs: List<CatalystResultCodec> get() = emptyList()

    /** Must return a fresh instance for each action execution. */
    fun create(): AviatorPluginInstance
}

data class CatalystParameterContract(
    val name: String,
    val cardinality: CatalystParameterValue.Cardinality,
    val serializer: KSerializer<*>,
) {
    val codec: String get() = serializer.descriptor.serialName
}
