import com.tvku.buildlogic.lib
import com.tvku.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import com.tvku.buildlogic.TVKU_JAVA_VERSION

/** Pure Kotlin module (no Android dependencies), e.g. core:model. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("java-library")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = TVKU_JAVA_VERSION
            targetCompatibility = TVKU_JAVA_VERSION
        }
        dependencies {
            add("testImplementation", libs.lib("junit4"))
        }
    }
}
