plugins {
    alias(libs.plugins.geocrew.android.library)
    alias(libs.plugins.geocrew.android.compose)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx)
}
