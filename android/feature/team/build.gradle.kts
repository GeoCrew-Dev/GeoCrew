plugins {
    alias(libs.plugins.geocrew.android.feature)
}

dependencies {
    // Pure-Java QR encoder for invite codes.
    implementation(libs.zxing.core)
}
