import java.util.Properties

val releaseKeystoreFile = file("release.jks")
val releaseProperties = Properties().apply {
    val propFile = rootProject.file("local.properties")
    if (propFile.exists()) propFile.inputStream().use(::load)
}
val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD")
    ?: releaseProperties.getProperty("KEYSTORE_PASSWORD")
val releaseKeyPassword = System.getenv("KEY_PASSWORD")
    ?: releaseProperties.getProperty("KEY_PASSWORD")
    ?: releaseStorePassword
val releaseKeyAlias = System.getenv("KEY_ALIAS")
    ?: releaseProperties.getProperty("KEY_ALIAS")
    ?: "iconpackfiller"
val releaseSigningReady = releaseKeystoreFile.isFile && !releaseStorePassword.isNullOrBlank()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.artplus.iconpackfiller"

    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.artplus.iconpackfiller"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-m0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
        }
        release {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            isDebuggable = false
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

gradle.taskGraph.whenReady {
    val releaseRequested = allTasks.any { task ->
        task.name == "assembleRelease" || task.name == "bundleRelease"
    }
    if (releaseRequested && !releaseSigningReady) {
        throw GradleException(
            "Release 签名材料缺失：需要 app/release.jks 和 KEYSTORE_PASSWORD（KEY_PASSWORD/KEY_ALIAS 可选）",
        )
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")

    // Miuix：Compose Multiplatform 的 Miuix 组件库（Apache-2.0）
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    // 页面转场（MIUI 风格滑动 + 预测性返回）
    implementation("top.yukonga.miuix.kmp:miuix-navigation3-ui-android:0.9.3")
    implementation("androidx.navigation3:navigation3-runtime:1.1.4")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")

    implementation("io.github.reandroid:ARSCLib:1.3.5")
    implementation("com.android.tools.build:apksig:9.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.10")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
