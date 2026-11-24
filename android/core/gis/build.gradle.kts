plugins {
    alias(libs.plugins.geocrew.jvm.library)
}

// Spatial analysis tools. Pure Kotlin so they run (and are tested) anywhere,
// and so third-party tools only need this module and core:model.
dependencies {
    api(project(":core:model"))
}
