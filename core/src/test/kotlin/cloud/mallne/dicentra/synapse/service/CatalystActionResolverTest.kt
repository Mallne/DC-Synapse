package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-pluginMaterialization`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.ServiceMethods
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.aviator.plugin.synapse.SynapsePlugin
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystRequest
import cloud.mallne.dicentra.aviator.core.execution.RequestParameters
import cloud.mallne.dicentra.synapse.model.dto.APIServiceDTO
import io.ktor.openapi.*
import io.ktor.openapi.ReferenceOr.*
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

class CatalystActionResolverTest {
    private val resolver = CatalystActionResolver()

    @Test
    fun `locator selector round trips and may be shared by many service identities`() {
        val locator = ServiceLocator("parcels", ServiceMethods.GATHER)
        val encoded = CatalystActionSelector.encode(locator)

        val decoded = CatalystActionSelector.decode(encoded)

        assertEquals(locator, decoded)
        assertNull(CatalystActionSelector.decode("not-base64!"))
    }

    @Test
    fun `resolves all caller visible services sharing the locator`() {
        val first = service("service-1")
        val second = service("service-2")

        val resolved = resolver.resolve(ServiceLocator("parcels", ServiceMethods.GATHER), listOf(first, second))

        assertEquals(listOf("service-1", "service-2"), resolved.map { it.service.id })
        assertEquals(setOf(ServiceLocator("parcels", ServiceMethods.GATHER)), resolved.map { it.locator }.toSet())
    }

    @Test
    fun `does not resolve invisible ineligible or recursive services`() {
        val service = service("service-1")

        val locator = ServiceLocator("parcels", ServiceMethods.GATHER)
        assertEquals(emptyList(), resolver.resolve(locator, emptyList()))
        assertEquals(emptyList(), resolver.resolve(locator, listOf(service.copy(catalystTransformable = false))))
        assertEquals(emptyList(), resolver.resolve(locator, listOf(service("gateway", wrapper = true))))
    }

    @Test
    fun `deduplicates repeated service records and rejects multiple same-locator actions within one service`() {
        val service = service("service-1")
        val locator = ServiceLocator("parcels", ServiceMethods.GATHER)

        assertEquals(1, resolver.resolve(locator, listOf(service, service)).size)
        assertEquals(emptyList(), resolver.resolve(locator, listOf(service("ambiguous", multipleActions = true))))
    }

    @Test
    fun `explicit identities override filters and empty filter selection means no candidates`() {
        val locator = ServiceLocator("parcels", ServiceMethods.GATHER)
        val actions = resolver.resolve(locator, listOf(service("service-1"), service("service-2")))
        val filter = object : CatalystActionFilter {
            override val identity = "test:first"
            override fun select(
                candidates: List<ResolvedCatalystAction>,
                request: CatalystRequest,
                parameters: RequestParameters,
            ): List<ResolvedCatalystAction> = candidates.take(1)
        }
        val executor = CatalystExecutor(emptyList(), listOf(filter))
        try {
            val automatic = executor.selectActions(actions, CatalystRequest(), RequestParameters())
            assertEquals(listOf("service-1"), automatic.map { it.service.id })

            val explicit = executor.selectActions(
                actions,
                CatalystRequest(identities = listOf("service-2")),
                RequestParameters(),
            )
            assertEquals(listOf("service-2"), explicit.map { it.service.id })

            val fallbackExecutor = CatalystExecutor(emptyList(), listOf(object : CatalystActionFilter {
                override val identity = "test:none"
                override fun select(
                    candidates: List<ResolvedCatalystAction>,
                    request: CatalystRequest,
                    parameters: RequestParameters,
                ) = emptyList<ResolvedCatalystAction>()
            }))
            try {
                assertEquals(
                    emptyList(),
                    fallbackExecutor.selectActions(actions, CatalystRequest(), RequestParameters()),
                )
            } finally {
                fallbackExecutor.close()
            }

            val notApplicableExecutor = CatalystExecutor(emptyList(), listOf(object : CatalystActionFilter {
                override val identity = "test:not-applicable"
                override fun select(
                    candidates: List<ResolvedCatalystAction>,
                    request: CatalystRequest,
                    parameters: RequestParameters,
                ): List<ResolvedCatalystAction>? = null
            }))
            try {
                assertEquals(
                    listOf("service-1", "service-2"),
                    notApplicableExecutor.selectActions(actions, CatalystRequest(), RequestParameters()).map { it.service.id },
                )
            } finally {
                notApplicableExecutor.close()
            }
        } finally {
            executor.close()
        }
    }

    @Test
    fun `filters run by priority then identity and reject out-of-snapshot selections`() {
        val locator = ServiceLocator("parcels", ServiceMethods.GATHER)
        val actions = resolver.resolve(locator, listOf(service("service-1"), service("service-2")))
        val selectedBy = mutableListOf<String>()
        fun filter(id: String, priority: Int, selection: (List<ResolvedCatalystAction>) -> List<ResolvedCatalystAction>) =
            object : CatalystActionFilter {
                override val identity = id
                override val priority = priority
                override fun select(
                    candidates: List<ResolvedCatalystAction>,
                    request: CatalystRequest,
                    parameters: RequestParameters,
                ): List<ResolvedCatalystAction>? {
                    selectedBy += identity
                    return selection(candidates)
                }
            }

        val executor = CatalystExecutor(
            emptyList(),
            listOf(
                filter("later", 20) { emptyList() },
                filter("first-b", 10) { emptyList() },
                filter("first-a", 10) { listOf(it.last()) },
            ),
        )
        try {
            assertEquals(
                listOf("service-2"),
                executor.selectActions(actions, CatalystRequest(), RequestParameters()).map { it.service.id },
            )
            assertEquals(listOf("first-a"), selectedBy)
        } finally {
            executor.close()
        }

        val invalidExecutor = CatalystExecutor(
            emptyList(),
            listOf(filter("invalid", 0) { listOf(service("not-visible").let { hidden ->
                val route = cloud.mallne.dicentra.aviator.model.AviatorServiceUtils
                    .extractServiceLocators(hidden.serviceDefinition).single().second
                ResolvedCatalystAction(hidden, route, locator)
            }) }),
        )
        try {
            assertFailsWith<CatalystInvalidFilterSelectionException> {
                invalidExecutor.selectActions(actions, CatalystRequest(), RequestParameters())
            }
        } finally {
            invalidExecutor.close()
        }
    }

    private fun service(
        id: String,
        wrapper: Boolean = false,
        multipleActions: Boolean = false,
    ): APIServiceDTO {
        fun action(operationId: String): Operation = Operation.build {
            this.operationId = operationId
            `x-dicentra-aviator-serviceDelegateCall` = ServiceLocator("parcels", ServiceMethods.GATHER)
            if (wrapper) {
                `x-dicentra-aviator-pluginMaterialization` = mapOf(
                    SynapsePlugin.identity to JsonObject(emptyMap()),
                )
            }
        }

        val get = action("GetParcels")
        val post = action("PostParcels")
        val document = OpenApiDoc.build {
            `x-dicentra-aviator` = AviatorExtensionSpec.SpecVersion
            servers { server("https://provider.example") }
            info = OpenApiInfo(title = id, version = "1.0.0")
        }.copy(
            paths = mapOf(
                "/parcels" to Value(
                    PathItem(
                        get = get,
                        post = if (multipleActions) post else null,
                    )
                ),
            )
        )
        return APIServiceDTO(id = id, serviceDefinition = document)
    }
}
