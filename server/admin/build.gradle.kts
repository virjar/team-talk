import com.github.gradle.node.npm.task.NpmInstallTask
import com.github.gradle.node.npm.task.NpmTask

plugins {
    base
    alias(libs.plugins.node)
}

// Keep installed packages separate from the synced source tree: source deletions must
// remove stale files without deleting node_modules or inheriting a developer's local dist.
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
        include("package.json", "package-lock.json", "index.html", "tsconfig.json", "vite.config.ts")
        include("src/**", "public/**")
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

// Server consumes this directory as a Gradle artifact. Resolving it also builds it,
// so run, check and distributions cannot silently package a stale frontend.
val adminDist by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(adminDist.name, frontendDist) { builtBy(buildFrontend) }
