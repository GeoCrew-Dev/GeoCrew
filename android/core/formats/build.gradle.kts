plugins {
    alias(libs.plugins.geocrew.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
}
