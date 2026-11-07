pluginManagement {
    // 本地构建走阿里云镜像（国内快），CI 走官方源（GitHub runner 在境外）
    val localMirror = System.getenv("CI").isNullOrEmpty()

    repositories {
        if (localMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val localMirror = System.getenv("CI").isNullOrEmpty()

    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (localMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "CoyoteCage"
include(":app")
// 手机壳：只有一个 WebView，加载线上网页；网页更新后手机端不用重新发版
include(":webview")
