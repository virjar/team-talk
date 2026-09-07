plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

/** Netty framing adapter. The wire contract and payload codecs remain pure in :protocol:protocol. */
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
            // Keep direct IM transport and Ktor's transitive Netty modules on one patch level.
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
