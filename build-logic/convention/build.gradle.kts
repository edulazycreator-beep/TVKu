plugins {
    `kotlin-dsl`
}

group = "com.tvku.buildlogic"

// Convention plugins only touch the Android Gradle Plugin API. Kotlin, KSP,
// Hilt and Compose plugins are applied by id, so their versions come from the
// root build (gradle/libs.versions.toml) and not from here.
dependencies {
    compileOnly(libs.android.gradlePlugin)
}

tasks.validatePlugins {
    enableStricterValidation = true
    failOnWarning = true
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = libs.plugins.tvku.android.application.get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.tvku.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = libs.plugins.tvku.android.compose.get().pluginId
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = libs.plugins.tvku.android.hilt.get().pluginId
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidFeature") {
            id = libs.plugins.tvku.android.feature.get().pluginId
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("jvmLibrary") {
            id = libs.plugins.tvku.jvm.library.get().pluginId
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
