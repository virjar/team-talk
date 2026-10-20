rootProject.name = "TeamTalk"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// iOS 的 Native target 默认在 Mac 上启用（交叉编译只需 Kotlin/Native 编译器，不需要
// Xcode；实测 Intel Mac 分钟级）；非 Mac 平台默认关闭，保持常用构建不注册 iOS target。
// 显式优先级：Gradle 属性（含 -P / ORG_GRADLE_PROJECT_）> Git 忽略的 local.properties > 平台默认。
val localIosEnabled = providers.fileContents(layout.settingsDirectory.file("local.properties")).asText.map { text ->
    java.util.Properties().apply { load(text.reader()) }.getProperty("enableIos", "")
}
val defaultIosEnabled = if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) "true" else "false"
val enableIos = providers.gradleProperty("enableIos")
    .orElse(localIosEnabled)
    .orElse(providers.provider { defaultIosEnabled })
    .get().trim().let { value ->
        value.toBooleanStrictOrNull() ?: error("enableIos 必须是 true 或 false，实际为：$value")
    }
gradle.extra["enableIos"] = enableIos


// 模块按架构语义分三个顶层组：protocol（契约与传输）/ client（客户端）/ server（服务端）。
// Gradle 项目名使用层级路径（:client:shared 等），IDEA 项目树按组折叠显示。


include(":protocol:protocol")
include(":protocol:protocol-netty")
include(":protocol:rpc-processor")
include(":client:shared")
include(":client:headless")
include(":client:shared-testkit")
include(":client:richeditor")
include(":client:app")
include(":client:android")
if (enableIos) include(":client:ios")
include(":client:desktop")
include(":client:desktop-bootstrap")
include(":server:server")
include(":server:admin")
