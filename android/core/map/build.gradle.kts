plugins {
    alias(libs.plugins.geocrew.android.library)
    alias(libs.plugins.geocrew.android.compose)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:formats"))
    implementation(project(":core:ui"))
    api(libs.maplibre.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
