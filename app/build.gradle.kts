plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.suantangyu.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.suantangyu.player"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "3.1.7"
    }

    // 固定签名：工作流把 Secrets 里的固定 keystore 还原成 app/debug.keystore 时，debug 构建就用它签，
    // 不再每台机器现生成临时 debug key；本地没有该文件时回落 AGP 默认 debug 签名，不影响本地构建。
    signingConfigs {
        getByName("debug") {
            val fixed = file("debug.keystore")
            if (fixed.exists()) {
                storeFile = fixed
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    // BaoBridge.getShellVersion() 用 BuildConfig.VERSION_NAME，AGP 8 默认不生成 BuildConfig，必须显式打开
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.webkit:webkit:1.10.0")
    // phase-2 原生播放：ExoPlayer 在前台服务里解码，音频脱离 WebView
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
}
