import java.util.Properties
import deployment.DeploymentConfig

val deploymentConfig = rootProject.extra.get("deploymentConfig") as DeploymentConfig
val gitCommitId = rootProject.extra.get("gitCommitId") as String
val buildIdentity = rootProject.extra.get("buildIdentity") as String
val buildTime = rootProject.extra.get("buildTime") as String
val releaseVersion = rootProject.extra.get("releaseVersion") as String
val androidVersionCode = rootProject.extra.get("androidVersionCode") as Int

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
        versionCode = androidVersionCode
        versionName = releaseVersion
        buildConfigField("String", "SERVER_BASE_URL", "\"${deploymentConfig.serverUrl}\"")
        buildConfigField("String", "TCP_HOST", "\"${deploymentConfig.tcpHost}\"")
        buildConfigField("int", "TCP_PORT", "${deploymentConfig.tcpPort}")
        buildConfigField("String", "GIT_COMMIT_ID", "\"$gitCommitId\"")
        buildConfigField("String", "BUILD_IDENTITY", "\"$buildIdentity\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    // 私有发行从 local.properties 读取签名；未配置时使用本模块的固定预览证书，
    // 使内测 APK 可直接安装、后续同证书覆盖升级。仓库公开的开发证书不用于正式发行。
    val localProps = Properties()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use(localProps::load)
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
                storeFile = file("teamtalk-dev.jks")
                storePassword = "teamtalk"
                keyAlias = "teamtalk"
                keyPassword = "teamtalk"
            }
        }
    }

    buildTypes {
        release {
            // 缺失/错误证书由 validateSigningRelease 明确失败，不能静默产出 unsigned 包。
            signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        unitTests.all {
            it.systemProperty(
                "teamtalk.android.mainSourceDirectory",
                layout.projectDirectory.dir("src/main").asFile.absolutePath,
            )
            it.systemProperty(
                "teamtalk.android.debugSourceDirectory",
                layout.projectDirectory.dir("src/debug").asFile.absolutePath,
            )
        }
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
    implementation(project(":client:shared"))
    implementation(project(":client:app"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.material.icons.extended)
    implementation(libs.slf4j.jdk14)
    // 媒体展示依赖（上传已收敛到 shared 流式 transport）
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
