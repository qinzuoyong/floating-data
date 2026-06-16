plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.batteryfloat"
    // APK 输出文件名设为 yongge (<module>-<variant>.apk -> yongge-release.apk)
    base {
        archivesName.set("yongge")
    }

    // 使用 Android SDK 自带的 debug 密钥签名，无需自己创建密钥库
    signingConfigs {
        create("debugKey") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // assembleRelease 完成后将 APK 复制为 yongge.apk
    tasks.register("copyApkToYongge") {
        doLast {
            val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            val src = apkDir.resolve("yongge-release.apk")
            val dst = apkDir.resolve("yongge.apk")
            if (src.exists()) {
                if (dst.exists()) dst.delete()
                src.copyTo(dst, overwrite = true)
                if (dst.exists()) {
                    logger.lifecycle("APK copied: yongge.apk (release, signed with debug key)")
                } else {
                    logger.warn("APK copy failed")
                }
            } else {
                logger.warn("未找到 release APK 输出")
            }
        }
    }
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yongge.batteryfloat"
        minSdk = 34
        targetSdk = 34
        versionCode = 16
        versionName = "1.53"

        // 只保留中文资源，剪掉多语言
        resConfigs("zh")
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "src/main/keepRules/rules.keep"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debugKey")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "src/main/keepRules/rules.keep"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }


}

afterEvaluate {
    tasks.named("assembleRelease") { finalizedBy("copyApkToYongge") }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}