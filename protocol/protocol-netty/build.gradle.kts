plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

/** Netty 帧适配器。wire 契约与 payload 编解码保持纯净，位于 :protocol:protocol。 */
kotlin {
    // T011：Gradle 运行 JDK 21；JVM 产物显式钉 21，Android 字节码保持 17（设备兼容基线）。
    jvm() {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    androidTarget() {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol:protocol"))
            // 让直接 IM 传输与 Ktor 传递引入的 Netty 模块保持同一补丁版本。
            api(project.dependencies.platform(libs.netty.bom))
            api(libs.netty.handler)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.virjar.tk.protocol.netty"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
