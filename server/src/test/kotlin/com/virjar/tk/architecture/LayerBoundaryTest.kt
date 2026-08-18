package com.virjar.tk.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class LayerBoundaryTest {
    @Test
    fun `domain does not depend on infrastructure or transport adapters`() {
        val projectRoot = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "server/src/main/kotlin/com/virjar/tk/domain").isDirectory }
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
}
