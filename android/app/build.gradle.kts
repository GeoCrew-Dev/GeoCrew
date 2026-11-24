import java.util.Properties

plugins {
    alias(libs.plugins.geocrew.android.application)
    alias(libs.plugins.geocrew.android.compose)
    alias(libs.plugins.geocrew.android.hilt)
}

// 开发用天地图 Key（可选）：只从本机 local.properties 的 tianditu.key 读取，只进 debug 包。
// 正式包不带任何 Key，每位用户在设置里填写自己的 Key。
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val devTiandituKey: String = localProps.getProperty("tianditu.key").orEmpty()

android {
    namespace = "org.geocrew.android"

    defaultConfig {
        applicationId = "org.geocrew.android"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "DEV_TIANDITU_KEY", "\"$devTiandituKey\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "DEV_TIANDITU_KEY", "\"\"")
        }
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:map"))
    implementation(project(":core:gis"))
    implementation(project(":core:sensors"))
    implementation(project(":feature:map"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:record"))
    implementation(project(":feature:compass"))
    implementation(project(":feature:transfer"))
    implementation(project(":feature:team"))
    implementation(project(":feature:projects"))
    implementation(project(":feature:analysis"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
