plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.virjar.tk.ApplicationKt")
    applicationName = "server"
}

distributions {
    main {
        distributionBaseName.set("teamtalk-server")
        contents {
            from("src/main/resources/application.conf") { into("conf") }
            from("src/main/resources/logback.xml") { into("conf") }
            from("src/main/resources/static") { into("static") }
        }
    }
}

tasks.register("buildServerDist") {
    dependsOn("installDist")
    group = "distribution"
    description = "Build server distribution with start/stop scripts"
    doLast {
        val distDir = file("${layout.buildDirectory.get()}/install/teamtalk-server")
        mkdir("${distDir}/data")
        mkdir("${distDir}/logs")
        mkdir("${distDir}/static/downloads")
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.exposed)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.jbcrypt)
    implementation(libs.java.jwt)
    implementation(libs.rocksdb)
    implementation(libs.bundles.lucene)
    implementation(libs.ik.analyzer)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:${libs.versions.ktor.get()}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.register<JavaExec>("runServer") {
    group = "run"
    description = "Run the TeamTalk server"
    mainClass.set("com.virjar.tk.ApplicationKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// 将 application 插件自带的 run 任务归入 "run" group
tasks.named("run") {
    group = "run"
}

tasks.test {
    useJUnitPlatform()
}
