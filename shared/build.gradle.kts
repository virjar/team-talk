plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

/**
 * IM SDK 模块（= shared）。
 *
 * 完整 SDK 闭环：协议 + 模型 + ImClient/RpcClient（连接层）
 * + Repository + LocalCache + EventProcessor（数据层）+ 无头入口。
 * UI（:app）只消费本模块公开 API；无头客户端/AI bot 可直接依赖本模块运行。
 *
 * 分层（单向依赖）：shared(SDK) ← app(UI) ← android/desktop(shell)；server 依赖协议定义。
 */
kotlin {
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.netty.handler)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sqldelight.android.driver)
            }
        }
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.virjar.tk.database")
        }
    }
}

android {
    namespace = "com.virjar.tk.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.configureEach {
    if (name == "jvmRun") enabled = false
}
