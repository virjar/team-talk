package release

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopPackageChecksTest {
    @Test
    fun `font cleanup resolves each jpackage layout inside the named application`() {
        val destination = File("build/compose/binaries/main-release/app")
        mapOf(
            "macos" to "TeamTalk Private.app/Contents/runtime/Contents/Home/lib/fonts",
            "windows" to "TeamTalk Private/runtime/lib/fonts",
            "linux" to "teamtalk-private/lib/runtime/lib/fonts",
        ).forEach { (platform, relativePath) ->
            val name = if (platform == "linux") "teamtalk-private" else "TeamTalk Private"
            assertEquals(destination.resolve(relativePath), desktopRuntimeFontsDirectory(destination, name, platform))
        }
    }

    @Test
    fun `actual classfile checks reject JNI methods removed without JVM callers on any platform`() = withFixture { root ->
        val original = bridgeJar(root.resolve("original.jar"))
        verifyDesktopMediaNativeBridges(listOf(original), listOf(original))
        listOf("mac.MacNativeBridge", "windows.WindowsNativeBridge", "linux.LinuxNativeBridge").forEach { bridge ->
            val shrunk = bridgeJar(root.resolve("shrunk.jar"), removedFrom = bridge)
            val failure = assertFailsWith<IllegalStateException> {
                verifyDesktopMediaNativeBridges(listOf(original), listOf(shrunk))
            }
            assertTrue(failure.message.orEmpty().contains("nGetPlaybackSpeed"))
            assertTrue(failure.message.orEmpty().contains(bridge.substringAfter('.')))
        }
    }

    @Test
    fun `native signature and static dispatch must survive packaging`() = withFixture { root ->
        val original = bridgeJar(root.resolve("original.jar"))
        val changed = bridgeJar(root.resolve("changed.jar"), staticMethods = false)
        assertFailsWith<IllegalStateException> {
            verifyDesktopMediaNativeBridges(listOf(original), listOf(changed))
        }
        val changedSignature = bridgeJar(root.resolve("changed-signature.jar"), descriptor = "(J)D")
        assertFailsWith<IllegalStateException> {
            verifyDesktopMediaNativeBridges(listOf(original), listOf(changedSignature))
        }
        assertFailsWith<IllegalStateException> {
            verifyDesktopMediaNativeBridges(listOf(original), emptyList())
        }
    }

    @Test
    fun `SID query retains the reflected field annotation and constructor after shrinking`() = withFixture { root ->
        verifyDesktopWindowsIdentityBindings(listOf(psidJar(root.resolve("complete.jar"))))
        listOf("field", "annotation", "constructor").forEach { missing ->
            val jar = psidJar(root.resolve("missing-$missing.jar"), missing)
            assertFailsWith<IllegalStateException> { verifyDesktopWindowsIdentityBindings(listOf(jar)) }
        }
    }

    private fun psidJar(file: File, missing: String? = null): File {
        val name = "com/sun/jna/platform/win32/WinNT\$PSID"
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "com/sun/jna/Structure", null)
        if (missing != "annotation") {
            val annotation = writer.visitAnnotation("Lcom/sun/jna/Structure\$FieldOrder;", true)
            annotation.visitArray("value").apply { visit(null, "sid"); visitEnd() }
            annotation.visitEnd()
        }
        if (missing != "field") {
            writer.visitField(Opcodes.ACC_PUBLIC, "sid", "Lcom/sun/jna/Pointer;", null, null).visitEnd()
        }
        if (missing != "constructor") {
            writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "com/sun/jna/Structure", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }
        writer.visitEnd()
        JarOutputStream(file.outputStream()).use { jar ->
            jar.putNextEntry(ZipEntry("$name.class"))
            jar.write(writer.toByteArray())
            jar.closeEntry()
        }
        return file
    }

    private fun bridgeJar(
        file: File,
        removedFrom: String? = null,
        staticMethods: Boolean = true,
        descriptor: String = "(J)F",
    ): File {
        JarOutputStream(file.outputStream()).use { jar ->
            listOf("mac.MacNativeBridge", "windows.WindowsNativeBridge", "linux.LinuxNativeBridge").forEach { bridge ->
                val name = "io/github/kdroidfilter/composemediaplayer/${bridge.replace('.', '/')}"
                val writer = ClassWriter(0)
                writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
                if (bridge != removedFrom) {
                    val access = Opcodes.ACC_PUBLIC or Opcodes.ACC_NATIVE or (if (staticMethods) Opcodes.ACC_STATIC else 0)
                    writer.visitMethod(access, "nGetPlaybackSpeed", descriptor, null, null).visitEnd()
                }
                writer.visitEnd()
                jar.putNextEntry(ZipEntry("$name.class"))
                jar.write(writer.toByteArray())
                jar.closeEntry()
            }
        }
        return file
    }

    private fun withFixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("teamtalk-package-check-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
