plugins {
    alias(libs.plugins.geocrew.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    // GeoPackage tests run against real SQLite files.
    testImplementation(libs.sqlite.jdbc)
}
