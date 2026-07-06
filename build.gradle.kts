// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// 将所有构建产物重定向到 _build/ 目录，保持项目根目录整洁
rootProject.buildDir = file("_build/root")
subprojects {
    buildDir = file("${rootProject.rootDir}/_build/${name}")
}
