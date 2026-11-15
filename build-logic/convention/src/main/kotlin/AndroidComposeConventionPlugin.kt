import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.tvku.buildlogic.lib
import com.tvku.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Enables Jetpack Compose on an Android application or library module. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.withPlugin("com.android.application") {
            extensions.getByType(ApplicationExtension::class.java).buildFeatures.compose = true
        }
        pluginManager.withPlugin("com.android.library") {
            extensions.getByType(LibraryExtension::class.java).buildFeatures.compose = true
        }
        dependencies {
            val bom = platform(libs.lib("androidx-compose-bom"))
            add("implementation", bom)
            add("androidTestImplementation", bom)
            add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
            add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
        }
    }
}
