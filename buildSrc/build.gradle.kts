plugins {
    `kotlin-dsl`
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
    implementation("org.apache.sshd:sshd-sftp:2.19.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
    // Match the Android Gradle plugin: verify the actual signed APK before sealing a release.
    implementation("com.android.tools.build:apksig:8.13.2")
    testImplementation(kotlin("test"))
}
