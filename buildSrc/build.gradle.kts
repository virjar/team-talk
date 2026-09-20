import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

plugins {
    `kotlin-dsl`
}

// 普通 Kotlin 源文件参与真实编译和 IDE 导航。私有目录完整替换公版目录，不能同时编译两套入口。
// 目录路径与 buildSrc/src/main/kotlin/deployment/DeploymentLayout.kt 保持一致。
val localDeploymentDirectory = file("deployment-local")
val localConfigurationFile = localDeploymentDirectory.resolve("Deployment.kt")
val usingLocalDeploymentConfiguration = Files.exists(localConfigurationFile.toPath(), NOFOLLOW_LINKS)
val deploymentSourceDirectory = if (usingLocalDeploymentConfiguration) {
    localDeploymentDirectory
} else {
    file("deployment")
}
if (!deploymentSourceDirectory.isDirectory || !deploymentSourceDirectory.resolve("Deployment.kt").isFile) {
    throw GradleException(
        "Deployment source directory ${deploymentSourceDirectory.relativeTo(projectDir)} must contain Deployment.kt; " +
            "an incomplete private configuration never falls back to the public server",
    )
}
// 公版 clone 也用该目录存放生成的部署状态（凭据、TLS 材料、vendor SDK）；目录里出现
// Kotlin 源文件却没有入口 Deployment.kt 时，按不完整的私有配置失败，而不是悄悄回退公版。
if (!usingLocalDeploymentConfiguration && localDeploymentDirectory.isDirectory) {
    val strayConfigurationSources = localDeploymentDirectory
        .listFiles { file -> file.isFile && file.extension == "kt" }
        .orEmpty()
    if (strayConfigurationSources.isNotEmpty()) {
        throw GradleException(
            "buildSrc/deployment-local contains Kotlin sources " +
                "(${strayConfigurationSources.joinToString { it.name }}) but is missing Deployment.kt; " +
                "a partial private configuration never falls back to the public server",
        )
    }
}
kotlin.sourceSets.named("main") {
    kotlin.srcDir(deploymentSourceDirectory)
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
}

dependencies {
    // Gradle 8's kotlin-dsl uses its embedded Kotlin compiler, independently of the product catalog.
    // Keep a compatible JSON runtime here; product modules use the newer serialization release.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.apache.commons:commons-compress:1.28.0")
    // Read references without changing bytecode when selecting Desktop's Material icon subset.
    implementation("org.ow2.asm:asm-commons:9.8")
    implementation("org.apache.sshd:sshd-sftp:2.19.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
    // Match the Android Gradle plugin: verify the actual signed APK before sealing a release.
    implementation("com.android.tools.build:apksig:8.13.2")
    // launch4j：把 bootstrap jar 包裹成 Windows GUI exe（core + 按宿主 OS 的 workdir 分类器，
    // 均来自 Maven Central；mac 产物为 x64 二进制，Apple Silicon 经 Rosetta 运行）。
    val hostWorkdirClassifier = when {
        org.gradle.internal.os.OperatingSystem.current().isMacOsX() -> "workdir-mac"
        org.gradle.internal.os.OperatingSystem.current().isLinux -> "workdir-linux64"
        else -> "workdir-win32"
    }
    implementation("net.sf.launch4j:launch4j:3.50:core")
    implementation("net.sf.launch4j:launch4j:3.50:${hostWorkdirClassifier}")
    testImplementation(kotlin("test"))
    // Debian 归档测试直接读取 data.tar.xz；生产打包通过统一进程执行器调用 tar。
    testImplementation("org.tukaani:xz:1.10")
}

// Gradle ProjectBuilder exercises the real packaging tasks in isolated temporary directories.
tasks.test {
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}
