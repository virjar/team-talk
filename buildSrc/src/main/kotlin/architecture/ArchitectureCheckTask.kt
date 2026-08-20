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

        val violations = buildList {
            for (rule in rules) {
                val sourceRoot = root.resolve(rule.relativeRoot)
                if (!sourceRoot.isDirectory) {
                    add("${rule.name}: missing source root ${rule.relativeRoot}")
                    continue
                }
                sourceRoot.walkTopDown()
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
}
