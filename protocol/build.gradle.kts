plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

/**
 * TeamTalk 跨端契约模块。
 *
 * 这里只允许出现 wire、消息体、传输模型、RPC IDL 和两端必须一致的纯规则。
 * 客户端连接、缓存、Repository、平台实现和服务端基础设施都不得进入本模块。
 */
kotlin {
    jvm()
    androidTarget()

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
        }
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            // PacketBuffer/PacketCodec 的公开签名仍使用 Netty ByteBuf；后续协议版本再收敛该泄漏。
            api(libs.netty.handler)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.virjar.tk.protocol"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    add("kspCommonMainMetadata", project(":rpc-processor"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("kspCommonMainKotlinMetadata")
}
