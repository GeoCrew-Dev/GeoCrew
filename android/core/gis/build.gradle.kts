plugins {
    alias(libs.plugins.geocrew.jvm.library)
}

// Spatial analysis tools. Pure Kotlin so they run (and are tested) anywhere,
// and so third-party tools only need this module, core:model and core:formats
// (GeoJSON, which tools in other apps exchange; see ExternalTool).
dependencies {
    api(project(":core:model"))
    api(project(":core:formats"))
}
