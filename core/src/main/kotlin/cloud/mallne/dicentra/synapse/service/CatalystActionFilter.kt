package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.execution.RequestParameters
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystRequest

/** Deployment extension for declarative candidate selection by domain-specific request data. */
interface CatalystActionFilter {
    val identity: String
    /** Lower values run first; ties are ordered by [identity]. */
    val priority: Int get() = 0

    /** Return null when not applicable, an empty list when no candidates match, or selected candidates. */
    fun select(
        candidates: List<ResolvedCatalystAction>,
        request: CatalystRequest,
        parameters: RequestParameters,
    ): List<ResolvedCatalystAction>?
}
