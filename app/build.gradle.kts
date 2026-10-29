import java.io.InputStream
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.coyotecage"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.coyotecage"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "2.1"

        // 只保留 arm64，APK 体积能小一半左右。需要 32 位机器就把下面这行删掉。
        ndk { abiFilters.add("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all {
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// ---------------------------------------------------------------------------
// 姿态模型：MediaPipe Pose Landmarker (lite)，约 5.7 MB，首次构建自动下载。
// 离线环境可手动放到 app/src/main/assets/pose_landmarker_lite.task
// ---------------------------------------------------------------------------
val poseModelUrl =
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
val poseModelFile = layout.projectDirectory.file("src/main/assets/pose_landmarker_lite.task")

tasks.register("fetchPoseModel") {
    val target = poseModelFile.asFile
    outputs.file(target)
    doLast {
        if (target.exists() && target.length() > 1_000_000) return@doLast
        target.parentFile?.mkdirs()
        logger.lifecycle("Downloading pose model -> ${target.absolutePath}")
        URI.create(poseModelUrl).toURL().openStream().use { input: InputStream ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        check(target.length() > 1_000_000) { "模型下载失败或文件不完整：${target.absolutePath}" }
    }
}

tasks.named("preBuild") { dependsOn("fetchPoseModel") }
