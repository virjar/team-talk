import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.library)
}

/**
 * WYSIWYG 富文本编辑器（fork 源码引入，Apache 2.0，来源见 LICENSE）。
 *
 * 来源：github.com/MohamedRejeb/compose-rich-editor（richeditor-compose 模块，
 * Kotlin 2.3.21 / CMP 1.10.3 与本项目同线）。裁剪至本项目所需目标
 * （common + android + desktop），去 explicitApi/bcv/发布配置。
 *
 * fork 的意义（F21）：发布版依赖的 JVM 字节码版本不可控（mikepenz 教训），
 * 且 IM 的 mention span/markdown 序列化需要源码级定制。
 */
kotlin {
    jvm("desktop")
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.material)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.markdown)
            // HTML 解析（富文本 HTML 导入导出用）
            implementation(libs.ksoup.html)
            implementation(libs.ksoup.entities)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

android {
    namespace = "com.mohamedrejeb.richeditor.compose"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
