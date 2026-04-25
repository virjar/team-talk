import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val androidMinSdk: Int by rootProject.extra
val androidTargetSdk: Int by rootProject.extra
val androidCompileSdk: Int by rootProject.extra
val kotlinxSerializationVersion: String by rootProject.extra

android {
    namespace = "com.virjar.tk"
    compileSdk = androidCompileSdk

    // 从 local.properties 读取签名配置
    val localProps = Properties()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localProps.load(localPropsFile.inputStream())
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProps.getProperty("release.storeFile")
            if (storeFilePath != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = localProps.getProperty("release.storePassword") ?: ""
                keyAlias = localProps.getProperty("release.keyAlias") ?: ""
                keyPassword = localProps.getProperty("release.keyPassword") ?: ""
            } else {
                val devKeystore = rootProject.file("android/teamtalk-dev.jks")
                if (devKeystore.exists()) {
                    storeFile = devKeystore
                    storePassword = "teamtalk"
                    keyAlias = "teamtalk"
                    keyPassword = "teamtalk"
                }
            }
        }
    }

    defaultConfig {
        applicationId = "com.virjar.tk.teamtalk"
        minSdk = androidMinSdk
        targetSdk = androidTargetSdk
        val pkgVersion = project.findProperty("packageVersion")?.toString() ?: "1.0.0"
        versionCode = pkgVersion.split(".").fold(0) { acc, part -> acc * 100 + (part.toIntOrNull() ?: 0) }
        versionName = pkgVersion
    }

    buildTypes {
        release {
            val releaseConfig = signingConfigs.findByName("release")
            if (releaseConfig?.storeFile != null) {
                signingConfig = releaseConfig
            }
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
    }

    kotlin {
        jvmToolchain(17)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":app"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // Compose — versions resolved via :app transitive dependencies (Compose Multiplatform 1.10.0)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
