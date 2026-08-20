import deployment.DeploymentConfig

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
                filePermissions { unix("rwxr-xr-x") }
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
    // 媒体缩略图：图片纯 Java2D；视频 javacv JNI（native 内嵌 jar，平台裁剪：服务器 linux + 开发 mac 双架构）
    implementation(libs.javacv)
    // 平台 classifier 依赖（version catalog 不支持 classifier，全坐标直写）
    implementation("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.natives.get()}")
    implementation("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.natives.get()}:linux-x86_64")
    implementation("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.natives.get()}:macosx-x86_64")
    implementation("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.natives.get()}:macosx-arm64")
    implementation(project(":protocol"))
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
    // E2E 对端使用产品客户端 SDK；生产服务端不依赖 :shared。
    testImplementation(project(":shared"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("io.ktor:ktor-server-test-host:${libs.versions.ktor.get()}")
    testImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
    // 跨端编解码一致性测试需要客户端 Repository（:app 的 JVM target）
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.koin.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 管理后台 SPA：发布产物以 admin/dist 为事实源。Sync 会移除旧 hash 资源，processResources
// 强制依赖它，避免前端已经构建但 server dist 仍静默携带旧页面。
val copyAdminDist by tasks.registering(Sync::class) {
    group = "build"
    description = "同步 admin/dist 到 server 静态资源（先 cd admin && npm run build）"
    from(rootProject.file("admin/dist"))
    into(layout.projectDirectory.dir("src/main/resources/static/admin"))
}

tasks.named("processResources") {
    dependsOn(copyAdminDist)
}

tasks.test {
    // CliPeerE2eTest 会启动 shared 的 headless agent；必须与当前协议一起重建，
    // 否则工作区残留的旧分发包会用旧 PROTOCOL_VERSION 无限重连。
    dependsOn(":shared:headlessDist")

    // 默认运行集成测试；PostgreSQL 连接由 TK_TEST_PG_* 提供，每个环境只使用自己的临时 schema。
    // 本地快速跳过：./gradlew :server:test -PskipTests
    onlyIf { !project.hasProperty("skipTests") }
    useJUnitPlatform()
    // DatabaseFactory is process-global. Every database-backed test owns a private PostgreSQL schema, while the
    // single test fork keeps Exposed's global default database from crossing concurrently active environments.
    maxParallelForks = 1
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    // 服务端测试会间接加载 :app 的 JVM 类和 Java2D 缩略图，但不应注册 macOS GUI 应用。
    // JBR 17 在新版 macOS 上尝试初始化 AppKit 会直接 SIGABRT（exit 134）；headless
    // 仍保留 BufferedImage/ImageIO 能力，并让服务端测试符合真实无界面运行形态。
    systemProperty("java.awt.headless", "true")

    // 远程 E2E 开关透传：默认关闭，仅 -Dtk.e2e.remote=true 时启用远程测试。
    // Gradle 默认不把命令行 -D 转发给测试 JVM，需显式桥接。
    listOf("tk.e2e.remote", "tk.e2e.host", "tk.e2e.port", "tk.e2e.server", "peer.action", "peer.arg", "peer.username", "peer.password", "peer.file", "peer.url", "peer.server").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

/**
 * 真实业务验收的唯一入口：始终对接 gradle/deployment.json 指定的服务器。
 * 本地 test 保留为快速的协议、存储与算法回归，不代替该任务。
 */
val deploymentConfig = rootProject.extra.get("deploymentConfig") as DeploymentConfig
tasks.register<Test>("acceptanceTest") {
    group = "verification"
    description = "Run business E2E tests against the configured deployment"
    dependsOn("testClasses")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    filter { includeTestsMatching("com.virjar.tk.e2e.RemoteAcceptanceTest") }
    systemProperty("tk.e2e.remote", "true")
    systemProperty("tk.e2e.host", deploymentConfig.tcpHost)
    systemProperty("tk.e2e.port", deploymentConfig.tcpPort)
    systemProperty("tk.e2e.server", deploymentConfig.serverUrl)
    outputs.upToDateWhen { false }
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
