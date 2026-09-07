package release

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarFile

/** jpackage's app-image layouts; the destination contains the named application on every OS. */
fun desktopRuntimeFontsDirectory(destination: File, packageName: String, platform: String): File =
    destination.resolve(
        when (platform) {
            "macos" -> "$packageName.app/Contents/runtime/Contents/Home/lib/fonts"
            "windows" -> "$packageName/runtime/lib/fonts"
            "linux" -> "$packageName/lib/runtime/lib/fonts"
            else -> error("Unsupported Desktop packaging platform: $platform")
        },
    )

private val mediaNativeBridges = listOf("mac.MacNativeBridge", "windows.WindowsNativeBridge", "linux.LinuxNativeBridge")
    .map { "io/github/kdroidfilter/composemediaplayer/${it.replace('.', '/')}" }

/**
 * Native code can resolve these methods by name even when no JVM caller survives shrinking.
 * Compare the actual dependency and ProGuard output, without loading a platform's JNI library.
 */
fun verifyDesktopMediaNativeBridges(originalClasspath: Collection<File>, packagedClasspath: Collection<File>) {
    val original = mediaNativeMethods(originalClasspath)
    val packaged = mediaNativeMethods(packagedClasspath)
    mediaNativeBridges.forEach { bridge ->
        val expected = original[bridge]
        check(!expected.isNullOrEmpty()) { "Missing source native bridge: $bridge; review ComposeMediaPlayer packaging" }
        val missing = expected - packaged[bridge].orEmpty()
        check(missing.isEmpty()) {
            "Desktop packaging removed or changed JNI methods in $bridge: ${missing.joinToString()}. " +
                "Keep the complete native method group in desktop-proguard.pro."
        }
    }
}

/** Check JNA's reflected SID layout without loading Windows DLLs on the build host. */
fun verifyDesktopWindowsIdentityBindings(packagedClasspath: Collection<File>) {
    val psidEntry = "com/sun/jna/platform/win32/WinNT\$PSID.class"
    var classBytes: ByteArray? = null
    packagedClasspath.filter { it.isFile && it.extension == "jar" }.forEach { file ->
        JarFile(file).use { jar ->
            jar.getJarEntry(psidEntry)?.let { entry ->
                check(classBytes == null) { "Duplicate JNA PSID in Desktop package" }
                classBytes = jar.getInputStream(entry).use { it.readBytes() }
            }
        }
    }
    var publicPointerField = false
    var publicDefaultConstructor = false
    val fieldOrder = mutableListOf<String>()
    ClassReader(checkNotNull(classBytes) { "Desktop package is missing JNA PSID" }).accept(
        object : ClassVisitor(Opcodes.ASM9) {
            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                if (name == "sid" && descriptor == "Lcom/sun/jna/Pointer;" &&
                    access and Opcodes.ACC_PUBLIC != 0 && access and Opcodes.ACC_STATIC == 0
                ) publicPointerField = true
                return null
            }

            override fun visitMethod(
                access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor? {
                if (name == "<init>" && descriptor == "()V" && access and Opcodes.ACC_PUBLIC != 0) {
                    publicDefaultConstructor = true
                }
                return null
            }

            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                if (descriptor != "Lcom/sun/jna/Structure\$FieldOrder;" || !visible) return null
                return object : AnnotationVisitor(Opcodes.ASM9) {
                    override fun visitArray(name: String): AnnotationVisitor? =
                        if (name != "value") null else object : AnnotationVisitor(Opcodes.ASM9) {
                            override fun visit(name: String?, value: Any?) {
                                if (value is String) fieldOrder += value
                            }
                        }
                }
            }
        },
        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
    )
    check(publicPointerField && publicDefaultConstructor && fieldOrder == listOf("sid")) {
        "Desktop packaging changed JNA PSID's reflected sid field, constructor or FieldOrder annotation; " +
            "review the JNA rules in desktop-proguard.pro."
    }
}

private fun mediaNativeMethods(classpath: Collection<File>): Map<String, Set<String>> {
    val methods = mutableMapOf<String, Set<String>>()
    classpath.filter { it.isFile && it.extension == "jar" }.forEach { file ->
        JarFile(file).use { jar ->
            mediaNativeBridges.forEach bridgeLoop@ { bridge ->
                val entry = jar.getJarEntry("$bridge.class") ?: return@bridgeLoop
                val signatures = mutableSetOf<String>()
                jar.getInputStream(entry).use { input ->
                    ClassReader(input).accept(object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor? {
                            if (access and Opcodes.ACC_NATIVE != 0) {
                                val kind = if (access and Opcodes.ACC_STATIC != 0) "static" else "instance"
                                signatures += "$kind $name$descriptor"
                            }
                            return null
                        }
                    }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                }
                check(methods.put(bridge, signatures) == null) { "Duplicate Desktop native bridge: $bridge" }
            }
        }
    }
    return methods
}
