import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/** 将 AGENTS.md 中的源代码依赖规则变为可执行检查，而不是依赖评审记忆。 */
abstract class ArchitectureCheckTask : DefaultTask() {
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Input
    abstract val configuredDependencyViolations: ListProperty<String>

    @get:Input
    abstract val trackedAdminGeneratedFiles: ListProperty<String>

    @TaskAction
    fun checkBoundaries() {
        val root = repositoryRoot.get().asFile
        val rules = listOf(
            Rule(
                name = "protocol contract purity",
                relativeRoot = "protocol/protocol/src/commonMain",
                forbiddenImports = listOf(
                    "com.virjar.tk.shared.client.",
                    "com.virjar.tk.shared.repository.",
                    "com.virjar.tk.app.navigation.",
                    "com.virjar.tk.app.ui.",
                    "com.virjar.tk.server.domain.",
                    "com.virjar.tk.server.infra.",
                    "com.virjar.tk.server.api.",
                    "io.netty.",
                ),
            ),
            Rule(
                name = "shared SDK has no UI or server dependency",
                relativeRoot = "client/shared/src/commonMain",
                forbiddenImports = listOf(
                    "androidx.compose.",
                    "org.jetbrains.compose.",
                    "com.virjar.tk.app.ui.",
                    "com.virjar.tk.app.navigation.",
                    "com.virjar.tk.server.domain.",
                    "com.virjar.tk.server.infra.",
                    "com.virjar.tk.server.api.",
                ),
            ),
            Rule(
                name = "shared UI has no server or transport implementation dependency",
                relativeRoot = "client/app/src/commonMain",
                forbiddenImports = listOf(
                    "com.virjar.tk.server.domain.",
                    "com.virjar.tk.server.infra.",
                    "com.virjar.tk.server.api.",
                    "org.jetbrains.exposed.",
                    "io.ktor.server.",
                    "io.netty.",
                ),
            ),
            Rule(
                name = "server domain has no outer adapter dependency",
                relativeRoot = "server/server/src/main/kotlin/com/virjar/tk/server/domain",
                forbiddenImports = listOf(
                    "com.virjar.tk.server.infra.",
                    "com.virjar.tk.server.api.",
                    "com.virjar.tk.server.protocol.connection.",
                    "com.virjar.tk.server.protocol.executor.",
                    "com.virjar.tk.server.protocol.rpc.",
                    "com.virjar.tk.protocol.rpc.gen.",
                    "org.jetbrains.exposed.",
                    "io.ktor.",
                    "io.netty.",
                ),
            ),
            Rule(
                name = "server application depends on ports instead of outer adapters",
                relativeRoot = "server/server/src/main/kotlin/com/virjar/tk/server/application",
                forbiddenImports = listOf(
                    "com.virjar.tk.server.infra.",
                    "org.jetbrains.exposed.",
                    "io.ktor.",
                    "io.netty.",
                    "java.io.File",
                ),
            ),
            Rule(
                name = "bot domain depends on ports instead of sibling services",
                relativeRoot = "server/server/src/main/kotlin/com/virjar/tk/server/domain/bot",
                forbiddenImports = listOf(
                    "com.virjar.tk.server.domain.chat.ChatService",
                    "com.virjar.tk.server.domain.chat.ChatStore",
                    "com.virjar.tk.server.domain.message.MessageService",
                    "com.virjar.tk.server.domain.user.UserService",
                ),
            ),
            Rule(
                name = "server production does not depend on the client SDK or UI",
                relativeRoot = "server/server/src/main",
                forbiddenImports = listOf(
                    "com.virjar.tk.shared.client.",
                    "com.virjar.tk.shared.repository.",
                    "com.virjar.tk.app.navigation.",
                    "com.virjar.tk.app.ui.",
                ),
            ),
        )

        val productMainSourceRoots = listOf(
            "protocol/protocol/src/commonMain",
            "protocol/protocol-netty/src/commonMain",
            "client/shared/src/commonMain",
            "client/shared/src/jvmAndAndroidMain",
            "client/shared/src/androidMain",
            "client/shared/src/jvmMain",
            "client/richeditor/src/commonMain",
            "client/richeditor/src/androidMain",
            "client/richeditor/src/desktopMain",
            "client/app/src/commonMain",
            "client/app/src/androidMain",
            "client/app/src/desktopMain",
            "client/android/src/main",
            "client/desktop/src/desktopMain",
            "server/server/src/main",
            "protocol/rpc-processor/src/main",
        )

        val sourcePatternRules = listOf(
            SourcePatternRule(
                name = "testkit stays outside product source sets",
                relativeRoots = productMainSourceRoots,
                forbiddenPatterns = listOf(
                    Regex("\\bcom\\.virjar\\.tk\\.testing(?:\\.|\\b)") to
                        "move test doubles to :shared-testkit and depend on them only from tests",
                ),
            ),
            SourcePatternRule(
                name = "product executors are explicitly bounded",
                relativeRoots = productMainSourceRoots,
                forbiddenPatterns = listOf(
                    Regex("\\bExecutors\\s*\\.\\s*newCachedThreadPool\\s*\\(") to
                        "use an owned, explicitly bounded executor in product code",
                    Regex("^\\s*import\\s+java\\.util\\.concurrent\\.LinkedBlocking(?:Queue|Deque)\\b") to
                        "use a fixed-capacity queue in product code",
                    Regex("\\.waitFor\\s*\\(\\s*\\)") to
                        "use an owned subprocess runner with an explicit timeout and termination path",
                ),
            ),
            SourcePatternRule(
                name = "product Netty EventLoops use the Netty 4.2 IO API",
                relativeRoots = productMainSourceRoots,
                forbiddenPatterns = listOf(
                    Regex("\\bNioEventLoopGroup\\b") to
                        "use MultiThreadIoEventLoopGroup with the matching IoHandler factory",
                ),
            ),
            SourcePatternRule(
                name = "client credentials have no process-global session singleton",
                relativeRoots = listOf(
                    "client/shared/src/commonMain",
                    "client/app/src/commonMain",
                    "client/android/src/main",
                    "client/desktop/src/desktopMain",
                ),
                forbiddenPatterns = listOf(
                    Regex("\\bSessionContext\\b") to
                        "bearer credentials must be provided by an authenticated session owner",
                ),
            ),
            SourcePatternRule(
                name = "desktop authenticated work has structured ownership",
                relativeRoots = listOf("client/desktop/src/desktopMain"),
                forbiddenPatterns = listOf(
                    Regex("\\brunBlocking\\s*\\(") to
                        "desktop UI/session shutdown must never block its caller",
                    Regex("\\bGlobalScope\\b") to
                        "desktop background work must belong to a closeable session scope",
                    Regex("\\bkotlin\\.concurrent\\.thread\\s*\\{") to
                        "desktop background work must belong to a closeable session scope",
                    Regex("\\bDesktopMediaCache\\s*\\.\\s*(init|initialize)\\s*\\(") to
                        "desktop media cache is session-owned and must not be globally initialized",
                    Regex("\\bobject\\s+DesktopMediaCache\\b") to
                        "desktop media cache must remain a session-owned instance",
                ),
            ),
            SourcePatternRule(
                name = "desktop modal chrome has one owner",
                relativeRoots = listOf("client/desktop/src/desktopMain"),
                forbiddenPatterns = listOf(
                    Regex("^(?!\\s*(?://|/\\*|\\*)).*\\bDialogWindow\\b") to
                        "use the owner-modal undecorated common Dialog container",
                ),
            ),
            SourcePatternRule(
                name = "server Exposed transactions have an explicit database owner",
                relativeRoots = listOf("server/server/src/main"),
                forbiddenPatterns = listOf(
                    Regex("\\btransaction\\s*\\{") to
                        "pass the Application-owned Database explicitly instead of using Exposed's process default",
                ),
            ),
            SourcePatternRule(
                name = "server Exposed databases retain the blocking-IO guard",
                relativeRoots = listOf("server/server/src/main"),
                forbiddenPatterns = listOf(
                    Regex(
                        "\\bDatabase\\s*\\.\\s*connect\\s*\\(" +
                            "(?!\\s*BlockingIoGuardDataSource\\s*\\()",
                    ) to "wrap every production Exposed DataSource at the connection-acquisition boundary",
                ),
            ),
            SourcePatternRule(
                name = "server Netty EventLoops retain lifecycle blocking-IO protection",
                relativeRoots = listOf("server/server/src/main"),
                forbiddenPatterns = listOf(
                    Regex(
                        "\\bMultiThreadIoEventLoopGroup\\s*\\(" +
                            "(?![^\\n]*\\bBlockingIoGuardThreadFactory\\s*\\()",
                    ) to "construct every TCP/HTTP EventLoop through the lifecycle-protected thread factory",
                ),
            ),
            SourcePatternRule(
                name = "server queues have explicit memory bounds",
                relativeRoots = listOf("server/server/src/main"),
                forbiddenPatterns = listOf(
                    Regex(
                        "^\\s*import\\s+java\\.util\\.concurrent\\." +
                            "(?:LinkedBlockingQueue|LinkedBlockingDeque|LinkedTransferQueue|" +
                            "PriorityBlockingQueue|DelayQueue)\\b",
                    ) to "use a fixed-capacity queue or enforce a separately tested admission bound",
                    Regex(
                        "\\bjava\\.util\\.concurrent\\." +
                            "(?:LinkedBlockingQueue|LinkedBlockingDeque|LinkedTransferQueue|" +
                            "PriorityBlockingQueue|DelayQueue)\\s*[<(]",
                    ) to "use a fixed-capacity queue or enforce a separately tested admission bound",
                ),
            ),
        )

        val attachmentTransferFiles = listOf(
            "client/shared/src/commonMain/kotlin/com/virjar/tk/shared/repository/FileRepository.kt",
            "client/shared/src/jvmMain/kotlin/com/virjar/tk/shared/repository/FileRepository.desktop.kt",
            "client/shared/src/androidMain/kotlin/com/virjar/tk/shared/repository/FileRepository.android.kt",
            "client/shared/src/commonMain/kotlin/com/virjar/tk/shared/bot/ImBot.kt",
            "client/shared/src/jvmMain/kotlin/com/virjar/tk/shared/agent/AgentApi.kt",
            "client/desktop/src/desktopMain/kotlin/com/virjar/tk/desktop/DesktopMediaServices.kt",
            "client/desktop/src/desktopMain/kotlin/com/virjar/tk/desktop/media/DesktopMediaCache.kt",
            "client/desktop/src/desktopMain/kotlin/com/virjar/tk/desktop/DesktopFileDownloadController.kt",
            "client/android/src/main/kotlin/com/virjar/tk/android/MediaHelper.kt",
            "client/android/src/main/kotlin/com/virjar/tk/android/AndroidFileDownloadController.kt",
            "server/server/src/main/kotlin/com/virjar/tk/server/infra/storage/FileStore.kt",
            "server/server/src/main/kotlin/com/virjar/tk/server/infra/storage/FileStoreObjectStorage.kt",
        )

        val productionSourceRoots = listOf(
            "protocol/protocol/src/commonMain",
            "protocol/protocol-netty/src/commonMain",
            "client/shared/src/commonMain",
            "client/shared/src/jvmAndAndroidMain",
            "client/shared/src/androidMain",
            "client/shared/src/jvmMain",
            "client/app/src/commonMain",
            "client/android/src/main",
            "client/desktop/src/desktopMain",
            "server/server/src/main",
        )
        // 受控的 richeditor 源码 fork 仍参与上面的依赖、执行器和传输模式检查。其上游文件
        // 拓扑被有意保留，以便可审计地 rebase（见 richeditor/FORK.md），因此本地的 800 行
        // 阅读热点提示只作用于 TeamTalk 自有的生产源码，不强制 fork 拆分。
        val reviewedKotlinSourceRoots = productionSourceRoots + "client/shared-testkit/src/commonMain"
        val organizationManagedChatAdapter =
            "server/server/src/main/kotlin/com/virjar/tk/server/infra/db/repository/" +
                "ExposedOrganizationManagedChatProjectionStore.kt"
        val groupMemberMutationAdapters = mapOf(
            "server/server/src/main/kotlin/com/virjar/tk/server/infra/db/repository/ExposedChatRepository.kt" to
                listOf(
                    "GroupPolicy.canonicalInitialMemberUids",
                    "GroupPolicy.requireAdditionalCapacity",
                ),
            "server/server/src/main/kotlin/com/virjar/tk/server/infra/db/repository/ExposedChatMemberRepository.kt" to
                listOf(
                    "GroupPolicy.canonicalTargetMemberUids",
                    "GroupPolicy.requireAdditionalCapacity",
                ),
            organizationManagedChatAdapter to listOf("GroupPolicy.requireFinalMemberCount"),
        )
        val contactMutationAdapter =
            "server/server/src/main/kotlin/com/virjar/tk/server/infra/db/repository/ExposedContactRepository.kt"
        val violations = buildList {
            val ignoreRules = root.resolve(".gitignore").takeIf { it.isFile }
                ?.readLines()
                ?.map(String::trim)
                .orEmpty()
            for (generatedDirectory in listOf("/server/admin/dist/", "/server/admin/node_modules/")) {
                if (generatedDirectory !in ignoreRules) {
                    add("Admin producer boundary: .gitignore must retain $generatedDirectory")
                }
            }
            trackedAdminGeneratedFiles.get()
                .forEach { relativePath ->
                    add(
                        "$relativePath: Admin producer boundary forbids tracked dist or node_modules; " +
                            "Server must build Admin from the locked source graph into server/server/build",
                    )
                }
            addAll(configuredDependencyViolations.get())
            if (!root.resolve("client/richeditor/FORK.md").isFile) {
                add("controlled source fork: richeditor/FORK.md is required for provenance and local-change governance")
            }
            groupMemberMutationAdapters.forEach { (relativeFile, requiredMarkers) ->
                val adapter = root.resolve(relativeFile)
                if (!adapter.isFile) {
                    add("group member capacity boundary: missing approved mutation adapter $relativeFile")
                } else {
                    val source = adapter.readText()
                    requiredMarkers.filterNot(source::contains).forEach { marker ->
                        add(
                            "$relativeFile: group member capacity boundary must retain $marker " +
                                "at the persistence adapter",
                        )
                    }
                }
            }
            val contactAdapterFile = root.resolve(contactMutationAdapter)
            if (!contactAdapterFile.isFile) {
                add("contact capacity boundary: missing aggregate adapter $contactMutationAdapter")
            } else {
                val source = contactAdapterFile.readText()
                if (!source.contains("requireRelationshipCapacity")) {
                    add(
                        "$contactMutationAdapter: contact capacity boundary must remain at the " +
                            "persistence adapter",
                    )
                }
            }
            val groupMemberMutationPattern = Regex(
                "\\bGroupMembers\\s*\\.\\s*" +
                    "(?:insert|insertIgnore|batchInsert|update|upsert|replace)\\s*\\(",
            )
            root.resolve("server/server/src/main").walkTopDown()
                .filter { it.isFile && it.extension in PRODUCT_SOURCE_EXTENSIONS }
                .forEach { file ->
                    val relativeFile = file.relativeTo(root).invariantSeparatorsPath
                    if (
                        relativeFile !in groupMemberMutationAdapters &&
                        groupMemberMutationPattern.containsMatchIn(file.readText())
                    ) {
                        add(
                            "$relativeFile: group member capacity boundary allows GroupMembers writes only in " +
                                "the reviewed aggregate adapters",
                        )
                    }
                }
            val contactMutationPattern = Regex(
                "\\bFriends\\s*\\.\\s*" +
                    "(?:insert|insertIgnore|batchInsert|update|upsert|replace)\\s*\\(",
            )
            root.resolve("server/server/src/main").walkTopDown()
                .filter { it.isFile && it.extension in PRODUCT_SOURCE_EXTENSIONS }
                .forEach { file ->
                    val relativeFile = file.relativeTo(root).invariantSeparatorsPath
                    if (
                        relativeFile != contactMutationAdapter &&
                        contactMutationPattern.containsMatchIn(file.readText())
                    ) {
                        add(
                            "$relativeFile: contact capacity boundary allows Friends writes only in " +
                                "the reviewed aggregate adapter",
                        )
                    }
                }
            val serverApplication = root.resolve(
                "server/server/src/main/kotlin/com/virjar/tk/server/Application.kt",
            )
            if (!serverApplication.isFile) {
                add("server HTTP blocking boundary: missing production Application.kt")
            } else {
                val source = serverApplication.readText()
                val ownerPosition = Regex(
                    "resources\\s*\\.\\s*ownDependencyBarrier\\s*\\(\\s*" +
                        "\"HTTP blocking executor\"",
                ).find(source)?.range?.first ?: -1
                val boundaryPosition = Regex(
                    "\\binstallHttpBlockingBoundary\\s*\\(",
                ).find(source)?.range?.first ?: -1
                val routingPosition = Regex("\\brouting\\s*\\{")
                    .find(source)?.range?.first ?: -1
                val protectedEventLoops = Regex(
                    "\\bconfigureProtectedHttpEventLoops\\s*\\(\\s*\\)",
                ).containsMatchIn(source)
                if (
                    ownerPosition < 0 ||
                    boundaryPosition <= ownerPosition ||
                    routingPosition <= boundaryPosition ||
                    !protectedEventLoops
                ) {
                    add(
                        "server HTTP blocking boundary: configure protected Netty EventLoops, own the bounded " +
                            "executor, install the global Call boundary, then register routes in that order",
                    )
                }
            }

            for (rule in rules) {
                val ruleSourceRoot = root.resolve(rule.relativeRoot)
                if (!ruleSourceRoot.isDirectory) {
                    add("${rule.name}: missing source root ${rule.relativeRoot}")
                    continue
                }
                ruleSourceRoot.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { file ->
                        file.useLines { lines ->
                            lines.forEachIndexed { index, line ->
                                val imported = line.trim().removePrefix("import ")
                                if (imported == line.trim()) return@forEachIndexed
                                val forbidden = rule.forbiddenImports.firstOrNull(imported::startsWith)
                                if (forbidden != null) {
                                    add(
                                        "${file.relativeTo(root).path}:${index + 1}: " +
                                            "${rule.name} forbids import $imported",
                                    )
                                }
                            }
                        }
                    }
            }

            for (rule in sourcePatternRules) {
                for (ruleRelativeRoot in rule.relativeRoots) {
                    val patternSourceRoot = root.resolve(ruleRelativeRoot)
                    if (!patternSourceRoot.isDirectory) {
                        add("${rule.name}: missing source root $ruleRelativeRoot")
                        continue
                    }
                    patternSourceRoot.walkTopDown()
                        .filter { it.isFile && it.extension in PRODUCT_SOURCE_EXTENSIONS }
                        .forEach { file ->
                            file.useLines { lines ->
                                lines.forEachIndexed { index, line ->
                                    rule.forbiddenPatterns.forEach { (pattern, reason) ->
                                        if (pattern.containsMatchIn(line)) {
                                            add(
                                                "${file.relativeTo(root).path}:${index + 1}: " +
                                                    "${rule.name}: $reason",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                }
            }

            val bcryptAdapter =
                "server/server/src/main/kotlin/com/virjar/tk/server/infra/security/BCryptPasswordHasher.kt"
            val bcryptAdapterFile = root.resolve(bcryptAdapter)
            if (!bcryptAdapterFile.isFile) {
                add("password hashing boundary: missing production adapter $bcryptAdapter")
            }
            root.resolve("server/server/src/main").walkTopDown()
                .filter { it.isFile && it.extension in PRODUCT_SOURCE_EXTENSIONS }
                .forEach { file ->
                    val relativeFile = file.relativeTo(root).invariantSeparatorsPath
                    if (relativeFile == bcryptAdapter) return@forEach
                    file.useLines { lines ->
                        lines.forEachIndexed { index, line ->
                            if (
                                Regex("^\\s*import\\s+org\\.mindrot\\.jbcrypt\\.").containsMatchIn(line) ||
                                Regex("\\bBCrypt\\s*\\.").containsMatchIn(line)
                            ) {
                                add(
                                    "$relativeFile:${index + 1}: password hashing boundary requires " +
                                        "the application-owned infra/security BCrypt adapter",
                                )
                            }
                        }
                    }
                }

            for (relativeFile in attachmentTransferFiles) {
                val transferFile = root.resolve(relativeFile)
                if (!transferFile.isFile) {
                    add("attachment streaming: missing production source $relativeFile")
                    continue
                }
                transferFile.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (Regex("\\.readBytes\\s*\\(").containsMatchIn(line)) {
                            add(
                                "$relativeFile:${index + 1}: attachment streaming forbids whole-file readBytes()",
                            )
                        }
                    }
                }
            }

            for (boundedRelativeRoot in reviewedKotlinSourceRoots) {
                val boundedSourceRoot = root.resolve(boundedRelativeRoot)
                if (!boundedSourceRoot.isDirectory) {
                    add("maintained source size: missing source root $boundedRelativeRoot")
                    continue
                }
                boundedSourceRoot.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { file ->
                        val relativePath = file.relativeTo(root).invariantSeparatorsPath
                        val lineCount = file.useLines { lines -> lines.count() }
                        if (lineCount > KOTLIN_SIZE_REVIEW_THRESHOLD) {
                            // 长度用于定位阅读热点；多一行注释不构成架构违规，也不应迫使机械拆类。
                            logger.warn(
                                "$relativePath: maintained Kotlin file has $lineCount lines; " +
                                    "review ownership and reading flow before splitting responsibilities",
                            )
                        }
                    }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException("Architecture boundary violations:\n${violations.joinToString("\n")}")
        }
    }

    private data class Rule(
        val name: String,
        val relativeRoot: String,
        val forbiddenImports: List<String>,
    )

    private data class SourcePatternRule(
        val name: String,
        val relativeRoots: List<String>,
        val forbiddenPatterns: List<Pair<Regex, String>>,
    )

    private companion object {
        const val KOTLIN_SIZE_REVIEW_THRESHOLD = 800
        val PRODUCT_SOURCE_EXTENSIONS = setOf("kt", "java")
    }
}
