plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 这个模块里只有一个 WebView，故意不引任何第三方依赖。
android {
    namespace = "dev.coyotecage.shell"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.coyotecage.shell"
        minSdk = 26
        targetSdk = 35
        // 这个版本号是「手机壳」自己的，和网页端版本无关
        versionCode = 1145
        versionName = "1145.14.191810"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
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

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}
