plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.suantangyu.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.suantangyu.player"
        minSdk = 26
        targetSdk = 34
        versionCode = 146
        versionName = "3.84.17"
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
        // 3.80.0：原生队列试点引入 Compose
        compose = true
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
    // 3.59.0 本地音乐授权文件夹：DocumentFile 走 SAF 树
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.webkit:webkit:1.10.0")
    // phase-2 原生播放：ExoPlayer 在前台服务里解码，音频脱离 WebView
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    // 3.33.0：Chromium 网络栈（Cronet 内嵌版）作播放通道（同 3.28.0）
    implementation("androidx.media3:media3-datasource-cronet:1.4.1")
    implementation("org.chromium.net:cronet-embedded:119.6045.31")
    // 3.3.0 原生解析：QuickJS 引擎（LX 音源脚本在服务内运行，不依赖 WebView）
    implementation("wang.harlon.quickjs:wrapper-android:3.2.3")
    // 3.80.0：Compose（原生队列 sheet；BOM 与 Kotlin 2.0.20 对齐）
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
