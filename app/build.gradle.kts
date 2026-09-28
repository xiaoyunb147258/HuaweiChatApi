plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.huawei2api"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.huawei2api"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore.jks")
            storePassword = "huawei2api"
            keyAlias = "huawei2api"
            keyPassword = "huawei2api"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 未提供 keystore 时用 debug 签名，保证 CI 可产出可安装包
            signingConfig = if (file("../keystore.jks").exists())
                signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
