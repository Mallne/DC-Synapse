plugins {
    alias(libs.plugins.kjvm)
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.ktor)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.koin.compiler)
}

group = "cloud.mallne.dicentra.synapse"
version = project.findProperty("VERSION_NAME") ?: "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

dependencies {
    //Koin
    api(libs.koin.ktor)
    api(libs.koin.logger.slf4j)
    api(libs.koin.annotations)
    //Ktor
    api(libs.ktor.server.core)
    api(libs.ktor.serialization.kotlinx.json)
    api(libs.ktor.server.content.negotiation)
    api(libs.ktor.server.request.validation)
    api(libs.ktor.server.auto.head.response)
    api(libs.ktor.server.auth)
    api(libs.ktor.server.auth.jwt)
    api(libs.ktor.client.core)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.client.apache)
    api(libs.ktor.client.auth)
    api(libs.ktor.server.forwarded.header)
    api(libs.ktor.server.cors)
    api(libs.ktor.server.caching.headers)
    api(libs.ktor.server.compression)
    api(libs.ktor.server.netty)
    api(libs.ktor.server.config.yaml)
    api(libs.ktor.server.openapi)
    // Exposed
    api(libs.exposed.core)
    api(libs.exposed.rdbc)
    api(libs.exposed.json)
    api(libs.exposed.datetime)
    api(libs.exposed.migrations.r2dbc)
    // Database
    api(libs.postgres)
    api(libs.flyway)
    runtimeOnly(libs.flyway.pg)
    runtimeOnly(libs.postgres.jdbc)
    // Other
    api(libs.mcp)
    api(libs.logback.classic)
    api(libs.kotlinx.datetime)
    //OpenTelemetry
    api(libs.opentelemetry.ktor)
    api(libs.opentelemetry.kotlin)
    api(libs.opentelemetry.logback)
    api(libs.opentelemetry.autoconfigure)
    implementation(libs.opentelemetry.kotlin.core)
    //aviator
    api(libs.dc.aviator.client.ktor)
    api(libs.dc.aviator.client.mock)
    api(libs.dc.aviator.adapter.xml)
    api(libs.dc.aviator.adapter.json)
    api(libs.dc.aviator.plugin.interception)
    api(libs.dc.aviator.plugin.weaver)
    api(libs.dc.aviator.plugin.synapse)
    api(libs.dc.aviator.plugin.otel)
    api(libs.dc.polyfill)
}


koinCompiler {
    userLogs = true
    compileSafety = true //the configuration gets injected with the application as a non Koin param https://github.com/InsertKoinIO/koin-compiler-plugin/issues/7
}

mavenPublishing {
    publishing {
        repositories {
            maven {
                name = "DiCentraArtefacts"
                url = uri("https://registry.mallne.cloud/repository/DiCentraArtefacts/")
                credentials {
                    username = providers.environmentVariable("NEXUS_USERNAME").getOrElse("")
                    password = providers.environmentVariable("NEXUS_PASSWORD").getOrElse("")
                }
            }
        }
    }

    coordinates(group.toString(), project.name)
    pom {
        name = "DiCentra Synapse Core"
        inceptionYear = "2025"
        developers {
            developer {
                name = "Mallne"
                url = "mallne.cloud"
            }
        }
    }
}