package cloud.mallne.dicentra.synapse.model.dto

import kotlinx.serialization.Serializable

@Serializable
data class ScopeDTO(
    val name: String,
    val attaches: List<String> = emptyList(),
    val attachesAdmin: List<String> = emptyList(),
)
