package com.tvku.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { IllegalStateException("Missing version '$alias' in libs.versions.toml") }.requiredVersion

internal fun VersionCatalog.lib(alias: String) =
    findLibrary(alias).orElseThrow { IllegalStateException("Missing library '$alias' in libs.versions.toml") }

/** Java/Kotlin bytecode target shared by every module (AGP's built-in Kotlin follows compileOptions). */
internal val TVKU_JAVA_VERSION = JavaVersion.VERSION_21

internal fun Project.configureApplication(extension: ApplicationExtension) = extension.apply {
    compileSdk = libs.version("compileSdk").toInt()
    defaultConfig {
        minSdk = libs.version("minSdk").toInt()
        targetSdk = libs.version("targetSdk").toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = TVKU_JAVA_VERSION
        targetCompatibility = TVKU_JAVA_VERSION
    }
    testOptions.unitTests.isReturnDefaultValues = true
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        xmlReport = true
        htmlReport = true
    }
}

internal fun Project.configureLibrary(extension: LibraryExtension) = extension.apply {
    compileSdk = libs.version("compileSdk").toInt()
    defaultConfig {
        minSdk = libs.version("minSdk").toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = TVKU_JAVA_VERSION
        targetCompatibility = TVKU_JAVA_VERSION
    }
    testOptions.unitTests.isReturnDefaultValues = true
    lint {
        abortOnError = true
        xmlReport = true
        htmlReport = true
    }
}
