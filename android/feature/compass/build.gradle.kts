plugins {
    alias(libs.plugins.geocrew.android.feature)
}

dependencies {
    implementation(project(":core:sensors"))
}
