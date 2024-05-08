import com.github.gradle.node.yarn.task.YarnSetupTask
import com.github.gradle.node.yarn.task.YarnTask

plugins {
    id("com.github.node-gradle.node") version "7.0.2"
}
val applicationId: String by rootProject.extra
var versionCode: Int by rootProject.extra
var versionName: String by rootProject.extra
var buildTime: String by rootProject.extra
var buildUser: String by rootProject.extra


val yarnVersionStr: String by rootProject.extra
val nodeVersionStr: String by rootProject.extra
var nodeDistMirror: String by rootProject.extra

node {
    download = true
    version = nodeVersionStr
    yarnVersion = yarnVersionStr
    distBaseUrl = nodeDistMirror
    workDir = file("${project.projectDir}/.gradle/nodejs")
    yarnWorkDir = file("${project.projectDir}/.gradle/yarn")
}


class OSCompat {
    companion object {
        private val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
        var binExt = if (isWindows) {
            ".cmd"
        } else {
            ""
        }
        var yarnDirName = if (isWindows) {
            ""
        } else {
            "/bin"
        }
    }
}

// enable yarn 2
val corepackTask = tasks.register("corepack") {
    val dirPath = ".gradle/yarn/yarn-v${yarnVersionStr}/${OSCompat.yarnDirName}"
    outputs.dir(dirPath)
    doLast {
        mkdir(dirPath)
        exec {
            commandLine("corepack${OSCompat.binExt}", "enable", "--install-directory", dirPath)
        }
    }
}


tasks.named("yarnSetup").configure {
    enabled = false
    dependsOn(corepackTask)
}

task<YarnTask>("yarnBuild") {
    group = "build"
    dependsOn(tasks.yarnSetup)
    dependsOn(tasks.yarn)
    args = listOf("run", "build")
    environment = mapOf("BUILD_VERSION" to versionName, "BUILD_TIME" to buildTime)
}
