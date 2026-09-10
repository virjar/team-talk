import java.util.Base64
import deployment.DeploymentConfig
import deployment.GenerateOemPushChannels
import deployment.resolveAndroidSigning
import release.GenerateAndroidReleaseIdentity
import kotlinx.serialization.json.JsonPrimitive

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

// T011：Android 字节码目标保持 17（设备兼容基线），显式固定避免跟随构建 JDK 漂移。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val generateReleaseIdentity = tasks.register<GenerateAndroidReleaseIdentity>("generateReleaseIdentity") {
    this.releaseVersion.set(rootProject.extra["releaseVersion"] as String)
    this.buildIdentity.set(rootProject.extra["buildIdentity"] as String)
    deploymentConfigJson.set(deploymentConfig.toCanonicalJson())
    outputDirectory.set(layout.buildDirectory.dir("generated/release-identity/assets"))
}
val generateOemPushChannels = tasks.register<GenerateOemPushChannels>("generateOemPushChannels") {
    vendors.set(deploymentConfig.oemPush.keys.toList())
    outputDirectory.set(layout.buildDirectory.dir("generated/oem-push-channels/kotlin"))
}
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateReleaseIdentity, GenerateAndroidReleaseIdentity::outputDirectory)
        variant.sources.java?.addGeneratedSourceDirectory(generateOemPushChannels, GenerateOemPushChannels::outputDirectory)
    }
}

android {
    namespace = "com.virjar.tk.android"
    compileSdk = 36

    defaultConfig {
        // 安装身份可以按私有部署配置，源码 namespace 保持稳定。
        applicationId = deploymentConfig.client.androidApplicationId
        // Android 字符串资源外层引号保留显示名里的空格和撇号，内部引号单独转义。
        val appName = deploymentConfig.client.displayName.replace("\\", "\\\\").replace("\"", "\\\"")
        resValue("string", "app_name", "\"$appName\"")
        minSdk = 26
        targetSdk = 35
        versionCode = androidVersionCode
        versionName = releaseVersion
        buildConfigField("String", "SERVER_BASE_URL", "\"${deploymentConfig.serverUrl}\"")
        buildConfigField("String", "TCP_HOST", "\"${deploymentConfig.tcpHost}\"")
        buildConfigField("int", "TCP_PORT", "${deploymentConfig.tcpPort}")
        buildConfigField(
            "String",
            "TCP_TLS_CERTIFICATE_BASE64",
            "\"${deploymentConfig.tcpTlsCertificatePem?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }.orEmpty()}\"",
        )
        buildConfigField("String", "GIT_COMMIT_ID", "\"$gitCommitId\"")
        buildConfigField("String", "BUILD_IDENTITY", "\"$buildIdentity\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        // 每个厂商的客户端注册参数只在该厂商配置后进入 APK；服务端密钥绝不写入 BuildConfig。
        // OPPO 官方注册接口在客户端同时要求 appKey 与 appSecret。
        for (vendor in deployment.OemPushVendors.ALL) {
            val push = deploymentConfig.oemPush[vendor]
            manifestPlaceholders["${vendor}PushEnabled"] = (push != null).toString()
            when (vendor) {
                deployment.OemPushVendors.XIAOMI -> {
                    buildConfigField("String", "XIAOMI_PUSH_APP_ID", JsonPrimitive(push?.appId.orEmpty()).toString())
                    buildConfigField("String", "XIAOMI_PUSH_APP_KEY", JsonPrimitive(push?.readCredential("appKey").orEmpty()).toString())
                }
                deployment.OemPushVendors.HUAWEI, deployment.OemPushVendors.HONOR -> {
                    buildConfigField("String", "${vendor.uppercase()}_PUSH_APP_ID", JsonPrimitive(push?.appId.orEmpty()).toString())
                    manifestPlaceholders["${vendor}PushAppId"] = push?.appId.orEmpty()
                }
                deployment.OemPushVendors.OPPO -> {
                    buildConfigField("String", "OPPO_PUSH_APP_KEY", JsonPrimitive(push?.appKey.orEmpty()).toString())
                    buildConfigField("String", "OPPO_PUSH_APP_SECRET", JsonPrimitive(push?.readCredential("appSecret").orEmpty()).toString())
                }
                deployment.OemPushVendors.VIVO -> {
                    buildConfigField("String", "VIVO_PUSH_APP_ID", JsonPrimitive(push?.appId.orEmpty()).toString())
                    buildConfigField("String", "VIVO_PUSH_APP_KEY", JsonPrimitive(push?.appKey.orEmpty()).toString())
                    manifestPlaceholders["vivoPushAppId"] = push?.appId.orEmpty()
                    manifestPlaceholders["vivoPushAppKey"] = push?.appKey.orEmpty()
                }
                deployment.OemPushVendors.MEIZU -> {
                    buildConfigField("String", "MEIZU_PUSH_APP_ID", JsonPrimitive(push?.appId.orEmpty()).toString())
                    buildConfigField("String", "MEIZU_PUSH_APP_KEY", JsonPrimitive(push?.appKey.orEmpty()).toString())
                }
            }
        }
    }

    // 每个厂商一个可选源集；聚合表与未配置厂商的清单占位组件由 generateOemPushChannels 生成。
    sourceSets.getByName("main") {
        deploymentConfig.oemPush.keys.forEach { vendor -> java.srcDir("src/$vendor/kotlin") }
    }

    // buildSrc 统一解析签名配置；这里只将同一身份绑定到 AGP 的 Debug / Release。
    val signing = resolveAndroidSigning(
        configured = deploymentConfig.androidSigning,
        trialKeystore = file("teamtalk-dev.jks"),
        localPropertiesFile = rootProject.file("local.properties"),
        environment = { providers.environmentVariable(it).orNull },
        resolveFile = { rootProject.file(it) },
    )
    signingConfigs {
        create("release") {
            storeFile = signing.storeFile
            keyAlias = signing.keyAlias
            storePassword = signing.storePassword
            keyPassword = signing.keyPassword
        }
    }

    buildTypes {
        // 显式绑定到 Debug 与 Release：快速试用与私有交付都满足同证书覆盖安装（T012）。
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
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
    // 已配置厂商的官方 AAR 逐个登记；华为/荣耀可能包含多个文件。
    deploymentConfig.oemPush.values.forEach { push -> implementation(files(push.sdkFiles)) }
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
