import com.android.build.api.dsl.LibraryExtension
import com.tvku.buildlogic.configureLibrary
import com.tvku.buildlogic.lib
import com.tvku.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> { configureLibrary(this) }
        dependencies {
            add("testImplementation", libs.lib("junit4"))
            add("testImplementation", libs.lib("kotlinx-coroutines-test"))
        }
    }
}
