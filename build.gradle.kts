import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.jetbrainsCompose) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    // todo 这个似乎还用不起来
    id("com.gorylenko.gradle-git-properties") version "2.3.2" apply true
}


var versionCode by extra(1)
var versionName by extra("1.0.0-SNAPSHOT")
var applicationId by extra("com.virjar.tk.server")
var buildTime: String by extra(
    LocalDateTime.now().format(
        DateTimeFormatter.ofPattern(
            "yyyy-MM-dd_HH:mm:ss",
            java.util.Locale.CHINA
        )
    )
)

var buildUser: String by extra {
    var user = System.getenv("USER")
    if (user == null || user.isEmpty()) {
        user = System.getenv("USERNAME")
    }
    user
}

// 前端工具链相关
val yarnVersionStr by extra("4.1.1")
val nodeVersionStr by extra("20.10.0")
var nodeDistMirror by extra("https://mirrors.ustc.edu.cn/node")