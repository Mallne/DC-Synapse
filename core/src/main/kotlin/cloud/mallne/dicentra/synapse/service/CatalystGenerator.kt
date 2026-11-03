package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-pluginMaterialization`
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.koas.typed.Model
import cloud.mallne.dicentra.aviator.koas.typed.Route
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.aviator.plugin.adapter.json.JsonAdapter
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystRequest
import cloud.mallne.dicentra.aviator.plugin.synapse.SynapsePlugin
import cloud.mallne.dicentra.aviator.plugin.synapse.SynapsePluginConfig
import cloud.mallne.dicentra.synapse.model.SynapseConfig
import cloud.mallne.dicentra.synapse.statics.Serialization
import io.ktor.http.*
import io.ktor.openapi.*
import io.ktor.openapi.KotlinxSerializerJsonSchemaInference.Companion.Default
import io.ktor.openapi.ReferenceOr.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class CatalystGenerator(
    @Provided val configuration: SynapseConfig,
    val json: Json = Serialization(),
) {
    fun generateFor(
        route: Route,
    ): Pair<String, PathItem> {
        val rawLocator = route.`x-dicentra-aviator-serviceDelegateCall`
        val locator =
            rawLocator?.let { ServiceLocator(it) } ?: throw IllegalArgumentException("Service Locator is required")
        val actionSelector = CatalystActionSelector.encode(locator)
        // The generated client calls Catalyst, so only materialize its receiving plugin here.
        // Provider plugins and private service options are retained on the original server definition.
        val plugins = mapOf(
            SynapsePlugin.identity to json.encodeToJsonElement(SynapsePluginConfig(active = true)),
            // The receiving action body is the Catalyst envelope and must be serialized as JSON.
            JsonAdapter.identity to kotlinx.serialization.json.JsonObject(emptyMap()),
        )
        return "/catalyst/$actionSelector" to PathItem(
            post = Operation.build {
                summary = "Execute ${route.operationId ?: locator.toString()} through Synapse Catalyst"
                operationId = "Catalyst_$actionSelector"
                `x-dicentra-aviator-serviceDelegateCall` = locator
                `x-dicentra-aviator-pluginMaterialization` = plugins
                responses {
                    response(200) {
                        description = "Ordered array of results from selected original Aviator actions."
                        ContentType.Application.Json {
                            schema = catalystResponseSchema(route)
                        }
                    }
                    response(400) { description = "The request envelope or typed action input is invalid." }
                    response(404) { description = "No caller-visible action matches the selector or identity." }
                    response(413) { description = "The request or selected target count exceeds its configured limit." }
                    response(415) { description = "The request content type must be application/json." }
                    response(500) {
                        description = "Catalyst could not complete the action."
                        ContentType.Application.Json { schema = Default.jsonSchema<Map<String, String>>() }
                    }
                    response(502) {
                        description = "One or more upstream actions failed or the aggregate exceeded its limit."
                        ContentType.Application.Json { schema = Default.jsonSchema<Map<String, String>>() }
                    }
                    response(503) {
                        description = "A required provider plugin is unavailable."
                        ContentType.Application.Json { schema = Default.jsonSchema<Map<String, String>>() }
                    }
                    response(504) {
                        description = "Catalyst execution exceeded its configured deadline."
                        ContentType.Application.Json { schema = Default.jsonSchema<Map<String, String>>() }
                    }
                }
            }.copy(
                requestBody = Value(
                    RequestBody(
                        description = "JSON envelope: body carries the original JSON value by default. For serialized " +
                            "bodies, set bodyFormat to {\"type\":\"base64\",\"contentType\":\"application/xml\"} " +
                            "and body to a JSON string containing canonical standard padded base64 of the exact bytes. " +
                            "Only application/xml or text/xml declared by the original action is currently supported. " +
                            "XML is never parsed or reserialized. The entire encoded envelope " +
                            "counts toward the request byte limit.",
                        required = true,
                        content = mapOf(
                            ContentType.Application.Json to MediaType(
                                schema = Value(catalystRequestSchema(route))
                            )
                        )
                    )
                )
            )
        )
    }

    private fun catalystRequestSchema(route: Route): JsonSchema {
        val envelope = Default.jsonSchema<CatalystRequest>()
        val properties = envelope.properties.orEmpty().toMutableMap()
        properties["typedParameters"] = Value(typedParametersSchema(route))
        val bodySchemas = route.body.types.mapNotNull { (contentType, body) ->
            when (contentType.withoutParameters()) {
                ContentType.Application.Json -> body.schema ?: modelSchema(body.model)
                ContentType.Application.Xml, ContentType.Text.Xml -> JsonSchema(
                    type = JsonType.STRING,
                    format = "byte",
                    description = "Canonical standard padded base64 of the original serialized body bytes.",
                )
                else -> null
            }
        }
        if (bodySchemas.isNotEmpty()) {
            properties["body"] = Value(
                if (bodySchemas.size == 1) bodySchemas.single()
                else JsonSchema(anyOf = bodySchemas.map { Value(it) }),
            )
        }
        val required = envelope.required.orEmpty().toMutableList().apply {
            if (route.input.any { it.isRequired && !it.type.hasDefault() } && "typedParameters" !in this) {
                add("typedParameters")
            }
            if (route.body.required && bodySchemas.isNotEmpty() && "body" !in this) add("body")
        }
        return envelope.copy(properties = properties, required = required.takeIf { it.isNotEmpty() })
    }

    private fun typedParametersSchema(route: Route): JsonSchema {
        val properties = route.input.associate { input ->
            val model = input.type
            val listModel = model as? Model.Collection.List
            val isMulti = listModel != null
            val valueSchema = listModel?.let { modelSchema(it.inner) } ?: modelSchema(model)
            val parameterProperties = mapOf(
                "cardinality" to Value(JsonSchema(
                    type = JsonType.STRING,
                    enum = listOf(
                        GenericElementWrapper(if (isMulti) "MULTI" else "SINGLE", String.serializer()),
                    ),
                )),
                "values" to Value(JsonSchema(
                    type = JsonType.ARRAY,
                    items = Value(valueSchema),
                    minItems = if (isMulti) listModel?.constraint?.minItems ?: 0 else 1,
                    maxItems = if (isMulti) listModel?.constraint?.maxItems?.takeIf { it != Int.MAX_VALUE } else 1,
                )),
                "codec" to Value(JsonSchema(type = JsonType.STRING)),
            )
            val inputProperty = JsonSchema(
                type = JsonType.OBJECT,
                properties = parameterProperties,
                required = listOf("cardinality", "values"),
                additionalProperties = AdditionalProperties.Allowed(false),
                description = input.description,
            )
            input.name to Value(inputProperty)
        }
        val requiredInputs = route.input.filter { it.isRequired && !it.type.hasDefault() }.map { it.name }
        return JsonSchema(
            type = JsonType.OBJECT,
            properties = properties,
            required = requiredInputs.takeIf { it.isNotEmpty() },
            additionalProperties = AdditionalProperties.Allowed(properties.isEmpty()),
            maxProperties = 0.takeIf { properties.isEmpty() },
            description = "Typed logical inputs declared by the original action.",
        )
    }

    private fun catalystResponseSchema(route: Route): JsonSchema {
        val responseSchemas = route.returnType.types.entries
            .filter { it.key.value in 200..299 }
            .mapNotNull { (_, response) -> response.types[ContentType.Application.Json] }
            .map { it.schema ?: modelSchema(it.model) }
        val itemSchemas = responseSchemas.flatMap { schema ->
            if (schema.type == JsonType.ARRAY) listOf(schema.items?.valueOrNull() ?: JsonSchema())
            else listOf(schema)
        }
            .distinct()
        val itemSchema = when (itemSchemas.size) {
            0 -> return Default.jsonSchema<List<JsonElement>>()
            1 -> itemSchemas.single()
            else -> JsonSchema(anyOf = itemSchemas.map(::Value))
        }
        return JsonSchema(type = JsonType.ARRAY, items = Value(itemSchema))
    }

    private fun Model.hasDefault(): Boolean = when (this) {
        is Model.Primitive.String -> default != null
        is Model.Primitive.Int -> default != null
        is Model.Primitive.Double -> default != null
        is Model.Primitive.Boolean -> default != null
        is Model.Enum.Closed -> default != null
        is Model.Enum.Open -> default != null
        is Model.Collection.List -> default != null
        is Model.Union -> default != null
        else -> false
    }

    private fun modelSchema(model: Model): JsonSchema = when (model) {
        is Model.Primitive.String -> JsonSchema(type = JsonType.STRING, description = model.description,
            default = model.default?.let { GenericElementWrapper(it, String.serializer()) },
            minLength = model.constraint?.minLength, maxLength = model.constraint?.maxLength, pattern = model.constraint?.pattern)
        is Model.Primitive.Int -> {
            val constraint = model.constraint
            JsonSchema(type = JsonType.INTEGER, description = model.description,
                default = model.default?.let { GenericElementWrapper(it, Int.serializer()) },
                minimum = constraint?.minimum?.takeIf { it.isFinite() && !constraint.exclusiveMinimum },
                exclusiveMinimum = constraint?.minimum?.takeIf { it.isFinite() && constraint.exclusiveMinimum },
                maximum = constraint?.maximum?.takeIf { it.isFinite() && !constraint.exclusiveMaximum },
                exclusiveMaximum = constraint?.maximum?.takeIf { it.isFinite() && constraint.exclusiveMaximum },
                multipleOf = constraint?.multipleOf)
        }
        is Model.Primitive.Double -> {
            val constraint = model.constraint
            JsonSchema(type = JsonType.NUMBER, description = model.description,
                default = model.default?.let { GenericElementWrapper(it, Double.serializer()) },
                minimum = constraint?.minimum?.takeIf { it.isFinite() && !constraint.exclusiveMinimum },
                exclusiveMinimum = constraint?.minimum?.takeIf { it.isFinite() && constraint.exclusiveMinimum },
                maximum = constraint?.maximum?.takeIf { it.isFinite() && !constraint.exclusiveMaximum },
                exclusiveMaximum = constraint?.maximum?.takeIf { it.isFinite() && constraint.exclusiveMaximum },
                multipleOf = constraint?.multipleOf)
        }
        is Model.Primitive.Boolean -> JsonSchema(type = JsonType.BOOLEAN, description = model.description,
            default = model.default?.let { GenericElementWrapper(it, Boolean.serializer()) })
        is Model.Primitive.Unit -> JsonSchema(type = JsonType.NULL, description = model.description)
        is Model.Collection.List -> JsonSchema(type = JsonType.ARRAY, description = model.description,
            items = Value(modelSchema(model.inner)), minItems = model.constraint?.minItems, maxItems = model.constraint?.maxItems)
        is Model.Collection.Map -> JsonSchema(type = JsonType.OBJECT, description = model.description,
            additionalProperties = AdditionalProperties.PSchema(Value(modelSchema(model.inner))),
            minProperties = model.constraint?.minItems, maxProperties = model.constraint?.maxItems)
        is Model.Object -> {
            val fields = model.properties.associate { property ->
                property.baseName to Value(if (property.isNullable) nullableSchema(property.model) else modelSchema(property.model))
            }
            JsonSchema(type = JsonType.OBJECT, description = model.description, properties = fields,
                required = model.properties.filter { it.isRequired }.map { it.baseName }.takeIf { it.isNotEmpty() },
                additionalProperties = AdditionalProperties.Allowed(false))
        }
        is Model.Enum.Closed -> JsonSchema(type = JsonType.STRING, description = model.description,
            enum = model.values.map { GenericElementWrapper(it, String.serializer()) })
        is Model.Enum.Open -> JsonSchema(type = JsonType.STRING, description = model.description)
        is Model.Union -> JsonSchema(anyOf = model.cases.map { Value(modelSchema(it.model)) }, description = model.description)
        is Model.FreeFormJson -> JsonSchema(type = JsonType.OBJECT, description = model.description,
            minProperties = model.constraint?.minProperties, maxProperties = model.constraint?.maxProperties)
        is Model.OctetStream -> JsonSchema(type = JsonType.STRING, format = "binary", description = model.description)
    }

    private fun nullableSchema(model: Model): JsonSchema = JsonSchema(
        anyOf = listOf(Value(modelSchema(model)), Value(JsonSchema(type = JsonType.NULL))),
        description = model.description,
    )

    fun generateWrapper(paths: List<Pair<String, PathItem>>): OpenApiDoc {
        val schema = if (configuration.catalyst.tlsEnabled) "https://" else "http://"
        return OpenApiDoc.build {
            `x-dicentra-aviator` = AviatorExtensionSpec.SpecVersion
            servers {
                server(url = schema + configuration.catalyst.serverName)
            }
            info = OpenApiInfo(
                title = configuration.catalyst.title,
                description = configuration.catalyst.description,
                version = AviatorExtensionSpec.SpecVersion
            )
        }.copy(paths = paths.associate { it.first to it.second.let(::Value) })
    }
}
