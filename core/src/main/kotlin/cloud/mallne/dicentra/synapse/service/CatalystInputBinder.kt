package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.execution.RequestParameter
import cloud.mallne.dicentra.aviator.core.execution.RequestParameters
import cloud.mallne.dicentra.aviator.koas.typed.Model
import cloud.mallne.dicentra.aviator.koas.typed.Constraints
import cloud.mallne.dicentra.aviator.koas.typed.Route
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystParameterCodecs
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystParameterValue
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystRequest
import cloud.mallne.dicentra.aviator.plugin.synapse.CatalystBodyFormat
import cloud.mallne.dicentra.aviator.core.io.RawRequestBody
import io.ktor.http.ContentType
import kotlin.io.encoding.Base64
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** Validates Catalyst values against the original action's declared inputs before Aviator runs. */
internal object CatalystInputBinder {
    fun serializedBody(request: CatalystRequest, route: Route): RawRequestBody? {
        val format = request.bodyFormat as? CatalystBodyFormat.Base64 ?: return null
        require(format.contentType.none { it.code < 32 || it.code == 127 }) { "Invalid body content type" }
        val contentType = try {
            ContentType.parse(format.contentType)
        } catch (cause: io.ktor.http.BadContentTypeFormatException) {
            throw IllegalArgumentException("Invalid body content type", cause)
        }
        require(contentType.withoutParameters() in listOf(ContentType.Application.Xml, ContentType.Text.Xml)) {
            "Serialized body contentType must be application/xml or text/xml"
        }
        require(route.body.types.keys.any { it.withoutParameters() == contentType.withoutParameters() }) {
            "The original action does not declare the XML media type"
        }
        val body = request.body as? JsonPrimitive ?: throw IllegalArgumentException("A base64 body must be a JSON string")
        require(body.isString) { "A base64 body must be a JSON string" }
        val base64 = body.content
        require(base64.length % 4 == 0 && base64.matches(Regex("[A-Za-z0-9+/]*={0,2}"))) {
            "Serialized body must contain standard padded base64"
        }
        val bytes = Base64.Default.decode(base64)
        require(bytes.isNotEmpty() && Base64.Default.encode(bytes) == base64) { "Serialized body base64 is empty or non-canonical" }
        return RawRequestBody(bytes, format.contentType)
    }

    fun bind(request: CatalystRequest, route: Route, json: Json, codecs: CatalystParameterCodecs): RequestParameters {
        require(request.version == 1) { "Unsupported Catalyst request version '${request.version}'" }
        val inputs = route.input.associateBy { it.name }
        val undeclaredInputs = request.typedParameters.keys - inputs.keys
        require(undeclaredInputs.isEmpty()) {
            "Catalyst request contains inputs not declared by the action: ${undeclaredInputs.sorted().joinToString()}"
        }
        require(inputs.values.filter { it.isRequired }.all { it.name in request.typedParameters }) {
            "Catalyst request is missing a required action input"
        }

        val parameters = mutableMapOf<String, RequestParameter>()
        request.typedParameters.forEach { (name, transport) ->
            val input = requireNotNull(inputs[name])
            require(transport.values.isNotEmpty() || transport.cardinality == CatalystParameterValue.Cardinality.MULTI) {
                "Single-valued Catalyst input must contain one value"
            }
            val schemaType = input.type
            val collectionType = schemaType as? Model.Collection.List
            val isArray = collectionType != null
            require((transport.cardinality == CatalystParameterValue.Cardinality.MULTI) == isArray) {
                "Catalyst input cardinality does not match the action schema"
            }
            if (transport.cardinality == CatalystParameterValue.Cardinality.SINGLE) {
                require(transport.values.size == 1) { "Single-valued Catalyst input must contain exactly one value" }
            }

            val declaredType = collectionType?.inner ?: schemaType
            collectionType?.constraint?.let { constraint ->
                require(transport.values.size in constraint.minItems..constraint.maxItems) {
                    "Catalyst collection input violates its declared item-count constraint"
                }
            }
            val values: List<Any?> = transport.values.map { value -> decodeValue(value, declaredType, transport.codec, json, codecs) }
            parameters[name] = when (transport.cardinality) {
                CatalystParameterValue.Cardinality.SINGLE -> RequestParameter.Single(values.single())
                CatalystParameterValue.Cardinality.MULTI -> RequestParameter.Multi(values)
            }
        }

        return RequestParameters(parameters)
    }

    fun validateBody(body: JsonElement?, route: Route, json: Json) {
        if (body == null) {
            require(!route.body.required) { "Catalyst request is missing a required action body" }
            return
        }
        require(body !== JsonNull) { "Explicit null action bodies are not supported" }
        val bodySchema = route.body[io.ktor.http.ContentType.Application.Json]
            ?: throw IllegalArgumentException("The original action does not declare a JSON-compatible request body")
        validateJsonShape(body, bodySchema.model, json)
    }

    private fun validateJsonShape(value: JsonElement, model: Model, json: Json) {
        when (model) {
            is Model.Primitive.String -> {
                val decoded = json.decodeFromJsonElement(String.serializer(), value)
                model.constraint?.let { constraint ->
                    require(decoded.length in constraint.minLength..constraint.maxLength)
                    constraint.pattern?.let { pattern -> require(Regex(pattern).containsMatchIn(decoded)) }
                }
            }
            is Model.Primitive.Boolean -> json.decodeFromJsonElement(Boolean.serializer(), value)
            is Model.Primitive.Int -> {
                val number = json.decodeFromJsonElement(Int.serializer(), value).toDouble()
                model.constraint?.let { require(it.accepts(number)) }
            }
            is Model.Primitive.Double -> {
                val number = json.decodeFromJsonElement(Double.serializer(), value)
                model.constraint?.let { require(it.accepts(number)) }
            }
            is Model.Primitive.Unit -> require(value === JsonNull)
            is Model.Enum.Closed -> {
                val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("Enum body value must be scalar")
                val stringValue = primitive.content
                require(stringValue in model.values) { "Body enum value is outside the declared values" }
            }
            is Model.Enum.Open -> json.decodeFromJsonElement(String.serializer(), value)
            is Model.Collection.List -> {
                val array = value as? JsonArray ?: throw IllegalArgumentException("Body value must be an array")
                model.constraint?.let { require(array.size in it.minItems..it.maxItems) }
                array.forEach { validateJsonShape(it, model.inner, json) }
            }
            is Model.Collection.Map -> {
                val obj = value as? JsonObject ?: throw IllegalArgumentException("Body value must be an object")
                model.constraint?.let { require(obj.size in it.minItems..it.maxItems) }
                obj.values.forEach { validateJsonShape(it, model.inner, json) }
            }
            is Model.Object -> {
                val obj = value as? JsonObject ?: throw IllegalArgumentException("Body value must be an object")
                model.properties.forEach { property ->
                    val propertyValue = obj[property.baseName]
                    if (propertyValue == null) {
                        require(!property.isRequired) { "Body is missing a required property" }
                    } else if (propertyValue === JsonNull) {
                        require(property.isNullable) { "Body property does not allow null" }
                    } else {
                        validateJsonShape(propertyValue, property.model, json)
                    }
                }
            }
            is Model.Union -> {
                require(model.cases.any { case -> runCatching { validateJsonShape(value, case.model, json) }.isSuccess }) {
                    "Body value does not match any declared union case"
                }
            }
            is Model.FreeFormJson -> if (value is JsonObject) {
                model.constraint?.let { constraint -> require(value.size in constraint.minProperties..constraint.maxProperties) }
            }
            is Model.OctetStream -> throw IllegalArgumentException("JSON body cannot satisfy an octet-stream schema")
        }
    }

    private fun decodeValue(
        value: JsonElement,
        declaredType: Model,
        codec: String?,
        json: Json,
        codecs: CatalystParameterCodecs,
    ): Any? {
        if (value === JsonNull) {
            val nullableUnion = declaredType as? Model.Union
            require(nullableUnion?.cases?.any { it.model is Model.Primitive.Unit } == true) {
                "Explicit null is not permitted for a non-nullable action input"
            }
            return null
        }
        val enumSchema = declaredType as? Model.Enum.Closed
        if (enumSchema != null) {
            val enumValue = (value as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: throw IllegalArgumentException("Closed enum inputs must be strings")
            require(enumValue in enumSchema.values) { "Catalyst enum input is outside the declared values" }
        }
        val type = when (declaredType) {
            is Model.Enum.Closed -> declaredType.inner
            is Model.Enum.Open -> Model.Primitive.String(null, null, null)
            else -> declaredType
        }
        return when (type) {
            is Model.Primitive.String -> {
                require(codec == null || codec == String.serializer().descriptor.serialName)
                val decoded = json.decodeFromJsonElement(String.serializer(), value)
                type.constraint?.let { constraint ->
                    require(decoded.length in constraint.minLength..constraint.maxLength)
                    constraint.pattern?.let { pattern -> require(Regex(pattern).containsMatchIn(decoded)) }
                }
                decoded
            }
            is Model.Primitive.Boolean -> {
                require(codec == null || codec == Boolean.serializer().descriptor.serialName)
                json.decodeFromJsonElement(Boolean.serializer(), value)
            }
            is Model.Primitive.Int -> {
                require(codec == null || codec == Int.serializer().descriptor.serialName)
                val decoded = json.decodeFromJsonElement(Int.serializer(), value)
                type.constraint?.let { constraint -> require(constraint.accepts(decoded.toDouble())) }
                decoded
            }
            is Model.Primitive.Double -> {
                require(codec == null || codec == Double.serializer().descriptor.serialName)
                val decoded = json.decodeFromJsonElement(Double.serializer(), value)
                type.constraint?.let { constraint -> require(constraint.accepts(decoded)) }
                decoded
            }
            is Model.Object, is Model.FreeFormJson, is Model.Union,
            is Model.Collection.Map, is Model.Collection.List -> {
                requireNotNull(codec) { "Structured Catalyst input requires a registered serializer codec" }
                validateJsonShape(value, type, json)
                codecs.decode(codec, json, value)
            }
            is Model.OctetStream -> throw IllegalArgumentException("JSON values cannot satisfy an octet-stream input")
            is Model.Primitive.Unit -> throw IllegalArgumentException("Unit is not a valid action parameter schema")
            is Model.Enum.Closed, is Model.Enum.Open -> throw IllegalArgumentException("Enum input schema could not be resolved")
        }
    }

    private fun Constraints.Number.accepts(value: Double): Boolean =
        (if (exclusiveMinimum) value > minimum else value >= minimum) &&
            (if (exclusiveMaximum) value < maximum else value <= maximum) &&
            multipleOf?.let { factor ->
                kotlin.math.abs(value / factor - (value / factor).toLong()) < 1e-9
            } ?: true
}
