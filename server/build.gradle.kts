plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

val ktorVersion: String by rootProject.extra
val exposedVersion: String by rootProject.extra
val kotlinxSerializationVersion: String by rootProject.extra
val kotlinxCoroutinesVersion: String by rootProject.extra
val rocksdbVersion: String by rootProject.extra
val logbackVersion: String by rootProject.extra
val luceneVersion: String by rootProject.extra

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

    // Ktor
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")

    // Exposed + PostgreSQL
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("com.auth0:java-jwt:0.12.6")

    // RocksDB
    implementation("org.rocksdb:rocksdbjni:$rocksdbVersion")

    // Lucene full-text search
    implementation("org.apache.lucene:lucene-core:$luceneVersion")
    implementation("org.apache.lucene:lucene-queryparser:$luceneVersion")
    implementation("org.apache.lucene:lucene-highlighter:$luceneVersion")
    implementation("cn.shenyanchao.ik-analyzer:ik-analyzer:9.0.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
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
