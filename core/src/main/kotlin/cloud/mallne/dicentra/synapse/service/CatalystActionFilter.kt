package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.execution.RequestParameters
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystRequest

/** Deployment extension for declarative candidate selection by domain-specific request data. */
interface CatalystActionFilter {
    val identity: String
    /** Lower values run first; ties are ordered by [identity]. */
    val priority: Int get() = 0
    /** Logical operation inputs that must be schema-validated before this selector runs. */
    val selectionInputs: Set<String> get() = emptySet()

    /** Return null when not applicable, an empty list when no candidates match, or selected candidates. */
    fun select(
        candidates: List<ResolvedCatalystAction>,
        request: CatalystRequest,
        parameters: RequestParameters,
    ): List<ResolvedCatalystAction>?
}

/** Marker for ordered alternatives: execute one action at a time and stop at first success. */
interface CatalystFallbackActionFilter : CatalystActionFilter

/** Adds generic first-success execution semantics to an otherwise domain-only selector. */
class CatalystOrderedFallbackActionFilter(
    private val selector: CatalystActionFilter,
) : CatalystFallbackActionFilter {
    override val identity: String get() = selector.identity
    override val priority: Int get() = selector.priority
    override val selectionInputs: Set<String> get() = selector.selectionInputs

    override fun select(
        candidates: List<ResolvedCatalystAction>,
        request: CatalystRequest,
        parameters: RequestParameters,
    ): List<ResolvedCatalystAction>? = selector.select(candidates, request, parameters)
}
