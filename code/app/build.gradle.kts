import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// release 签名凭据（不入库/不入包；缺失时 release 产物为未签名 APK）。
// 查找顺序：环境变量 STARCAM_KEYSTORE → -PkeystoreProps=<路径> → 本机默认位置。
// 前两者供 CI / 其他开发机使用，避免把开发者目录结构写死在公开仓库里。
val keystorePropsFile: File? =
    (System.getenv("STARCAM_KEYSTORE")
        ?: (project.findProperty("keystoreProps") as? String)
        ?: "${System.getProperty("user.home")}/.starcam/starcam-keystore.properties")
        .let { File(it) }
        .takeIf { it.exists() }
val keystoreProps = Properties().apply {
    keystorePropsFile?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.starcam.astro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.starcam.astro"
        minSdk = 26
        targetSdk = 34
        versionCode = 82
        versionName = "1.5.62"
    }

    // 每版更新内容简述（用户规则：在 APK 文件名上带上更新内容）
    val updateDesc = project.findProperty("updateDesc") as? String
        ?: "识别提速3.4倍"

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
            keystorePropsFile?.let {
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
            signingConfig = keystorePropsFile?.let { signingConfigs.getByName("release") }
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
        // 注：512m 同时也是「星表加深到 6.5 等不可行」的实测约束 —— 那个索引
        // （500 万条三角形记录）在这个堆里连求解阶段都跑不完，见 PROGRESS §0.73。
        unitTests.all {
            it.maxHeapSize = "512m"
        }
    }

    // 本机 4 逻辑核（2026-09-01）：两个测试 worker 并行跑测试类，全量回归减半
    tasks.withType<Test>().configureEach {
        maxParallelForks = 2
        // 真值回归台依赖不入库的真实照片素材（testdata/，含私拍原图）与
        // SkyView 下载的 DSS 巡天图；CI 里没有这批素材，用 -PskipPhotoTests=true 排除。
        // 本机照常执行——这是宽场/暗星等/窄场修复的唯一真值保护，务必保留。
        if (project.findProperty("skipPhotoTests") == "true") {
            filter {
                excludeTestsMatching("com.starcam.astro.RealPhotoMatchTest")
                excludeTestsMatching("com.starcam.astro.Photo12RegressionTest")
                excludeTestsMatching("com.starcam.astro.NarrowFieldRegressionTest")
                excludeTestsMatching("com.starcam.astro.PhaseTimingBench")
                excludeTestsMatching("com.starcam.astro.TieredCatalogTest")
            }
        }
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

    // 加密存储（在线求解 API Key；MODE_PRIVATE 挡不住 root/取证）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 测试
    testImplementation("junit:junit:4.13.2")
    // §0.70：单测里 `org.json`（Android 内置）是「未实现桩」——调用任何方法都抛
    // RuntimeException("not mocked")。识别日志的组装与摘要渲染正是围绕 JSON 的，
    // 于是测试期补一个**真实实现**（org.json:json，与 Android 的 API 同源）。
    // 只进测试 classpath，不进 APK。
    testImplementation("org.json:json:20240303")
}
