package architecture

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/** Makes the source dependency rules in AGENTS.md executable instead of relying on review memory. */
abstract class ArchitectureCheckTask : DefaultTask() {
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @TaskAction
    fun checkBoundaries() {
        val root = repositoryRoot.get().asFile
        val rules = listOf(
            Rule(
                name = "protocol contract purity",
                relativeRoot = "protocol/src/commonMain",
                forbiddenImports = listOf(
                    "com.virjar.tk.client.",
                    "com.virjar.tk.repository.",
                    "com.virjar.tk.navigation.",
                    "com.virjar.tk.ui.",
                    "com.virjar.tk.domain.",
                    "com.virjar.tk.infra.",
                    "com.virjar.tk.api.",
                ),
            ),
            Rule(
                name = "shared SDK has no UI or server dependency",
                relativeRoot = "shared/src/commonMain",
                forbiddenImports = listOf(
                    "androidx.compose.",
                    "org.jetbrains.compose.",
                    "com.virjar.tk.ui.",
                    "com.virjar.tk.navigation.",
                    "com.virjar.tk.domain.",
                    "com.virjar.tk.infra.",
                    "com.virjar.tk.api.",
                ),
            ),
            Rule(
                name = "shared UI has no server or transport implementation dependency",
                relativeRoot = "app/src/commonMain",
                forbiddenImports = listOf(
                    "com.virjar.tk.domain.",
                    "com.virjar.tk.infra.",
                    "com.virjar.tk.api.",
                    "org.jetbrains.exposed.",
                    "io.ktor.server.",
                    "io.netty.",
                ),
            ),
            Rule(
                name = "server domain has no outer adapter dependency",
                relativeRoot = "server/src/main/kotlin/com/virjar/tk/domain",
                forbiddenImports = listOf(
                    "com.virjar.tk.infra.",
                    "com.virjar.tk.api.",
                    "com.virjar.tk.protocol.codec.",
                    "com.virjar.tk.protocol.executor.",
                    "com.virjar.tk.protocol.rpc.",
                    "com.virjar.tk.rpc.gen.",
                    "org.jetbrains.exposed.",
                    "io.ktor.",
                    "io.netty.",
                ),
            ),
            Rule(
                name = "bot domain depends on ports instead of sibling services",
                relativeRoot = "server/src/main/kotlin/com/virjar/tk/domain/bot",
                forbiddenImports = listOf(
                    "com.virjar.tk.domain.chat.ChatService",
                    "com.virjar.tk.domain.chat.ChatStore",
                    "com.virjar.tk.domain.message.MessageService",
                    "com.virjar.tk.domain.user.UserService",
                ),
            ),
            Rule(
                name = "server production does not depend on the client SDK or UI",
                relativeRoot = "server/src/main",
                forbiddenImports = listOf(
                    "com.virjar.tk.client.",
                    "com.virjar.tk.repository.",
                    "com.virjar.tk.navigation.",
                    "com.virjar.tk.ui.",
                ),
            ),
        )

        val sourcePatternRules = listOf(
            SourcePatternRule(
                name = "client credentials have no process-global session singleton",
                relativeRoots = listOf(
                    "shared/src/commonMain",
                    "app/src/commonMain",
                    "android/src/main",
                    "desktop/src/desktopMain",
                ),
                forbiddenPatterns = listOf(
                    Regex("\\bSessionContext\\b") to
                        "bearer credentials must be provided by an authenticated session owner",
                ),
            ),
            SourcePatternRule(
                name = "desktop authenticated work has structured ownership",
                relativeRoots = listOf("desktop/src/desktopMain"),
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
        )

        val attachmentTransferFiles = listOf(
            "shared/src/commonMain/kotlin/com/virjar/tk/repository/FileRepository.kt",
            "shared/src/jvmMain/kotlin/com/virjar/tk/repository/FileRepository.desktop.kt",
            "shared/src/androidMain/kotlin/com/virjar/tk/repository/FileRepository.android.kt",
            "shared/src/commonMain/kotlin/com/virjar/tk/bot/ImBot.kt",
            "shared/src/jvmMain/kotlin/com/virjar/tk/agent/AgentApi.kt",
            "desktop/src/desktopMain/kotlin/com/virjar/tk/DesktopMediaServices.kt",
            "desktop/src/desktopMain/kotlin/com/virjar/tk/media/DesktopMediaCache.kt",
            "desktop/src/desktopMain/kotlin/com/virjar/tk/DesktopFileDownloadController.kt",
            "android/src/main/kotlin/com/virjar/tk/MediaHelper.kt",
            "android/src/main/kotlin/com/virjar/tk/AndroidFileDownloadController.kt",
        )

        val productionSourceRoots = listOf(
            "protocol/src/commonMain",
            "shared/src/commonMain",
            "shared/src/androidMain",
            "shared/src/jvmMain",
            "app/src/commonMain",
            "android/src/main",
            "desktop/src/desktopMain",
            "server/src/main",
        )
        val temporaryOversizedFiles = setOf(
            "app/src/commonMain/kotlin/com/virjar/tk/navigation/feature/DocumentWorkspaceFeature.kt",
            "app/src/commonMain/kotlin/com/virjar/tk/ui/component/rich/DocumentBlockEditor.kt",
            "app/src/commonMain/kotlin/com/virjar/tk/ui/component/rich/MarkdownText.kt",
            "app/src/commonMain/kotlin/com/virjar/tk/ui/screen/ChatScreen.kt",
            "desktop/src/desktopMain/kotlin/com/virjar/tk/MainAppContent.kt",
            "desktop/src/desktopMain/kotlin/com/virjar/tk/test/TestHttpServer.kt",
            "shared/src/commonMain/kotlin/com/virjar/tk/client/LocalCacheImpl.kt",
        )

        val violations = buildList {
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
                        .filter { it.isFile && it.extension == "kt" }
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

            for (productionRelativeRoot in productionSourceRoots) {
                val productionSourceRoot = root.resolve(productionRelativeRoot)
                if (!productionSourceRoot.isDirectory) {
                    add("production source size: missing source root $productionRelativeRoot")
                    continue
                }
                productionSourceRoot.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { file ->
                        val relativePath = file.relativeTo(root).invariantSeparatorsPath
                        val lineCount = file.useLines { lines -> lines.count() }
                        if (lineCount > MAX_PRODUCTION_KOTLIN_LINES && relativePath !in temporaryOversizedFiles) {
                            add(
                                "$relativePath: production Kotlin file has $lineCount lines; " +
                                    "split real responsibilities before exceeding $MAX_PRODUCTION_KOTLIN_LINES",
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
        const val MAX_PRODUCTION_KOTLIN_LINES = 800
    }
}
