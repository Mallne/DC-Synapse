package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec
import cloud.mallne.dicentra.aviator.core.AviatorExtensionSpec.`x-dicentra-aviator-serviceDelegateCall`
import cloud.mallne.dicentra.aviator.core.InflatedServiceOptions
import cloud.mallne.dicentra.aviator.koas.Components
import cloud.mallne.dicentra.aviator.koas.OpenAPI
import cloud.mallne.dicentra.aviator.koas.Operation
import cloud.mallne.dicentra.aviator.koas.PathItem
import cloud.mallne.dicentra.aviator.koas.extensions.ReferenceOr
import cloud.mallne.dicentra.aviator.koas.info.Info
import cloud.mallne.dicentra.aviator.koas.parameters.Parameter
import cloud.mallne.dicentra.aviator.koas.security.SecurityRequirement
import cloud.mallne.dicentra.aviator.koas.security.SecurityScheme
import cloud.mallne.dicentra.aviator.koas.servers.Server
import cloud.mallne.dicentra.aviator.model.ServiceLocator
import cloud.mallne.dicentra.synapse.model.Configuration
import cloud.mallne.dicentra.synapse.statics.Serialization
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single

@Single
class DiscoveryGenerator(
    val configuration: Configuration,
    val json: Json = Serialization(),
) {
    val memory = DiscoveryDSLBuilder(this)

    fun generate(
        path: String,
        operations: Map<HttpMethod, Operation>,
    ): Pair<String, PathItem> {
        var pathItem = PathItem()
        operations.forEach { (method, operation) ->
            pathItem = overridePathItemByMethod(method, operation, pathItem)
        }
        return path to pathItem
    }

    fun operation(
        id: String,
        locator: ServiceLocator,
        summary: String? = null,
        description: String? = null,
        parameter: List<Parameter> = emptyList(),
        options: InflatedServiceOptions = InflatedServiceOptions.empty,
        authenticationStrategy: AuthenticationStrategy = AuthenticationStrategy.NONE,
    ): Operation {

        parameter.toMutableList()

        val securityReq: List<SecurityRequirement> = when (authenticationStrategy) {
            AuthenticationStrategy.MANDATORY -> {
                listOf(mapOf("bearerAuth" to configuration.security.scopes.split(",")))
            }

            AuthenticationStrategy.OPTIONAL -> {
                listOf(mapOf("bearerAuth" to configuration.security.scopes.split(",")), emptyMap())
            }

            AuthenticationStrategy.NONE -> emptyList()
        }

        return Operation(
            operationId = id,
            parameters = parameter.map { ReferenceOr.Value(it) },
            summary = summary,
            description = description,
            extensions = mapOf(
                AviatorExtensionSpec.ServiceLocator.O.key to locator.usable(),
                AviatorExtensionSpec.ServiceOptions.O.key to options.usable(),
            ),
            security = securityReq,
        )
    }

    fun generateWrapper(paths: List<Pair<String, PathItem>>): OpenAPI {
        val schema = if (configuration.server.tlsEnabled) "https://" else "http://"
        val sec = if (configuration.security.enabled) {
            mapOf(
                "bearerAuth" to ReferenceOr.Value(
                    SecurityScheme(
                        type = SecurityScheme.Type.HTTP,
                        scheme = "Bearer",
                        bearerFormat = "JWT"
                    )
                )
            )
        } else emptyMap()

        val cleanedPaths = paths.map { (path, pathItem) ->
            var resultingPath = pathItem
            for ((method, operation) in pathItem.operations()) {
                val locator = operation.`x-dicentra-aviator-serviceDelegateCall`?.let { ServiceLocator(it) }
                if (locator != null && locator.toString() in configuration.server.discoveryExclusions) {
                    resultingPath = overridePathItemByMethod(method, Operation(), resultingPath)
                }
            }
            path to resultingPath
        }

        return OpenAPI(
            extensions = mapOf(
                AviatorExtensionSpec.Version.key to json.parseToJsonElement(
                    AviatorExtensionSpec.SpecVersion
                )
            ),
            servers = listOf(
                Server(
                    url = schema + configuration.server.hostname,
                )
            ),
            info = Info(
                title = configuration.server.info,
                description = configuration.server.description,
                version = AviatorExtensionSpec.SpecVersion
            ),
            paths = cleanedPaths.toMap(),
            components = Components(
                securitySchemes = sec
            )
        )
    }

    fun create(dsl: DiscoveryDSL.() -> Unit): OpenAPI {
        val dslBuilder = DiscoveryDSLBuilder(this)
        dslBuilder.dsl()
        return dslBuilder.build()
    }

    fun memorize(dsl: DiscoveryDSL.() -> Unit) {
        memory.dsl()
    }

    fun overridePathItemByMethod(method: HttpMethod, operation: Operation, src: PathItem): PathItem {
        var dest = src
        when (method) {
            HttpMethod.Get -> dest = dest.copy(get = operation)
            HttpMethod.Put -> dest = dest.copy(put = operation)
            HttpMethod.Post -> dest = dest.copy(post = operation)
            HttpMethod.Delete -> dest = dest.copy(delete = operation)
            HttpMethod.Patch -> dest = dest.copy(patch = operation)
            HttpMethod.Head -> dest = dest.copy(head = operation)
            HttpMethod.Options -> dest = dest.copy(options = operation)
            HttpMethod("TRACE") -> dest = dest.copy(trace = operation)
            else -> {}
        }
        return dest
    }

    companion object {
        @Serializable
        enum class AuthenticationStrategy {
            MANDATORY, OPTIONAL, NONE
        }

        interface DiscoveryDSL {
            fun path(
                path: String,
                dsl: OperationDiscoveryDSL.() -> Unit,
            )
        }

        interface OperationDiscoveryDSL {
            fun operation(
                id: String,
                method: HttpMethod,
                locator: ServiceLocator,
                summary: String? = null,
                description: String? = null,
                parameter: List<Parameter> = emptyList(),
                options: InflatedServiceOptions = InflatedServiceOptions.empty,
                authenticationStrategy: AuthenticationStrategy = AuthenticationStrategy.NONE,
            )
        }

        data class OperationDiscoveryDSLBuilder(
            private val generator: DiscoveryGenerator,
            val operations: MutableMap<HttpMethod, Operation> = mutableMapOf()
        ) : OperationDiscoveryDSL {
            override fun operation(
                id: String,
                method: HttpMethod,
                locator: ServiceLocator,
                summary: String?,
                description: String?,
                parameter: List<Parameter>,
                options: InflatedServiceOptions,
                authenticationStrategy: AuthenticationStrategy
            ) {
                operations[method] =
                    generator.operation(id, locator, summary, description, parameter, options, authenticationStrategy)
            }

        }

        data class DiscoveryDSLBuilder(
            private val generator: DiscoveryGenerator,
            private val paths: MutableList<Pair<String, PathItem>> = mutableListOf()
        ) : DiscoveryDSL {
            fun build(): OpenAPI {
                return generator.generateWrapper(paths)
            }

            override fun path(
                path: String,
                dsl: OperationDiscoveryDSL.() -> Unit,
            ) {
                val dslBuilder = OperationDiscoveryDSLBuilder(generator)
                dslBuilder.dsl()
                paths.add(generator.generate(path, dslBuilder.operations))
            }
        }
    }
}