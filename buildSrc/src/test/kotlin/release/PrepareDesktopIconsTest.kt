package release

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PrepareDesktopIconsTest {
    private val prefix = "androidx/compose/material/icons/"

    @Test
    fun `retains filled outlined transitive and literal reflection references without modifying bytes`() {
        val directory = Files.createTempDirectory("desktop-icons").toFile()
        try {
            val filled = prefix + "filled/ChatKt"
            val outlined = prefix + "outlined/ChatKt"
            val delegated = prefix + "automirrored/outlined/ChatKt"
            val reflected = prefix + "filled/InfoKt"
            val unused = prefix + "filled/UnusedKt"
            val original = directory.resolve("icons.jar")
            val classes = mapOf(
                filled to bytecode(filled), outlined to bytecode(outlined, delegated),
                delegated to bytecode(delegated), reflected to bytecode(reflected), unused to bytecode(unused),
            )
            writeJar(original, classes.mapKeys { "${it.key}.class" } + ("META-INF/LICENSE" to "original notice".toByteArray()))
            val consumer = directory.resolve("app.jar")
            writeJar(consumer, mapOf("App.class" to bytecode("App", filled, outlined, literal = reflected.replace('/', '.'))))
            val output = directory.resolve("subset.jar")

            val result = createMaterialIconSubset(original, setOf(consumer), output)

            assertEquals(setOf(filled, outlined, delegated, reflected), result.classes)
            assertEquals(5, result.originalClassCount)
            JarFile(output).use { jar ->
                result.classes.forEach { name ->
                    assertContentEquals(classes.getValue(name), jar.getInputStream(jar.getJarEntry("$name.class")).readBytes())
                }
                assertEquals(null, jar.getJarEntry("$unused.class"))
                assertEquals("original notice", jar.getInputStream(jar.getJarEntry("META-INF/LICENSE")).reader().readText())
            }
            val again = directory.resolve("again.jar")
            createMaterialIconSubset(original, setOf(consumer), again)
            assertContentEquals(output.readBytes(), again.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `changes only the anchored input retaining windows quoting and platform graph`() {
        val original = "C:\\user workspace\\icons.jar"
        val subset = "C:\\user workspace\\build\\icons.jar"
        val config = """
            // C:\user workspace\icons.jar is not an input here.
            app.inputs = ${'$'}{app.inputs} [
                "C:\\user workspace\\icons.jar" -> icons-2.jar
                "C:\\user workspace\\app.jar"
            ]
            app.mac.aarch64.inputs = ${'$'}{app.mac.aarch64.inputs} [
                /cache/skiko-arm64.jar
            ]
        """.trimIndent()
        val rewritten = replaceConveyorIconInput(config, original, subset)
        assertTrue(rewritten.contains("    \"C:\\\\user workspace\\\\build\\\\icons.jar\" -> icons-2.jar"))
        assertEquals(config.lines().filterIndexed { i, _ -> i != 2 }, rewritten.lines().filterIndexed { i, _ -> i != 2 })
        assertFailsWith<IllegalStateException> { replaceConveyorIconInput(config, "/missing.jar", subset) }
        assertFailsWith<IllegalStateException> { replaceConveyorIconInput(config + "\n" + config.lines()[2], original, subset) }
        assertEquals(listOf(original, "C:\\user workspace\\app.jar", "/cache/skiko-arm64.jar"),
            conveyorJarInputs(config).map { it.path })
    }

    private fun bytecode(name: String, vararg references: String, literal: String? = null): ByteArray =
        ClassWriter(0).apply {
            visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "get", "()V", null, null).apply {
                visitCode()
                references.forEach { visitMethodInsn(Opcodes.INVOKESTATIC, it, "get", "()V", false) }
                literal?.let { visitLdcInsn(it); visitInsn(Opcodes.POP) }
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 0)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()

    private fun writeJar(file: java.io.File, entries: Map<String, ByteArray>) {
        JarOutputStream(file.outputStream()).use { jar ->
            entries.forEach { (name, bytes) ->
                jar.putNextEntry(ZipEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
    }
}
