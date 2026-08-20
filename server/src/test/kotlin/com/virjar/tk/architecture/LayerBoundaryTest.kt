package com.virjar.tk.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class LayerBoundaryTest {
    @Test
    fun `domain does not depend on infrastructure or transport adapters`() {
        val projectRoot = projectRoot()
        val domainRoot = File(projectRoot, "server/src/main/kotlin/com/virjar/tk/domain")
        val forbidden = listOf("import com.virjar.tk.infra.", "import com.virjar.tk.protocol.rpc.", "import com.virjar.tk.rpc.gen.")
        val violations = domainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    line.takeIf { candidate -> forbidden.any(candidate::startsWith) }
                        ?.let { "${file.relativeTo(projectRoot)}:${index + 1}: $it" }
                }.asSequence()
            }
            .toList()

        assertTrue(violations.isEmpty(), violations.joinToString("\n", prefix = "Layer violations:\n"))
    }

    @Test
    fun `bot domain depends on narrow collaborators instead of sibling services`() {
        val projectRoot = projectRoot()
        val botRoot = File(projectRoot, "server/src/main/kotlin/com/virjar/tk/domain/bot")
        val forbidden = listOf(
            "import com.virjar.tk.domain.chat.ChatService",
            "import com.virjar.tk.domain.chat.ChatStore",
            "import com.virjar.tk.domain.message.MessageService",
            "import com.virjar.tk.domain.user.UserService",
        )
        val violations = botRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    line.takeIf { candidate -> forbidden.any(candidate::startsWith) }
                        ?.let { "${file.relativeTo(projectRoot)}:${index + 1}: $it" }
                }.asSequence()
            }
            .toList()

        assertTrue(violations.isEmpty(), violations.joinToString("\n", prefix = "Bot boundary violations:\n"))
    }

    private fun projectRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "server/src/main/kotlin/com/virjar/tk/domain").isDirectory }
}
