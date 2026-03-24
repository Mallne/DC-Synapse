package cloud.mallne.dicentra.synapse.model.dto

import kotlinx.serialization.Serializable

@Serializable
data class ScopeDTO(
    val name: String,
    val attachesReads: List<String> = emptyList(),
    val attachesWrites: List<String> = emptyList(),
    val attachesAdmin: List<String> = emptyList(),
)
