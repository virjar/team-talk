plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

/**
 * E2E 测试夹具（fixture）模块：唯一职责是为 **集成/验收层** 提供 client SDK 的内存替身。
 *
 * 定位与边界（2026-09-04 测试政策，见 doc/08-development/engineering-rules.md）：
 * - 唯一对外入口是 [FakeLocalCache]（完整 LocalCache 接口的内存实现，供 server e2e、
 *   acceptance 与少量复杂收敛套件驱动真实部署/真实协议栈）与 [FakeRpcInvoker]。
 * - 本模块**永不**成为产品依赖：app/desktop/android 的制品不得包含任何 Fake 类，
 *   该边界由模块图硬性保证——这是它独立于 shared 存在的唯一理由。
 * - 轻单元测试政策下禁止新增 Fake：单元测试一律精简，测试重心在集成与 e2e。
 *   （文件名带 Test 的自测已删除；Fake 的行为由 e2e 与 acceptance 直接检验。）
 *
 * Reusable client-SDK test doubles.
 *
 * This module has ordinary main source sets so another module's test compilation can consume it,
 * but no product main configuration may depend on it. Package names intentionally remain under
 * com.virjar.tk.testing so architecture checks can reject accidental production use.
 */
kotlin {
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            api(project(":client:shared"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.virjar.tk.shared.testkit"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
