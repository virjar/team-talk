import deployment.DeploymentConfig

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.library)
    alias(libs.plugins.buildconfig)
}

// 平台壳和共享 UI 消费同一份编译期身份，避免各自维护名称和本地目录规则。
val clientIdentity = (rootProject.extra["deploymentConfig"] as DeploymentConfig).client
buildConfig {
    packageName("com.virjar.tk.app.identity")
    className("ClientIdentity")
    useKotlinOutput { internalVisibility = false }
    buildConfigField("APPLICATION_ID", clientIdentity.applicationId)
    buildConfigField("DISPLAY_NAME", clientIdentity.displayName)
    buildConfigField("DESKTOP_DATA_DIRECTORY_NAME", clientIdentity.desktopDataDirectoryName)
    buildConfigField("LINUX_DATA_DIRECTORY_NAME", clientIdentity.linuxDataDirectoryName)
}

/**
 * UI 模块。只消费 :client:shared（IM SDK）的公开 API，
 * 不持有任何 SDK 内部实现（连接/缓存/协议细节）。
 */
kotlin {
    jvm("desktop")
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            // Platform shells depend on :client:shared explicitly. Keep SDK internals off :client:app's
            // transitive API so the module boundary remains android/desktop -> app + shared.
            implementation(project(":client:shared"))
            api(libs.jetbrains.compose.runtime)
            api(libs.jetbrains.compose.foundation)
            api(libs.jetbrains.compose.material3)
            api(libs.jetbrains.compose.material.icons.extended)
            api(libs.jetbrains.compose.components.resources)
            api(libs.jetbrains.markdown)
            api(project(":client:richeditor"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(project(":client:shared-testkit"))
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.activity.compose)
                implementation(libs.media3.exoplayer)
                implementation(libs.media3.ui)
            }
        }
    }
}

android {
    namespace = "com.virjar.tk.app"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.configureEach {
    if (name == "desktopRun") enabled = false
}
