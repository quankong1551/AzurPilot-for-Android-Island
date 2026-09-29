plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val spikeTargetSdk: Int = (findProperty("spike.targetSdk") as? String)?.toIntOrNull() ?: 35

android {
    namespace = "com.aos.spikea"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.aos.spikea"
        minSdk = 26
        targetSdk = spikeTargetSdk
        versionCode = 2
        versionName = "0.2.0-ts$spikeTargetSdk"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        // 仅供 Spike 使用：沿用 debug 签名安装不可调试的 release 包，验证 `lib*.so` 命名
        // 规则在完整安装链路中的表现。
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 安装时将 native 库提取到 nativeLibraryDir；从该目录执行的探针依赖真实文件，且
    // targetSdk 35 的 W^X 策略限制从数据目录执行。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
