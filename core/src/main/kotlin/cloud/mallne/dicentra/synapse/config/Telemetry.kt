package cloud.mallne.dicentra.synapse.config

import io.ktor.server.application.*
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.OpenTelemetry as OpenTelemetryKotlin

@OptIn(ExperimentalApi::class)
object OpenTelemetryHolder {
    var openTelemetry: io.opentelemetry.api.OpenTelemetry? = null
        private set

    var openTelemetryKotlin: OpenTelemetryKotlin? = null
        private set

    fun initialize(openTelemetry: io.opentelemetry.api.OpenTelemetry) {
        this.openTelemetry = openTelemetry
    }

    fun setKotlinInstance(instance: OpenTelemetryKotlin) {
        this.openTelemetryKotlin = instance
    }
}

@OptIn(ExperimentalApi::class)
fun Application.configureTelemetry(defaultServiceName: String = "synapse") {
    val endpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
    if (endpoint.isNullOrBlank()) {
        log.info("OpenTelemetry disabled (OTEL_EXPORTER_OTLP_ENDPOINT not set)")
        return
    }

    val serviceName = System.getenv("OTEL_SERVICE_NAME") ?: defaultServiceName
    if (System.getenv("OTEL_SERVICE_NAME") == null) {
        System.setProperty("otel.service.name", serviceName)
    }

    val autoConfigured = AutoConfiguredOpenTelemetrySdk.initialize()
    val openTelemetry = autoConfigured.openTelemetrySdk

    install(KtorServerTelemetry) {
        setOpenTelemetry(openTelemetry)
    }

    OpenTelemetryAppender.install(openTelemetry)

    OpenTelemetryHolder.initialize(openTelemetry)

    Runtime.getRuntime().addShutdownHook(Thread { openTelemetry.close() })

    log.info("OpenTelemetry initialized — endpoint={}, service={}", endpoint, serviceName)
}
