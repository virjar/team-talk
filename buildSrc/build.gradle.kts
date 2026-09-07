import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

plugins {
    `kotlin-dsl`
}

// 普通 Kotlin 源文件参与真实编译和 IDE 导航。私有目录完整替换公版目录，不能同时编译两套入口。
val localDeploymentDirectory = file("deployment-local")
val deploymentSourceDirectory = if (Files.exists(localDeploymentDirectory.toPath(), NOFOLLOW_LINKS)) {
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
    implementation("com.typesafe:config:1.4.3")
    // Read references without changing bytecode when selecting Desktop's Material icon subset.
    implementation("org.ow2.asm:asm-commons:9.8")
    implementation("org.apache.sshd:sshd-sftp:2.19.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
    // Match the Android Gradle plugin: verify the actual signed APK before sealing a release.
    implementation("com.android.tools.build:apksig:8.13.2")
    testImplementation(kotlin("test"))
}
