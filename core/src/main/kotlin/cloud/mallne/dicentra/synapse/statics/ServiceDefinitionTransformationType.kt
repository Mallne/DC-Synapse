package cloud.mallne.dicentra.synapse.statics

import cloud.mallne.dicentra.aviator.plugin.synapse.ServiceDefinitionTransformationType as SharedTransformationType
import cloud.mallne.dicentra.synapse.model.SynapseConfig

typealias ServiceDefinitionTransformationType = SharedTransformationType

fun ServiceDefinitionTransformationType.distill(
    config: SynapseConfig,
    preferred: ServiceDefinitionTransformationType = config.preferredTransform,
): ServiceDefinitionTransformationType? {
    val finalType = if (this == ServiceDefinitionTransformationType.Auto) {
        if (preferred == ServiceDefinitionTransformationType.Auto) config.preferredTransform else preferred
    } else this
    return finalType.takeUnless { it == ServiceDefinitionTransformationType.Auto }
}

fun ServiceDefinitionTransformationType.canUse(
    config: SynapseConfig,
    preferred: ServiceDefinitionTransformationType = config.preferredTransform,
    native: Boolean = true,
    catalyst: Boolean = config.catalyst.enabled,
): Boolean = when (distill(config, preferred)) {
    ServiceDefinitionTransformationType.Native -> native
    ServiceDefinitionTransformationType.Catalyst -> catalyst && config.catalyst.enabled
    else -> false
}
