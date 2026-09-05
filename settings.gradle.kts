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


// 模块按架构语义分三个顶层组：protocol（契约与传输）/ client（客户端）/ server（服务端）。
// Gradle 项目名使用层级路径（:client:shared 等），IDEA 项目树按组折叠显示。


include(":protocol:protocol")
include(":protocol:protocol-netty")
include(":protocol:rpc-processor")
include(":client:shared")
include(":client:shared-testkit")
include(":client:richeditor")
include(":client:app")
include(":client:android")
include(":client:desktop")
include(":server:server")
include(":server:admin")
