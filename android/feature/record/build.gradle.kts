plugins {
    alias(libs.plugins.geocrew.android.feature)
}
dependencies {
    // Compass heading and fix noted with each photo.
    implementation(project(":core:sensors"))
}
