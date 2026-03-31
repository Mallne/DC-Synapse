package cloud.mallne.dicentra.synapse.model.dto

import kotlinx.serialization.Serializable

@Serializable
data class ScopeDTO(
    val name: String,
    val readers: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val admins: List<String> = emptyList(),
)
