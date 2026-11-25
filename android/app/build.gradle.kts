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

// 版本号：发布时由标签传入 -Pgeocrew.version=0.3.0；versionCode 由版本号算出，保证新版本总能覆盖安装
// （与 core/model 的 AppReleases.versionCode 一致：1.2.3 → 1020399，1.2.3-rc.2 → 1020302）。
val releaseVersion: String? = providers.gradleProperty("geocrew.version").orNull?.removePrefix("v")
fun versionCodeOf(v: String): Int {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-[0-9A-Za-z]+\.?(\d+)?)?$""").matchEntire(v)
        ?: error("geocrew.version 应形如 1.2.3 或 1.2.3-rc.1，而不是 $v")
    val (maj, min, pat) = m.destructured
    val pre = if ('-' in v) (m.groupValues[4].toIntOrNull() ?: 0).coerceIn(0, 98) else 99
    return ((maj.toInt() * 100 + min.toInt()) * 100 + pat.toInt()) * 100 + pre
}

// 应用内更新：正式包默认从 GitHub Releases 检查更新；上架应用商店或 F-Droid 的包用 -Pgeocrew.updates=false 关掉，
// 分叉项目可用 -Pgeocrew.updateFeed=… 换成自己的发布地址。
val updatesEnabled = providers.gradleProperty("geocrew.updates").map { it.toBoolean() }.getOrElse(true)
val updateFeed = providers.gradleProperty("geocrew.updateFeed")
    .getOrElse("https://api.github.com/repos/GeoCrew-Dev/GeoCrew-Android/releases/latest")

// 发布签名：只从环境变量读取（CI 里来自仓库 Secrets），仓库里不放任何密钥。没设置时正式包不签名。
val keystoreFile: String? = System.getenv("GEOCREW_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "org.geocrew.android"

    defaultConfig {
        applicationId = "org.geocrew.android"
        versionCode = releaseVersion?.let(::versionCodeOf) ?: 1
        versionName = releaseVersion ?: "0.1.0"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("GEOCREW_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GEOCREW_KEY_ALIAS")
                keyPassword = System.getenv("GEOCREW_KEY_PASSWORD") ?: System.getenv("GEOCREW_KEYSTORE_PASSWORD")
            }
        }
    }

    sourceSets {
        if (!updatesEnabled) getByName("release").manifest.srcFile("src/noupdates/AndroidManifest.xml")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "DEV_TIANDITU_KEY", "\"$devTiandituKey\"")
            // 调试包和正式包签名不同，不能互相覆盖，所以调试包不检查更新。
            buildConfigField("String", "UPDATE_FEED", "\"\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "DEV_TIANDITU_KEY", "\"\"")
            buildConfigField("String", "UPDATE_FEED", "\"${if (updatesEnabled) updateFeed else ""}\"")
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
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
