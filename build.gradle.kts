plugins {
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("jvm") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.compose") version "1.10.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("com.android.application") version "8.9.2" apply false
    id("com.android.library") version "8.9.2" apply false
    id("app.cash.sqldelight") version "2.3.2" apply false
}

extra.apply {
    set("kotlinVersion", "2.3.20")
    set("composeVersion", "1.10.0")
    set("agpVersion", "8.9.2")
    set("ktorVersion", "3.1.3")
    set("exposedVersion", "0.61.0")
    set("kotlinxSerializationVersion", "1.8.1")
    set("kotlinxCoroutinesVersion", "1.10.2")
    set("sqldelightVersion", "2.3.2")
    set("rocksdbVersion", "9.10.0")
    set("logbackVersion", "1.5.18")
    set("luceneVersion", "9.12.0")
    set("androidMinSdk", 26)
    set("androidTargetSdk", 35)
    set("androidCompileSdk", 36)
}

// 强制统一 Kotlin 依赖版本，防止 AGP 或其他插件引入低版本导致 metadata 冲突
subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(rootProject.extra["kotlinVersion"] as String)
            }
        }
    }
}
