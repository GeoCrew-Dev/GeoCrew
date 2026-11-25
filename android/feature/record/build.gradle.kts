plugins {
    alias(libs.plugins.geocrew.android.feature)
}
dependencies {
    // Compass heading and fix noted with each photo.
    implementation(project(":core:sensors"))
    // Scanning sample labels to open their record.
    implementation(project(":core:scan"))
    // Offline dictation and transcribing voice notes.
    implementation(project(":core:speech"))
}
