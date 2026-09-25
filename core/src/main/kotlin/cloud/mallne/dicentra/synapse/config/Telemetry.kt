package cloud.mallne.dicentra.synapse.config

import io.ktor.server.application.*
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import io.ktor.server.config.*
import cloud.mallne.dicentra.synapse.model.SynapseConfig

fun Application.configureTelemetry() {
    val telemetry = environment.config.getAs<SynapseConfig>().telemetry
    val endpoint = telemetry.endpoint
    if (endpoint.isBlank()) {
        log.info("OpenTelemetry disabled (telemetry.endpoint not set)")
        return
    }

    val serviceName = telemetry.serviceName

    val autoConfigured = AutoConfiguredOpenTelemetrySdk.builder()
        .addPropertiesSupplier {
            mapOf(
                "otel.exporter.otlp.endpoint" to endpoint,
                "otel.service.name" to serviceName,
            )
        }
        .build()
    val openTelemetry = autoConfigured.openTelemetrySdk

    install(KtorServerTelemetry) {
        setOpenTelemetry(openTelemetry)
    }

    OpenTelemetryAppender.install(openTelemetry)

    Runtime.getRuntime().addShutdownHook(Thread { openTelemetry.close() })

    log.info("OpenTelemetry initialized — endpoint={}, service={}", endpoint, serviceName)
}
