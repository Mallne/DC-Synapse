package cloud.mallne.dicentra.synapse.config

import cloud.mallne.dicentra.synapse.model.IntrospectionResponse
import cloud.mallne.dicentra.synapse.model.SynapseConfig
import cloud.mallne.dicentra.synapse.statics.Client
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.routing.openapi.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

fun Application.configureSecurity() {

    // Get security settings and default to enabled if missing
    // See https://ktor.io/docs/server-jwt.html#configure-verifier
    val config = environment.config.getAs<SynapseConfig>()


    authentication {
        bearer {
            if (config.security.enabled) {
                val log = LoggerFactory.getLogger("Security")
                authenticate { jwt ->
                    val token = jwt.token
                    log.debug(
                        "Attempting introspection for token (first 10 chars): ${token.take(10)}..."
                    )
                    try {
                        val client = Client()
                        val responseBody = client.post(config.security.introspectionEndpoint) {
                            contentType(ContentType.Application.FormUrlEncoded)
                            // Client (this Ktor app) authenticates itself to the introspection endpoint
                            // using its client_id and client_secret
                            setBody(
                                listOf(
                                    "token" to token, // The token to be introspected
                                    "token_type_hint" to "access_token",
                                ).formUrlEncode()
                            )
                            header(HttpHeaders.Authorization, "Basic ${config.security.encodedCredentials()}")
                        }.bodyAsText()
                        log.debug("with body: $responseBody")

                        val jsonObject = Json.parseToJsonElement(responseBody).jsonObject
                        if (jsonObject["active"]?.jsonPrimitive?.booleanOrNull != true) {
                            return@authenticate null
                        }

                        val response = Json.decodeFromString<IntrospectionResponse>(responseBody)
                        log.debug("User ${response.name} requested a Resource")
                        response.toUser(
                            config = config.security,
                        )
                    } catch (e: Exception) {
                        log.error(e.message, e)
                    }
                }
            }
        }
    }

    registerBearerAuthSecurityScheme(bearerFormat = "JWT")
}