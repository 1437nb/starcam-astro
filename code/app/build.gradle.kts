import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// release 签名凭据（本机 C:\dev\starcam-keystore.properties，不入库/不入包；
// 文件缺失时 release 产物为未签名 APK）
val keystorePropsFile = File("C:/dev/starcam-keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.starcam.astro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.starcam.astro"
        minSdk = 26
        targetSdk = 34
        versionCode = 68
        versionName = "1.5.48"
    }

    // 每版更新内容简述（用户规则：在 APK 文件名上带上更新内容）
    val updateDesc = project.findProperty("updateDesc") as? String
        ?: "新增月亮与行星实时标注"

    // APK 产物自动带版本号、更新内容与变体名（用户规则：文件名标注版本与更新内容）
    applicationVariants.all {
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val variant = this@all.name
            val descPart = if (updateDesc.isNotBlank()) "-$updateDesc" else ""
            output?.outputFileName = "StarCam-v${versionName}${descPart}-${variant}.apk"
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // 构建机仅 1.8GB 内存：gradle 守护进程(512m) + 测试 worker 默认(512m)
        // 并存时会触发内核 OOM-killer（2026-08-28 实测）。测试实际峰值远低于默认值，
        // 限到 384m 后守护进程与 worker 可共存。
        unitTests.all {
            it.maxHeapSize = "512m"
        }
    }

    // 本机 4 逻辑核（2026-09-01）：两个测试 worker 并行跑测试类，全量回归减半
    tasks.withType<Test>().configureEach {
        maxParallelForks = 2
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose（BOM 统一版本）
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // AndroidX 基础
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // CameraX 相机
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // ListenableFuture 的协程扩展（await）
    implementation("androidx.concurrent:concurrent-futures-ktx:1.1.0")

    // 网络（astrometry.net API）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // EXIF 方向修正
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // 测试
    testImplementation("junit:junit:4.13.2")
}
