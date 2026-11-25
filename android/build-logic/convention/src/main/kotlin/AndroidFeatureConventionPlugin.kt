import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** A feature module: one screen or flow, built on the core modules. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("geocrew.android.library")
        pluginManager.apply("geocrew.android.compose")
        pluginManager.apply("geocrew.android.hilt")
        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:ui"))
            add("implementation", project(":core:data"))
            add("implementation", libs.lib("hilt-navigation-compose"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("androidx-navigation-compose"))
            add("implementation", libs.lib("kotlinx-coroutines-android"))
            add("implementation", libs.lib("androidx-activity-compose"))
        }
    }
}
