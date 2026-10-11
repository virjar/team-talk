import com.github.gradle.node.npm.task.NpmInstallTask
import com.github.gradle.node.npm.task.NpmTask

plugins {
    base
    alias(libs.plugins.node)
}

// 将已安装依赖与同步出的源码树隔离：源码同步删除过期文件时，
// 不会误删 node_modules，也不会继承开发者本地的 dist。
val npmWorkspace = layout.buildDirectory.dir("npm")
val sourceWorkspace = npmWorkspace.map { it.dir("project") }
val frontendDist = layout.buildDirectory.dir("dist")
val packageFiles = files("package.json", "package-lock.json")

node {
    download.set(true)
    version.set(libs.versions.nodejs)
    nodeProjectDir.set(npmWorkspace)
    npmInstallCommand.set("ci")
}

val preparePackageFiles by tasks.registering {
    inputs.files(packageFiles).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.files(packageFiles.map { npmWorkspace.get().file(it.name) })
    doLast {
        packageFiles.forEach { source ->
            source.copyTo(npmWorkspace.get().file(source.name).asFile, overwrite = true)
        }
    }
}

val prepareSources by tasks.registering(Sync::class) {
    from(layout.projectDirectory) {
        include("package.json", "package-lock.json", "index.html", "tsconfig.json", "vite.config.ts", "playwright.config.ts")
        include("src/**", "public/**", "tests/**")
    }
    into(sourceWorkspace)
}

val npmInstall = tasks.named<NpmInstallTask>("npmInstall") {
    dependsOn(preparePackageFiles)
    args.set(listOf("--no-audit", "--no-fund"))
    inputs.property("nodeVersion", node.version)
    inputs.property("operatingSystem", System.getProperty("os.name"))
    inputs.property("architecture", System.getProperty("os.arch"))
}

val buildFrontend by tasks.registering(NpmTask::class) {
    group = "build"
    description = "Type-check and bundle the Admin application with the managed Node runtime"
    dependsOn(npmInstall, prepareSources)
    workingDir.fileProvider(sourceWorkspace.map { it.asFile })
    args.set(listOf("run", "build", "--", "--outDir", frontendDist.get().asFile.absolutePath))
    inputs.dir(sourceWorkspace).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("nodeVersion", node.version)
    outputs.dir(frontendDist)
}

tasks.named("check") { dependsOn(buildFrontend) }
tasks.named("assemble") { dependsOn(buildFrontend) }

val installBrowser by tasks.registering(NpmTask::class) {
    group = "verification"
    description = "Install Chromium and its OS dependencies for the Admin browser regressions"
    dependsOn(npmInstall)
    args.set(listOf("exec", "--", "playwright", "install", "--with-deps", "chromium"))
}

val browserTest by tasks.registering(NpmTask::class) {
    group = "verification"
    description = "Exercise actual Admin pages with deterministically delayed HTTP responses"
    dependsOn(buildFrontend)
    workingDir.fileProvider(sourceWorkspace.map { it.asFile })
    args.set(listOf("exec", "--", "playwright", "test"))
}

// 服务端将该目录作为 Gradle artifact 消费。解析它的同时就会触发构建，
// 因此 run、check 与 distributions 不会悄悄打包过期的前端。
val adminDist by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(adminDist.name, frontendDist) { builtBy(buildFrontend) }
