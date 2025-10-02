package cloud.mallne.dicentra.synapse.model.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ScopeDTO(
    @Transient
    val id: Int = 0,
    val name: String,
    val attaches: String,
)