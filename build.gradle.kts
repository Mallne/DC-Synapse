import nl.littlerobots.vcu.plugin.resolver.VersionSelectors
import nl.littlerobots.vcu.plugin.versionSelector
import java.util.*

plugins {
    alias(libs.plugins.kjvm) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.version.catalog.update)
    alias(libs.plugins.ben.manes.versions)
    alias(libs.plugins.dependency.analysis)
}

allprojects {
    apply {
        plugin("com.autonomousapps.dependency-analysis")
    }
}

val allowAlpha = listOf<List<Provider<MinimalExternalModuleDependency>>>(

)
val allowBeta = listOf(
    libs.exposed.core,
    libs.exposed.datetime,
    libs.exposed.json,
    libs.exposed.rdbc,
)

private fun isStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase(Locale.getDefault()).contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable
}

private fun isBeta(version: String): Boolean {
    val stableKeyword = listOf("BETA").any { version.uppercase(Locale.getDefault()).contains(it) }
    return stableKeyword
}

private fun isAlpha(version: String): Boolean {
    val stableKeyword = listOf("ALPHA").any { version.uppercase(Locale.getDefault()).contains(it) }
    return stableKeyword
}

versionCatalogUpdate {
    versionSelector { candidate ->
        val unst = allowBeta.map { it.get().module }
        if (unst.contains(candidate.candidate.moduleIdentifier)) {
            (isAlpha(candidate.candidate.version) && isAlpha(candidate.currentVersion)) || isStable(candidate.candidate.version)
        } else if (unst.contains(candidate.candidate.moduleIdentifier)) {
            (isBeta(candidate.candidate.version) && isBeta(candidate.currentVersion)) || isStable(candidate.candidate.version)
        } else {
            VersionSelectors.STABLE.select(candidate)
        }
    }
}