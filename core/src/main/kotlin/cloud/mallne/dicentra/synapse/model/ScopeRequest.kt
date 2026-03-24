package cloud.mallne.dicentra.synapse.model

import cloud.mallne.dicentra.synapse.model.dto.ScopeDTO
import cloud.mallne.dicentra.synapse.statics.ResponseObject
import kotlinx.serialization.Serializable

@Serializable
@ResponseObject
data class ScopeCreateRequest(
    val name: String,
    val attachesReads: List<String> = emptyList(),
    val attachesWrites: List<String> = emptyList(),
    val attachesAdmin: List<String> = emptyList(),
) {
    fun toDTO() = ScopeDTO(
        name = name,
        attachesReads = attachesReads,
        attachesWrites = attachesWrites,
        attachesAdmin = attachesAdmin
    )
}

@Serializable
@ResponseObject
data class ScopeUpdateAttachesRequest(
    val name: String,
    val attachesReads: List<String> = emptyList(),
    val attachesWrites: List<String> = emptyList(),
) {
    fun toDTO() = ScopeDTO(
        name = name, attachesReads = attachesReads,
        attachesWrites = attachesWrites,
    )
}
