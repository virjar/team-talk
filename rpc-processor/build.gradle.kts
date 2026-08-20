plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:${libs.versions.ksp.get()}")

    testImplementation(kotlin("test"))
    testImplementation("dev.zacsweers.kctfork:ksp:0.12.1")
    // compile-testing 0.12.1 自带较早的 KSP2；与生产 processor 使用的版本严格对齐。
    testRuntimeOnly("com.google.devtools.ksp:symbol-processing-common-deps:${libs.versions.ksp.get()}")
    testRuntimeOnly("com.google.devtools.ksp:symbol-processing-aa-embeddable:${libs.versions.ksp.get()}")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
}
