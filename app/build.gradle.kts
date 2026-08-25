import java.util.Properties

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

    // 正式签名（Release 使用 release.keystore，无需手动签名）
    // 口令等敏感项从项目根的 keystore.properties 读取（已 gitignore，绝不硬编码进仓库）
    val keystoreProps = Properties().apply {
        val propsFile = rootProject.file("keystore.properties")
        if (!propsFile.exists()) {
            throw GradleException(
                "缺少 keystore.properties：请在项目根按 AGENTS.md 签名说明创建，" +
                    "内容为 storeFile/storePassword/keyAlias/keyPassword 四行"
            )
        }
        propsFile.inputStream().use { load(it) }
    }
    signingConfigs {
        create("releaseKey") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    // assembleRelease 完成后将 APK 复制为 yongge.apk
    val releaseApkDir = layout.buildDirectory.dir("outputs/apk/release")
    tasks.register("copyApkToYongge") {
        doLast {
            val apkDir = releaseApkDir.get().asFile
            val src = apkDir.resolve("yongge-release.apk")
            val dst = apkDir.resolve("yongge.apk")
            if (src.exists()) {
                if (dst.exists()) dst.delete()
                src.copyTo(dst, overwrite = true)
                if (dst.exists()) {
                    logger.lifecycle("APK copied: yongge.apk (release, signed with release.keystore)")
                } else {
                    logger.warn("APK copy failed")
                }
            } else {
                logger.warn("未找到 release APK 输出")
            }
        }
    }
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.yongge.batteryfloat"
        minSdk = 34
        targetSdk = 34
        versionCode = 41
        versionName = "1.77"

        // 家人位置共享 A 方案：凭据从 local.properties 注入（gitignore，不进 Git）
        val lpFile = rootProject.file("local.properties")
        val lp = Properties().apply {
            if (lpFile.exists()) lpFile.inputStream().use { load(it) }
        }
        val baiduAk = lp.getProperty("BAIDU_MAP_AK", "")
        val signalUrl = lp.getProperty("SIGNAL_URL", "ws://47.94.212.176/family-signal")

        buildConfigField("String", "BAIDU_MAP_AK", "\"${baiduAk}\"")
        buildConfigField("String", "SIGNAL_URL", "\"${signalUrl}\"")

        // 只保留中文资源，剪掉多语言（AGP 9.x 移除 resConfigs，改用 androidResources.localeFilters 但需 initscript）

        // ABI 精简：正式包仅 arm64-v8a（Android 14+ 真机全覆盖），
        // 避免 webrtc/百度 SDK 多 ABI 打包超 Gitee Release 附件 100MB 限制；
        // 模拟器联调时加 -PdevEmulatorAbi 临时带上 x86_64（雷电）
        ndk {
            abiFilters += listOf("arm64-v8a")
            if (project.hasProperty("devEmulatorAbi")) {
                abiFilters += "x86_64"
            }
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "src/main/keepRules/rules.keep"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("releaseKey")
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
        buildConfig = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.4"
        }
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
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose")

    implementation(libs.kotlinx.coroutines.android)

    // ADB 无线调试特权通道(批次 2)
    implementation(libs.bouncycastle.bcpkix)          // AdbKey 自签证书
    implementation(libs.lsposed.hiddenapibypass)      // 隐藏 API 豁免(Conscrypt TLS exporter)
    implementation(libs.ndk.boringssl)                // prefab: 配对库加密后端
    implementation(libs.ndk.libcxx)                   // prefab: native STL

    // 家人位置共享（纯信令中继 + WebView 地图，无 WebRTC/百度原生 SDK）
    implementation(libs.gson)                     // 信令/位置 JSON 编解码
    implementation(libs.java.websocket)           // WebSocket 信令客户端
}