package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.ServiceMethods
import cloud.mallne.dicentra.aviator.model.AviatorServiceUtils
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.aviator.koas.typed.Model
import cloud.mallne.dicentra.aviator.koas.typed.Route
import cloud.mallne.dicentra.aviator.koas.typed.Constraints
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystParameterCodecs
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystParameterValue
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystRequest
import io.ktor.openapi.*
import io.ktor.openapi.ReferenceOr.Value
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalystInputBinderTest {
    private val json = Json
    private val codecs = CatalystParameterCodecs(emptyList())

    @Test
    fun `binds schema typed single scalar`() {
        val result = bind(
            input("count", Model.Primitive.Int(null, null, null)),
            parameter("count", CatalystParameterValue.Cardinality.SINGLE, "7"),
        )

        assertEquals(7, (result.getValue("count") as cloud.mallne.dicentra.aviator.core.execution.RequestParameter.Single<*>).value)
    }

    @Test
    fun `binds array schema to multiple typed values`() {
        val result = bind(
            input("labels", Model.Collection.List(Model.Primitive.String(null, null, null), null, null, null)),
            parameter("labels", CatalystParameterValue.Cardinality.MULTI, "\"north\"", "\"south\""),
        )

        assertEquals(
            listOf("north", "south"),
            (result.getValue("labels") as cloud.mallne.dicentra.aviator.core.execution.RequestParameter.Multi<*>).value,
        )
    }

    @Test
    fun `rejects undeclared names mismatched cardinality and explicit null`() {
        val scalar = input("enabled", Model.Primitive.Boolean(null, null))

        assertFailsWith<IllegalArgumentException> {
            bind(scalar, parameter("other", CatalystParameterValue.Cardinality.SINGLE, "true"))
        }
        assertFailsWith<IllegalArgumentException> {
            bind(scalar, parameter("enabled", CatalystParameterValue.Cardinality.MULTI, "true"))
        }
        assertFailsWith<IllegalArgumentException> {
            bind(scalar, parameter("enabled", CatalystParameterValue.Cardinality.SINGLE, "null"))
        }
    }

    @Test
    fun `structured input requires a declared codec`() {
        assertFailsWith<IllegalArgumentException> {
            bind(input("filter", Model.FreeFormJson(null, null)), parameter("filter", CatalystParameterValue.Cardinality.SINGLE, "{}"))
        }
    }

    @Test
    fun `rejects missing required inputs and values outside schema constraints`() {
        val required = input("count", Model.Primitive.Int(null, null, Constraints.Number(false, 1.0, false, 5.0, null)))
        assertFailsWith<IllegalArgumentException> {
            CatalystInputBinder.bind(CatalystRequest(), route(required), json, codecs)
        }
        assertFailsWith<IllegalArgumentException> {
            bind(required, parameter("count", CatalystParameterValue.Cardinality.SINGLE, "6"))
        }
    }

    @Test
    fun `enforces string pattern length and list item-count constraints`() {
        val textInput = input("code", Model.Primitive.String(null, null, Constraints.Text(2, 4, "^[a-z]+$")))
        assertFailsWith<IllegalArgumentException> {
            bind(textInput, parameter("code", CatalystParameterValue.Cardinality.SINGLE, "\"A\""))
        }
        assertFailsWith<IllegalArgumentException> {
            bind(textInput, parameter("code", CatalystParameterValue.Cardinality.SINGLE, "\"longer\""))
        }

        val listInput = input(
            "labels",
            Model.Collection.List(
                Model.Primitive.String(null, null, null), null, null, Constraints.Collection(1, 2),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            bind(listInput, parameter("labels", CatalystParameterValue.Cardinality.MULTI))
        }
        assertFailsWith<IllegalArgumentException> {
            bind(listInput, parameter("labels", CatalystParameterValue.Cardinality.MULTI, "\"a\"", "\"b\"", "\"c\""))
        }
    }

    @Test
    fun `binds nested list inputs through an explicitly registered codec`() {
        val nestedSerializer = ListSerializer(Int.serializer())
        val nestedSchema = Model.Collection.List(
            Model.Collection.List(Model.Primitive.Int(null, null, null), null, null, null),
            null,
            null,
            null,
        )
        val route = route(input("matrix", nestedSchema))
        val request = CatalystRequest(
            typedParameters = mapOf(
                "matrix" to CatalystParameterValue(
                    CatalystParameterValue.Cardinality.MULTI,
                    listOf(json.parseToJsonElement("[2,3]")),
                    nestedSerializer.descriptor.serialName,
                ),
            ),
        )

        val result = CatalystInputBinder.bind(
            request,
            route,
            json,
            CatalystParameterCodecs(listOf(nestedSerializer)),
        )

        assertEquals(
            listOf(listOf(2, 3)),
            (result.getValue("matrix") as cloud.mallne.dicentra.aviator.core.execution.RequestParameter.Multi<*>).value,
        )
    }

    @Test
    fun `binds provider plugin inputs absent from upstream OpenAPI under explicit contract`() {
        val contract = CatalystParameterContract(
            name = "pluginFlag",
            cardinality = CatalystParameterValue.Cardinality.SINGLE,
            serializer = Boolean.serializer(),
        )
        val request = CatalystRequest(
            typedParameters = mapOf(
                "pluginFlag" to CatalystParameterValue(
                    CatalystParameterValue.Cardinality.SINGLE,
                    listOf(JsonPrimitive(true)),
                    Boolean.serializer().descriptor.serialName,
                ),
            ),
        )
        val targetRoute = route(input("ordinary", Model.Primitive.String(null, null, null))).copy(input = emptyList())

        val parameters = CatalystInputBinder.bind(
            request,
            targetRoute,
            json,
            codecs,
            mapOf(contract.name to contract),
        )

        assertEquals(true, (parameters.getValue("pluginFlag") as cloud.mallne.dicentra.aviator.core.execution.RequestParameter.Single<*>).value)
        assertFailsWith<IllegalArgumentException> {
            CatalystInputBinder.bind(
                request.copy(typedParameters = request.typedParameters + ("unregistered" to request.typedParameters.getValue("pluginFlag"))),
                targetRoute,
                json,
                codecs,
                mapOf(contract.name to contract),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CatalystInputBinder.bind(
                request.copy(typedParameters = request.typedParameters.mapValues { (_, value) -> value.copy(codec = String.serializer().descriptor.serialName) }),
                targetRoute,
                json,
                codecs,
                mapOf(contract.name to contract),
            )
        }
    }

    @Test
    fun `path item parameter declarations are represented in route input metadata`() {
        val locator = ServiceLocator("path-level-input", ServiceMethods.GATHER)
        val operation = Operation.build {
            operationId = "PathLevelInput"
            `x-dicentra-aviator-serviceDelegateCall` = locator
        }
        val pathParameter = Parameter(
            name = "count",
            `in` = ParameterType.query,
            required = true,
            schema = Value(JsonSchema(type = JsonType.INTEGER)),
        )
        val doc = OpenApiDoc.build {
            `x-dicentra-aviator` = AviatorExtensionSpec.SpecVersion
            servers { server("https://provider.example") }
            info = OpenApiInfo(title = "Path input", version = "1")
        }.copy(paths = mapOf("/input" to Value(PathItem(get = operation, parameters = listOf(Value(pathParameter))))))

        val route = AviatorServiceUtils.extractServiceLocators(doc).single().second
        assertEquals("count", route.input.single().name)
    }

    @Test
    fun `validates required JSON bodies against the original action schema`() {
        val base = route(input("unused", Model.Primitive.String(null, null, null)))
        val bodyRoute = base.copy(
            input = emptyList(),
            body = Route.Bodies(
                required = true,
                types = mapOf(
                    io.ktor.http.ContentType.Application.Json to Route.Bodies.Body(
                        model = Model.Primitive.Int(null, null, null),
                        schema = null,
                        parameters = emptyMap(),
                    ),
                ),
                extensions = emptyMap(),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            CatalystInputBinder.validateBody(null, bodyRoute, json)
        }
        assertFailsWith<IllegalArgumentException> {
            CatalystInputBinder.validateBody(JsonPrimitive("not-an-int"), bodyRoute, json)
        }
        CatalystInputBinder.validateBody(JsonPrimitive(42), bodyRoute, json)
    }

    private fun bind(input: Route.Input, vararg parameters: Pair<String, CatalystParameterValue>) =
        CatalystInputBinder.bind(
            CatalystRequest(typedParameters = parameters.toMap()),
            route(input),
            json,
            codecs,
        )

    private fun input(name: String, type: Model) = Route.Input(name, type, true, ParameterType.query, null)

    private fun parameter(name: String, cardinality: CatalystParameterValue.Cardinality, vararg jsonValues: String) =
        name to CatalystParameterValue(cardinality, jsonValues.map(json::parseToJsonElement))

    private fun route(input: Route.Input): Route {
        val locator = ServiceLocator("input-test", ServiceMethods.GATHER)
        val operation = Operation.build {
            operationId = "InputTest"
            `x-dicentra-aviator-serviceDelegateCall` = locator
        }
        val doc = OpenApiDoc.build {
            `x-dicentra-aviator` = AviatorExtensionSpec.SpecVersion
            servers { server("https://provider.example") }
            info = OpenApiInfo(title = "Input test", version = "1")
        }.copy(paths = mapOf("/input" to Value(PathItem(get = operation))))
        return AviatorServiceUtils.extractServiceLocators(doc).single().second.copy(input = listOf(input))
    }
}
