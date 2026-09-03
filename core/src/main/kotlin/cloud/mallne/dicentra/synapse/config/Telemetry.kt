package cloud.mallne.dicentra.synapse.config

import io.ktor.server.application.*
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk

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

    Runtime.getRuntime().addShutdownHook(Thread { openTelemetry.close() })

    log.info("OpenTelemetry initialized — endpoint={}, service={}", endpoint, serviceName)
}
