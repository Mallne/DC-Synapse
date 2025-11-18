pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Synapse"

include(":core")
include(":host")

val aviatorDir = file("../aviator")
if (aviatorDir.exists()) {
    includeBuild(aviatorDir.absolutePath) {
        dependencySubstitution {
            substitute(module("cloud.mallne.dicentra.aviator.plugin:interception")).using(project(":plugins:interception"))
            substitute(module("cloud.mallne.dicentra.aviator.plugin.adapter:adapter-xml")).using(project(":plugins:adapter-xml"))
            substitute(module("cloud.mallne.dicentra.aviator.plugin.adapter:adapter-json")).using(project(":plugins:adapter-json"))
            substitute(module("cloud.mallne.dicentra.aviator.plugin:weaver")).using(project(":plugins:weaver"))
            substitute(module("cloud.mallne.dicentra.aviator.plugin:synapse")).using(project(":plugins:synapse"))
            substitute(module("cloud.mallne.dicentra.aviator.client:ktor")).using(project(":clients:ktor"))
            substitute(module("cloud.mallne.dicentra.aviator.client:mock")).using(project(":clients:mock"))
        }
    }
} else {
    println("[SYNAPSE:aviator] This Project seems to be running without the Monorepo Context, please consider using the Monorepo")
}

val polyfillDir = file("../polyfill")
if (polyfillDir.exists()) {
    includeBuild(polyfillDir.absolutePath) {
        dependencySubstitution {
            substitute(module("cloud.mallne.dicentra:polyfill")).using(project(":"))
        }
    }
} else {
    println("[SYNAPSE:polyfill] This Project seems to be running without the Monorepo Context, please consider using the Monorepo")
}