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
            // 启动脚本：随构建打包，避免 rsync --delete 部署时丢失
            from("src/main/resources/bin/teamtalk.sh") {
                into("bin")
                fileMode = 0b111101101 // 0755
            }
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
    implementation(libs.bundles.netty)
    implementation(libs.bundles.exposed)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.rocksdb)
    implementation(libs.jbcrypt)
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.bundles.lucene)
    implementation(libs.ik.analyzer)
    implementation(libs.logback.classic)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("io.ktor:ktor-server-test-host:${libs.versions.ktor.get()}")
    testImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
    // 跨端编解码一致性测试需要客户端 Repository（:app 的 JVM target）
    testImplementation(project(":app"))
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.koin.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 管理后台 SPA：npm run build 产物拷入 resources（手动触发——前端低频变更）
val copyAdminDist by tasks.registering(Copy::class) {
    group = "build"
    description = "拷贝 admin/dist 到 server 静态资源（先 cd admin && npm run build）"
    from(rootProject.file("admin/dist"))
    into(layout.projectDirectory.dir("src/main/resources/static/admin"))
}

tasks.test {
    // 默认运行集成测试（基于 Embedded PostgreSQL，无需外部 DB）
    // 本地快速跳过：./gradlew :server:test -PskipTests
    onlyIf { !project.hasProperty("skipTests") }
    useJUnitPlatform()

    // 远程 demo E2E 开关透传：默认关闭，仅 -Dtk.e2e.remote=true 时启用 RemoteDemoE2eTest。
    // Gradle 默认不把命令行 -D 转发给测试 JVM，需显式桥接。
    listOf("tk.e2e.remote", "tk.e2e.host", "tk.e2e.port", "peer.action", "peer.arg", "peer.username", "peer.password", "peer.file", "peer.url", "peer.server").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

// 开发模式运行服务端，数据目录指向项目根/data
tasks.named<JavaExec>("run") {
    jvmArgs = listOf("-Dteamtalk.data.root=${rootProject.file("data").absolutePath}")
}

tasks.register<JavaExec>("runServer") {
    mainClass.set("com.virjar.tk.ApplicationKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf("-Dteamtalk.data.root=${rootProject.file("data").absolutePath}")
}
