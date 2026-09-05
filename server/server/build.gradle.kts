import deployment.DeploymentConfig
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.virjar.tk.server.ApplicationKt")
    applicationName = "server"
}

tasks.named<Jar>("jar") {
    // Deployment requires lib/server.jar; release identity remains in the generated manifests.
    archiveFileName.set("server.jar")
}

val releaseVersion = rootProject.extra.get("releaseVersion") as String
val buildIdentity = rootProject.extra.get("buildIdentity") as String
val serverProtocolWindow = deployment.ServerProtocolWindow(
    major = rootProject.extra.get("protocolMajor") as Int,
    minimumMinor = rootProject.extra.get("minimumProtocolMinor") as Int,
    currentMinor = rootProject.extra.get("protocolMinor") as Int,
)
// Admin owns its toolchain and producer tasks; Server consumes only its built artifact.
val adminDist by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val buildAdmin by tasks.registering {
    group = "build"
    description = "Build the Admin frontend consumed by Server (compatibility entry point)"
    dependsOn(adminDist)
}

val generatedBuildIdentityResources = layout.buildDirectory.dir("generated/build-identity/resources")
val generatedServerManifest = layout.buildDirectory.file(
    "generated/build-identity/distribution/${deployment.RELEASE_ARTIFACT_MANIFEST_FILE}",
)

val generateServerBuildIdentity by tasks.registering {
    group = "build"
    description = "Generate the Server runtime and distribution build identity manifests"
    inputs.property("releaseVersion", releaseVersion)
    inputs.property("buildIdentity", buildIdentity)
    inputs.property("protocolMajor", serverProtocolWindow.major)
    inputs.property("minimumProtocolMinor", serverProtocolWindow.minimumMinor)
    inputs.property("protocolMinor", serverProtocolWindow.currentMinor)
    outputs.dir(generatedBuildIdentityResources)
    outputs.file(generatedServerManifest)
    doLast {
        deployment.writeReleaseArtifactManifestFile(
            generatedBuildIdentityResources.get().file("teamtalk-build.properties").asFile,
            "server-runtime",
            releaseVersion,
            buildIdentity,
            serverProtocolWindow,
        )
        deployment.writeReleaseArtifactManifestFile(
            generatedServerManifest.get().asFile,
            "server-distribution",
            releaseVersion,
            buildIdentity,
            serverProtocolWindow,
        )
    }
}

distributions {
    main {
        distributionBaseName.set("teamtalk-server")
        contents {
            from(rootProject.file("LICENSE"))
            from("src/main/resources/application.conf") { into("conf") }
            from("src/main/resources/logback.xml") { into("conf") }
            from("src/main/resources/static") {
                exclude("admin/**")
                into("static")
            }
            from(adminDist) {
                into("static/admin")
            }
            from(files(generatedServerManifest).builtBy(generateServerBuildIdentity))
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
    add(adminDist.name, project(path = ":server:admin", configuration = "adminDist"))
    // Koin contributes ktor-server-di; it must use the same Ktor release as our HTTP engine.
    implementation(platform(libs.ktor.bom))
    // 媒体缩略图：图片纯 Java2D；视频 javacv JNI（native 内嵌 jar，平台裁剪：服务器 linux + 开发 mac 双架构）
    implementation(libs.javacv)
    // 平台 classifier 依赖（version catalog 不支持 classifier，全坐标直写）
    implementation("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.natives.get()}")
    implementation("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.natives.get()}:linux-x86_64")
    implementation("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.natives.get()}:macosx-x86_64")
    implementation("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.natives.get()}:macosx-arm64")
    implementation(project(":protocol:protocol"))
    implementation(project(":protocol:protocol-netty"))
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
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    // E2E 对端使用产品客户端 SDK 和独立 testkit；生产服务端不依赖两者。
    testImplementation(project(":client:shared"))
    testImplementation(project(":client:shared-testkit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("io.ktor:ktor-server-test-host:${libs.versions.ktor.get()}")
    testImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
    testImplementation("io.ktor:ktor-network-tls-certificates:${libs.versions.ktor.get()}")
    // 跨端编解码一致性测试需要客户端 Repository（:client:app 的 JVM target）
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.koin.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Remote capacity baselines are intentionally isolated from the ordinary test source set. The
// capacity code may reuse deterministic statistics and RemoteAcceptanceSupport from test output,
// but `server:test` never compiles or discovers the remote scenario itself.
val capacityTestSourceSet = sourceSets.create("capacityTest") {
    compileClasspath += sourceSets["main"].output + sourceSets["test"].output
    runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
}
configurations[capacityTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[capacityTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

// Admin 是 Server 分发的正式 producer；源码目录里的历史 dist 永不进入运行产物。
sourceSets.named("main") {
    resources.exclude("static/admin/**")
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildAdmin, generateServerBuildIdentity)
    from(adminDist) { into("static/admin") }
    from(generatedBuildIdentityResources)
}

tasks.named("installDist") {
    dependsOn(buildAdmin, generateServerBuildIdentity)
}

tasks.named("check") { dependsOn(buildAdmin) }

tasks.test {
    // CliPeerE2eTest 会启动 shared 的 headless agent；必须与当前协议一起重建，
    // 否则工作区残留的旧分发包会用旧 PROTOCOL_VERSION 无限重连。
    dependsOn(":client:shared:headlessDist")

    // 默认运行集成测试；PostgreSQL 连接由 TK_TEST_PG_* 提供，每个环境只使用自己的临时 schema。
    // 本地快速跳过：./gradlew :server:server:test -PskipTests
    onlyIf { !project.hasProperty("skipTests") }
    useJUnitPlatform()
    // Runtime containers bind independent Database/pool instances and isolation is covered with two simultaneous
    // TestEnvironments. Keep one fork to bound PostgreSQL connections and native RocksDB/Lucene resources for the
    // complete suite; this is a resource policy, not a database-ownership correctness requirement.
    maxParallelForks = 1
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    // 服务端测试会间接加载 :client:app 的 JVM 类和 Java2D 缩略图，但不应注册 macOS GUI 应用。
    // JBR 17 在新版 macOS 上尝试初始化 AppKit 会直接 SIGABRT（exit 134）；headless
    // 仍保留 BufferedImage/ImageIO 能力，并让服务端测试符合真实无界面运行形态。
    systemProperty("java.awt.headless", "true")
    // Thumbnail decoding runs in a helper JVM. Gradle test workers expose only their bootstrap jar
    // through java.class.path, so provide the real test runtime path explicitly to child helpers.
    doFirst {
        systemProperty("teamtalk.thumbnail.helper.classpath", sourceSets["test"].runtimeClasspath.asPath)
    }

    // 远程 E2E 开关透传：默认关闭，仅 -Dtk.e2e.remote=true 时启用远程测试。
    // Gradle 默认不把命令行 -D 转发给测试 JVM，需显式桥接。
    listOf("tk.e2e.remote", "tk.e2e.host", "tk.e2e.port", "tk.e2e.server", "peer.action", "peer.arg", "peer.username", "peer.password", "peer.file", "peer.url", "peer.server").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

/**
 * 真实部署验收：始终对接 gradle/deployment.json 指定的服务器。
 * 本地 test 保留为快速的协议、存储与算法回归，不代替该任务。
 */
val deploymentConfig = rootProject.extra.get("deploymentConfig") as DeploymentConfig
fun Test.configureRemoteBusinessTests() {
    group = "verification"
    dependsOn("testClasses")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    filter { includeTestsMatching("com.virjar.tk.server.e2e.RemoteAcceptanceTest") }
    systemProperty("tk.e2e.remote", "true")
    systemProperty("tk.e2e.host", deploymentConfig.tcpHost)
    systemProperty("tk.e2e.port", deploymentConfig.tcpPort)
    systemProperty("tk.e2e.server", deploymentConfig.serverUrl)
    maxParallelForks = 1
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    outputs.upToDateWhen { false }
}

tasks.register<Test>("acceptanceTest") {
    description = "Run business E2E tests against the configured deployment"
    configureRemoteBusinessTests()
    systemProperty("tk.e2e.deploy.host", deploymentConfig.deployHost)
    systemProperty("tk.e2e.deploy.user", deploymentConfig.deployUser)
    systemProperty("tk.e2e.deploy.port", deploymentConfig.deployPort)
    systemProperty("tk.e2e.deploy.path", deploymentConfig.deployPath)
}

// Reuse the ordinary business scenarios, excluding process kills, service restarts,
// admin fixtures and capacity runs. This is a preview smoke, not a release certificate.
tasks.register<Test>("previewSmokeTest") {
    description = "Run the bounded developer-preview business smoke against the configured deployment"
    configureRemoteBusinessTests()
    useJUnitPlatform { includeTags("preview-smoke") }
}

/**
 * Explicit, bounded remote message capacity baseline. This is development evidence, not a
 * production benchmark or product capacity promise, and never participates in ordinary test.
 */
tasks.register<Test>("capacityTest") {
    group = "verification"
    description = "Run the configurable remote message capacity baseline and write JSON evidence"
    dependsOn("testClasses", capacityTestSourceSet.classesTaskName)
    testClassesDirs = capacityTestSourceSet.output.classesDirs
    classpath = capacityTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    filter {
        includeTestsMatching("com.virjar.tk.server.e2e.capacity.RemoteMessageCapacityBaselineTest")
    }
    systemProperty("tk.e2e.remote", "true")
    systemProperty("tk.e2e.host", deploymentConfig.tcpHost)
    systemProperty("tk.e2e.port", deploymentConfig.tcpPort)
    systemProperty("tk.e2e.server", deploymentConfig.serverUrl)

    val capacityProperties = mapOf(
        "tk.capacity.sender.lanes" to "capacitySenderLanes",
        "tk.capacity.warmup.messagesPerLane" to "capacityWarmupMessagesPerLane",
        "tk.capacity.steady.messagesPerLane" to "capacitySteadyMessagesPerLane",
        "tk.capacity.steady.intervalMs" to "capacitySteadyIntervalMs",
        "tk.capacity.burst.messagesTotal" to "capacityBurstMessagesTotal",
        "tk.capacity.burst.concurrency" to "capacityBurstConcurrency",
        "tk.capacity.ack.timeoutMs" to "capacityAckTimeoutMs",
        "tk.capacity.delivery.timeoutMs" to "capacityDeliveryTimeoutMs",
        "tk.capacity.recovery.timeoutMs" to "capacityRecoveryTimeoutMs",
        "tk.capacity.recovery.retryIntervalMs" to "capacityRecoveryRetryIntervalMs",
        "tk.capacity.eventCatchup.timeoutMs" to "capacityEventCatchupTimeoutMs",
        "tk.capacity.eventCatchup.minimumEvents" to "capacityEventCatchupMinimumEvents",
    )
    val capacityReportOutput = providers.systemProperty("tk.capacity.report")
        .orElse(providers.gradleProperty("capacityReport"))
        .orElse(
            layout.buildDirectory.file("reports/capacity/message-capacity.json")
                .map { it.asFile.absolutePath },
        )
    doFirst {
        capacityProperties.forEach { (systemName, gradleName) ->
            val configured = System.getProperty(systemName)
                ?: project.findProperty(gradleName)?.toString()
            if (configured != null) systemProperty(systemName, configured)
        }
        systemProperty("tk.capacity.report", capacityReportOutput.get())
    }
    outputs.file(capacityReportOutput)
    outputs.upToDateWhen { false }
}

/**
 * Real-client connection/authentication baseline. Kept separate from message capacity so a run
 * has one load shape, one report, and an unambiguous failure boundary.
 */
tasks.register<Test>("connectionCapacityTest") {
    group = "verification"
    description = "Run the remote connection, hold, and reconnect capacity baseline"
    dependsOn("testClasses", capacityTestSourceSet.classesTaskName)
    testClassesDirs = capacityTestSourceSet.output.classesDirs
    classpath = capacityTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    filter {
        includeTestsMatching("com.virjar.tk.server.e2e.capacity.RemoteConnectionCapacityBaselineTest")
    }
    systemProperty("tk.e2e.remote", "true")
    systemProperty("tk.e2e.host", deploymentConfig.tcpHost)
    systemProperty("tk.e2e.port", deploymentConfig.tcpPort)
    systemProperty("tk.e2e.server", deploymentConfig.serverUrl)
    systemProperty("tk.e2e.deploy.host", deploymentConfig.deployHost)
    systemProperty("tk.e2e.deploy.user", deploymentConfig.deployUser)
    systemProperty("tk.e2e.deploy.port", deploymentConfig.deployPort)
    systemProperty("tk.capacity.deploy.sslEnabled", deploymentConfig.sslEnabled)
    systemProperty(
        "tk.capacity.deploy.httpPort",
        deploymentConfig.serverUri.port.takeIf { it > 0 } ?: 80,
    )
    systemProperty("tk.capacity.deploy.sslPort", deploymentConfig.sslPort)

    val connectionProperties = mapOf(
        "tk.connectionCapacity.clients" to "connectionCapacityClients",
        "tk.connectionCapacity.ramp.groupSize" to "connectionCapacityRampGroupSize",
        "tk.connectionCapacity.ramp.intervalMs" to "connectionCapacityRampIntervalMs",
        "tk.connectionCapacity.hold.durationMs" to "connectionCapacityHoldDurationMs",
        "tk.connectionCapacity.reconnect.clients" to "connectionCapacityReconnectClients",
        "tk.connectionCapacity.reconnect.timeoutMs" to "connectionCapacityReconnectTimeoutMs",
        "tk.connectionCapacity.sample.intervalMs" to "connectionCapacitySampleIntervalMs",
        "tk.connectionCapacity.cleanup.observationMs" to "connectionCapacityCleanupObservationMs",
    )
    val connectionReportOutput = providers.systemProperty("tk.connectionCapacity.report")
        .orElse(providers.gradleProperty("connectionCapacityReport"))
        .orElse(
            layout.buildDirectory.file("reports/capacity/connection-capacity.json")
                .map { it.asFile.absolutePath },
    )
    doFirst {
        val staleReport = file(connectionReportOutput.get())
        if (staleReport.exists()) {
            check(staleReport.delete()) {
                "Unable to remove stale connection capacity report: ${staleReport.absolutePath}"
            }
        }
        connectionProperties.forEach { (systemName, gradleName) ->
            val configured = System.getProperty(systemName)
                ?: project.findProperty(gradleName)?.toString()
            if (configured != null) systemProperty(systemName, configured)
        }
        systemProperty("tk.connectionCapacity.report", connectionReportOutput.get())
    }
    outputs.file(connectionReportOutput)
    outputs.upToDateWhen { false }
}

/**
 * Real-client first-page search baseline. Message and user requests use their production binary
 * RPCs and distinct user command lanes; no in-process search shortcut participates in the run.
 */
val searchCapacityReportOutput = providers.systemProperty("tk.searchCapacity.report")
    .orElse(providers.gradleProperty("searchCapacityReport"))
    .orElse(
        layout.buildDirectory.file("reports/capacity/search-capacity.json")
            .map { it.asFile.absolutePath },
    )
val searchCapacityTestTask = tasks.register<Test>("searchCapacityTest") {
    group = "verification"
    description = "Run the remote multi-user message and directory search capacity baseline"
    dependsOn("testClasses", capacityTestSourceSet.classesTaskName)
    testClassesDirs = capacityTestSourceSet.output.classesDirs
    classpath = capacityTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    filter {
        includeTestsMatching("com.virjar.tk.server.e2e.capacity.RemoteSearchCapacityBaselineTest")
    }
    systemProperty("tk.e2e.remote", "true")
    systemProperty("tk.e2e.host", deploymentConfig.tcpHost)
    systemProperty("tk.e2e.port", deploymentConfig.tcpPort)
    systemProperty("tk.e2e.server", deploymentConfig.serverUrl)
    systemProperty("tk.e2e.deploy.host", deploymentConfig.deployHost)
    systemProperty("tk.e2e.deploy.user", deploymentConfig.deployUser)
    systemProperty("tk.e2e.deploy.port", deploymentConfig.deployPort)
    systemProperty("tk.capacity.deploy.sslEnabled", deploymentConfig.sslEnabled)
    systemProperty(
        "tk.capacity.deploy.httpPort",
        deploymentConfig.serverUri.port.takeIf { it > 0 } ?: 80,
    )
    systemProperty("tk.capacity.deploy.sslPort", deploymentConfig.sslPort)

    val searchProperties = mapOf(
        "tk.searchCapacity.users" to "searchCapacityUsers",
        "tk.searchCapacity.chats" to "searchCapacityChats",
        "tk.searchCapacity.messagesPerChat" to "searchCapacityMessagesPerChat",
        "tk.searchCapacity.warmup.cycles" to "searchCapacityWarmupCycles",
        "tk.searchCapacity.steady.queriesPerUser" to "searchCapacitySteadyQueriesPerUser",
        "tk.searchCapacity.steady.intervalMs" to "searchCapacitySteadyIntervalMs",
        "tk.searchCapacity.burst.cycles" to "searchCapacityBurstCycles",
        "tk.searchCapacity.burst.concurrency" to "searchCapacityBurstConcurrency",
        "tk.searchCapacity.projection.timeoutMs" to "searchCapacityProjectionTimeoutMs",
        "tk.searchCapacity.sample.intervalMs" to "searchCapacitySampleIntervalMs",
        "tk.searchCapacity.cleanup.observationMs" to "searchCapacityCleanupObservationMs",
    )
    doFirst {
        searchProperties.forEach { (systemName, gradleName) ->
            val configured = System.getProperty(systemName)
                ?: project.findProperty(gradleName)?.toString()
            if (configured != null) systemProperty(systemName, configured)
        }
        systemProperty("tk.searchCapacity.report", searchCapacityReportOutput.get())
    }
    outputs.file(searchCapacityReportOutput)
    outputs.upToDateWhen { false }
}

// Task actions start after their compile dependencies. Clear stale evidence as soon as this task
// graph is accepted so a compilation failure cannot leave a previous successful report behind.
gradle.taskGraph.whenReady {
    if (!gradle.startParameter.isDryRun && hasTask(searchCapacityTestTask.get())) {
        val staleReport = file(searchCapacityReportOutput.get())
        if (staleReport.exists()) {
            check(staleReport.delete()) {
                "Unable to remove stale search capacity report: ${staleReport.absolutePath}"
            }
        }
    }
}

/**
 * Real SDK attachment upload, group-file publication, and authenticated peer-download baseline.
 * Capacity samples use ordinary one-shot upload identities; explicit same-identity recovery after
 * an unknown response is covered by the deployment acceptance suite instead of skewing throughput.
 */
val attachmentCapacityReportOutput = providers.systemProperty("tk.attachmentCapacity.report")
    .orElse(providers.gradleProperty("attachmentCapacityReport"))
    .orElse(
        layout.buildDirectory.file("reports/capacity/attachment-capacity.json")
            .map { it.asFile.absolutePath },
    )
val attachmentCapacityTestTask = tasks.register<Test>("attachmentCapacityTest") {
    group = "verification"
    description = "Run the remote attachment upload and authenticated download capacity baseline"
    dependsOn("testClasses", capacityTestSourceSet.classesTaskName)
    testClassesDirs = capacityTestSourceSet.output.classesDirs
    classpath = capacityTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    filter {
        includeTestsMatching("com.virjar.tk.server.e2e.capacity.RemoteAttachmentCapacityBaselineTest")
    }
    systemProperty("tk.e2e.remote", "true")
    systemProperty("tk.e2e.host", deploymentConfig.tcpHost)
    systemProperty("tk.e2e.port", deploymentConfig.tcpPort)
    systemProperty("tk.e2e.server", deploymentConfig.serverUrl)
    systemProperty("tk.e2e.deploy.host", deploymentConfig.deployHost)
    systemProperty("tk.e2e.deploy.user", deploymentConfig.deployUser)
    systemProperty("tk.e2e.deploy.port", deploymentConfig.deployPort)
    systemProperty("tk.capacity.deploy.sslEnabled", deploymentConfig.sslEnabled)
    systemProperty(
        "tk.capacity.deploy.httpPort",
        deploymentConfig.serverUri.port.takeIf { it > 0 } ?: 80,
    )
    systemProperty("tk.capacity.deploy.sslPort", deploymentConfig.sslPort)

    val attachmentProperties = mapOf(
        "tk.attachmentCapacity.users" to "attachmentCapacityUsers",
        "tk.attachmentCapacity.payloadBytes" to "attachmentCapacityPayloadBytes",
        "tk.attachmentCapacity.warmup.uploadsPerUser" to
            "attachmentCapacityWarmupUploadsPerUser",
        "tk.attachmentCapacity.steady.uploadsPerUser" to
            "attachmentCapacitySteadyUploadsPerUser",
        "tk.attachmentCapacity.steady.intervalMs" to "attachmentCapacitySteadyIntervalMs",
        "tk.attachmentCapacity.burst.uploadsTotal" to "attachmentCapacityBurstUploadsTotal",
        "tk.attachmentCapacity.burst.concurrency" to "attachmentCapacityBurstConcurrency",
        "tk.attachmentCapacity.downloadsPerAttachment" to
            "attachmentCapacityDownloadsPerAttachment",
        "tk.attachmentCapacity.download.concurrency" to
            "attachmentCapacityDownloadConcurrency",
        "tk.attachmentCapacity.request.timeoutMs" to "attachmentCapacityRequestTimeoutMs",
        "tk.attachmentCapacity.sample.intervalMs" to "attachmentCapacitySampleIntervalMs",
        "tk.attachmentCapacity.cleanup.observationMs" to
            "attachmentCapacityCleanupObservationMs",
    )
    doFirst {
        attachmentProperties.forEach { (systemName, gradleName) ->
            val configured = System.getProperty(systemName)
                ?: project.findProperty(gradleName)?.toString()
            if (configured != null) systemProperty(systemName, configured)
        }
        systemProperty("tk.attachmentCapacity.report", attachmentCapacityReportOutput.get())
    }
    outputs.file(attachmentCapacityReportOutput)
    outputs.upToDateWhen { false }
}

gradle.taskGraph.whenReady {
    if (!gradle.startParameter.isDryRun && hasTask(attachmentCapacityTestTask.get())) {
        val staleReport = file(attachmentCapacityReportOutput.get())
        if (staleReport.exists()) {
            check(staleReport.delete()) {
                "Unable to remove stale attachment capacity report: ${staleReport.absolutePath}"
            }
        }
    }
}

/**
 * One isolated >32 MiB FileStore filesystem-tier gate. The scenario performs exactly one bounded
 * TeamTalk unit restart and never changes host networking or stops the service directly.
 */
val filesystemTierCapacityReportOutput = providers
    .systemProperty("tk.filesystemTierCapacity.report")
    .orElse(providers.gradleProperty("filesystemTierCapacityReport"))
    .orElse(
        layout.buildDirectory.file("reports/capacity/filesystem-tier-capacity.json")
            .map { it.asFile.absolutePath },
    )
val filesystemTierCapacityTestTask = tasks.register<Test>("filesystemTierCapacityTest") {
    group = "verification"
    description = "Run the remote >32 MiB FileStore filesystem-tier restart gate"
    dependsOn("testClasses", capacityTestSourceSet.classesTaskName)
    testClassesDirs = capacityTestSourceSet.output.classesDirs
    classpath = capacityTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    filter {
        includeTestsMatching("com.virjar.tk.server.e2e.capacity.RemoteFileSystemTierCapacityTest")
    }
    systemProperty("tk.e2e.remote", "true")
    systemProperty("tk.e2e.host", deploymentConfig.tcpHost)
    systemProperty("tk.e2e.port", deploymentConfig.tcpPort)
    systemProperty("tk.e2e.server", deploymentConfig.serverUrl)
    systemProperty("tk.e2e.deploy.host", deploymentConfig.deployHost)
    systemProperty("tk.e2e.deploy.user", deploymentConfig.deployUser)
    systemProperty("tk.e2e.deploy.port", deploymentConfig.deployPort)
    systemProperty("tk.e2e.deploy.path", deploymentConfig.deployPath)

    val properties = mapOf(
        "tk.filesystemTierCapacity.payloadBytes" to "filesystemTierCapacityPayloadBytes",
        "tk.filesystemTierCapacity.request.timeoutMs" to
            "filesystemTierCapacityRequestTimeoutMs",
    )
    doFirst {
        properties.forEach { (systemName, gradleName) ->
            val configured = System.getProperty(systemName)
                ?: project.findProperty(gradleName)?.toString()
            if (configured != null) systemProperty(systemName, configured)
        }
        systemProperty(
            "tk.filesystemTierCapacity.report",
            filesystemTierCapacityReportOutput.get(),
        )
    }
    outputs.file(filesystemTierCapacityReportOutput)
    outputs.upToDateWhen { false }
}

gradle.taskGraph.whenReady {
    if (!gradle.startParameter.isDryRun && hasTask(filesystemTierCapacityTestTask.get())) {
        val staleReport = file(filesystemTierCapacityReportOutput.get())
        if (staleReport.exists()) {
            check(staleReport.delete()) {
                "Unable to remove stale filesystem-tier capacity report: ${staleReport.absolutePath}"
            }
        }
    }
}

/**
 * Reusable real-client document fixture driver for Desktop/Android UI acceptance.
 *
 * The implementation lives on the test runtime classpath so it can reuse the public client SDK
 * and the remote-acceptance transport without entering the production server distribution. The
 * main program admits credentials only from its private state directory; this task injects only
 * the non-secret deployment identity selected by gradle/deployment.json.
 */
tasks.register<JavaExec>("documentFixture") {
    group = "verification"
    description = "Seed or archive the 150-page document UI acceptance fixture"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.virjar.tk.server.e2e.DocumentFixtureMainKt")
    systemProperty("java.awt.headless", "true")
    systemProperty("tk.e2e.host", deploymentConfig.tcpHost)
    systemProperty("tk.e2e.port", deploymentConfig.tcpPort)
    systemProperty("tk.e2e.projectRoot", rootProject.projectDir.absolutePath)
    outputs.upToDateWhen { false }
}

// 开发模式运行服务端，数据目录指向项目根/data
tasks.named<JavaExec>("run") {
    jvmArgs = listOf("-Dteamtalk.data.root=${rootProject.file("data").absolutePath}")
}

tasks.register<JavaExec>("runServer") {
    mainClass.set("com.virjar.tk.server.ApplicationKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf("-Dteamtalk.data.root=${rootProject.file("data").absolutePath}")
}
