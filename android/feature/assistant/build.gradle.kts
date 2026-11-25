plugins {
    alias(libs.plugins.geocrew.android.feature)
}

dependencies {
    implementation(project(":core:actions"))
    implementation(project(":core:speech"))
    implementation(libs.okhttp)
}
