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
    //
    // 懒要求：仅当本次构建需要 release 签名时才强制要求该文件存在。
    // 否则 debug 构建与 IDE Sync 会因缺文件直接失败（配置期异常）。
    val needsReleaseSigning = gradle.startParameter.taskNames.any {
        it.contains("release", ignoreCase = true) || it.contains("bundle", ignoreCase = true)
    }
    signingConfigs {
        create("releaseKey") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val keystoreProps = Properties().apply { propsFile.inputStream().use { load(it) } }
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            } else if (needsReleaseSigning) {
                throw GradleException(
                    "缺少 keystore.properties：请在项目根按 AGENTS.md 签名说明创建，" +
                        "内容为 storeFile/storePassword/keyAlias/keyPassword 四行"
                )
            }
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

    // 只保留中文资源：本应用界面文案全为硬编码中文 + 8 条中文 string 资源，
    // 依赖库（AndroidX/Compose）自带 84 个语种的翻译共 1,008 条，全部用不到。
    // AGP 9.x 已移除 resConfigs，改用 androidResources.localeFilters（已在 AGP 9.2.1
    // 的 ApplicationAndroidResources DSL 中确认存在，无需 initscript）。
    androidResources {
        localeFilters += listOf("zh-rCN")
    }

    defaultConfig {
        applicationId = "com.yongge.batteryfloat"
        minSdk = 34
        targetSdk = 34
        versionCode = 60
        versionName = "2.01"

        // ABI 精简：正式包仅 arm64-v8a（Android 14+ 真机全覆盖），
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

    packaging {
        // 内置特权服务(libbfd.so)需被真实解压到 nativeLibraryDir 且保持可执行位
        // (经 shell 域拉起,Shizuku starter 同机制),必须关闭免解压打包
        jniLibs.useLegacyPackaging = true

        resources {
            // R8 只删类、不删依赖 jar 内的资源文件，AGP 会把这些 .properties 原量打进 APK。
            // bcpkix 在本项目只用于 AdbKey 签发自签证书（X509v3CertificateBuilder +
            // JcaContentSignerBuilder），不碰任何证书路径校验/审查逻辑，故以下两组纯死重：
            //  1) pqc：后量子算法（含 Picnic 预计算矩阵），R8 已把 bouncycastle.pqc 类全部剥除
            //     （mapping.txt 中 0 条），资源却仍在 —— 三份 lowmcL*.bin.properties 共 1,192 KB
            //  2) CertPathReviewerMessages：证书路径审查器的提示文案，该类未被保留（mapping 0 条）
            excludes += setOf(
                "org/bouncycastle/pqc/**",
                "**/CertPathReviewerMessages*.properties"
            )
        }
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

    // Shizuku 桥接:复用其常驻 server 进程,无线调试关闭后特权能力仍存活
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}