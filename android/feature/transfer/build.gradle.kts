plugins {
    alias(libs.plugins.geocrew.android.feature)
}

dependencies {
    // QR codes on sample labels.
    implementation(libs.zxing.core)
}
