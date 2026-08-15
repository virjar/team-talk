plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:${libs.versions.ksp.get()}")
}
