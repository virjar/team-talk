import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Properties
import org.gradle.api.GradleException
import profiles.BuildProfile

/**
 * Detect local LAN IP via UDP connect trick.
 * Used when profile has localhost — Android devices need the actual LAN IP to reach the host.
 */
fun detectLocalIp(): String {
    val socket = DatagramSocket()
    return try {
        socket.connect(InetAddress.getByName("8.8.8.8"), 10002)
        socket.localAddress.hostAddress
            ?: throw GradleException("Failed to detect local LAN IP: hostAddress is null")
    } catch (e: Exception) {
        throw GradleException(
            "Failed to detect local LAN IP for Android build. " +
                "Profile uses localhost/127.0.0.1, which cannot be reached from Android devices. " +
                "Please set serverUrl and tcpAddress to your actual LAN IP in the profile, " +
                "or ensure network connectivity. Original error: ${e.message}"
        )
    } finally {
        socket.close()
    }
}

@Suppress("UNCHECKED_CAST")
val allProfiles = rootProject.extra.get("allProfiles") as Map<String, BuildProfile>
val gitCommitId = rootProject.extra.get("gitCommitId") as String
val buildTime = rootProject.extra.get("buildTime") as String

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.virjar.tk.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.virjar.tk.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = rootProject.extra.get("packageVersion") as String
    }

    // 按 profile 创建 product flavors
    flavorDimensions += "profile"
    productFlavors {
        allProfiles.forEach { (pn, profile) ->
            register(pn) {
                dimension = "profile"

                // Android 自动替换 localhost 为局域网 IP
                val resolvedUrl = if (profile.serverUrl.contains("localhost") || profile.serverUrl.contains("127.0.0.1"))
                    "http://${detectLocalIp()}:8080" else profile.serverUrl
                val resolvedHost = if (profile.tcpHost == "localhost" || profile.tcpHost == "127.0.0.1")
                    detectLocalIp() else profile.tcpHost

                buildConfigField("String", "SERVER_BASE_URL", "\"$resolvedUrl\"")
                buildConfigField("String", "TCP_HOST", "\"$resolvedHost\"")
                buildConfigField("int", "TCP_PORT", "${profile.tcpPort}")
                buildConfigField("boolean", "ALLOW_CUSTOM_SERVER", "${profile.allowCustomServer}")
                buildConfigField("String", "GIT_COMMIT_ID", "\"$gitCommitId\"")
                buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
            }
        }
    }

    // 固定签名证书：从 local.properties 读取 release 签名，
    // 未配置时 fallback 到 android/teamtalk-dev.jks（方便用户快速体验）
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

    buildTypes {
        release {
            val releaseConfig = signingConfigs.findByName("release")
            if (releaseConfig?.storeFile != null) {
                signingConfig = releaseConfig
            }
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // netty 多个 jar 携带同名 META-INF 资源（INDEX.LIST / *.DSA / NOTICE 等），
    // Android 合并时报重复路径冲突。这些都是签名/索引元数据，运行时不需要，排除。
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":app"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.material.icons.extended)
    implementation(libs.slf4j.jdk14)
    // 媒体/上传依赖（VideoPlayerDialog / MediaHelper 使用）
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
}
