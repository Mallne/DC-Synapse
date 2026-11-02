package cloud.mallne.dicentra.synapse.config

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CorsConfigurationTest {
    @Test
    fun `environment-style scalar hosts are split trimmed and deduplicated`() {
        val config = MapApplicationConfig(
            "server.cors.hosts" to " localhost:8080, demo.example,localhost:8080, ",
        )
        assertEquals(listOf("localhost:8080", "demo.example"), config.corsHosts())
    }

    @Test
    fun `native configuration lists remain supported`() {
        val config = MapApplicationConfig().apply {
            put("server.cors.hosts", listOf("localhost:8080", "demo.example"))
        }
        assertEquals(listOf("localhost:8080", "demo.example"), config.corsHosts())
        assertEquals(emptyList(), MapApplicationConfig().corsHosts())
        assertEquals(emptyList(), MapApplicationConfig("server.cors.hosts" to "").corsHosts())
    }

    @Test
    fun `scalar host allows browser discovery and preflight but rejects other origins`() = testApplication {
        application {
            val config = MapApplicationConfig("server.cors.hosts" to "localhost:8080")
            configureCatalystCors(config.corsHosts())
            routing { get("/services") { call.respondText("ok") } }
        }
        val origin = "http://localhost:8080"
        val response = client.get("/services") { header(HttpHeaders.Origin, origin) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(origin, response.headers[HttpHeaders.AccessControlAllowOrigin])

        val preflight = client.options("/services") {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "content-type")
        }
        assertEquals(HttpStatusCode.OK, preflight.status)
        assertEquals(origin, preflight.headers[HttpHeaders.AccessControlAllowOrigin])

        val rejected = client.get("/services") { header(HttpHeaders.Origin, "http://localhost:9999") }
        assertEquals(HttpStatusCode.Forbidden, rejected.status)
        assertNull(rejected.headers[HttpHeaders.AccessControlAllowOrigin])
    }
}
