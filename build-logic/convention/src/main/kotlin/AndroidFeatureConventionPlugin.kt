import com.tvku.buildlogic.lib
import com.tvku.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Feature module = Android library + Compose + Hilt + design system. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("tvku.android.library")
        pluginManager.apply("tvku.android.compose")
        pluginManager.apply("tvku.android.hilt")
        dependencies {
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:model"))
            add("implementation", libs.lib("androidx-compose-material3"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("androidx-hilt-lifecycle-viewmodel-compose"))
            add("testImplementation", libs.lib("turbine"))
        }
    }
}
