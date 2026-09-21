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

// iOS 的 Native 编译、链接与依赖缓存由本机或专用 CI 显式启用。
// Gradle 属性（含 -P / ORG_GRADLE_PROJECT_）优先于 Git 忽略的 local.properties。
val localIosEnabled = providers.fileContents(layout.settingsDirectory.file("local.properties")).asText.map { text ->
    java.util.Properties().apply { load(text.reader()) }.getProperty("enableIos", "false")
}
val enableIos = providers.gradleProperty("enableIos").orElse(localIosEnabled).getOrElse("false").trim().let { value ->
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
