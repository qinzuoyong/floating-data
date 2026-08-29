pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        // 阿里云 central 镜像:本机 repo.maven.org DNS 不通,新增依赖经此拉取(2026-08-29 Shizuku API)
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        mavenCentral()
    }
}

rootProject.name = "BatteryFloating"
include(":app")

// Gradle 构建缓存重定向到 _build/ 目录
buildCache {
    local {
        directory = File(rootDir, "_build/gradle-cache")
        isEnabled = true
    }
}
