plugins {
    alias(libs.plugins.geocrew.android.feature)
}

dependencies {
    // Pure-Java QR encoder for invite codes.
    implementation(libs.zxing.core)
    // Background notices through the user's own distributor (ntfy etc.), no Google services.
    implementation(libs.unifiedpush.connector)
    // One GNSS fix for the position of an urgent message.
    implementation(project(":core:sensors"))
}
