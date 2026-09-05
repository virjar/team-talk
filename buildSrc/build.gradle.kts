plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Gradle 8's kotlin-dsl uses its embedded Kotlin compiler, independently of the product catalog.
    // Keep a compatible JSON runtime here; product modules use the newer serialization release.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(kotlin("test"))
}
