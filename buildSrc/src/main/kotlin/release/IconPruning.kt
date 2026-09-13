package release

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

/**
 * Material icons 裁剪（原 Conveyor 链的 PrepareDesktopIcons 保留核心）：
 * 扫描负载全部 jar 的图标引用，把 material-icons-extended 重打包为仅含被引用类
 * 的子集 jar；负载组装用它替换原始胖 jar（~40MB → 引用闭包）。
 * 只删生成的图标类，其他字节原样复制；刻意不做整应用压缩或 native 过滤。
 */
abstract class PruneDesktopMaterialIconsTask : DefaultTask() {
    @get:Classpath
    abstract val payloadJars: ConfigurableFileCollection

    /** 只允许包含子集 jar 本身；报告文件写到 sibling 目录避免混入负载。 */
    @get:OutputDirectory
    abstract val subsetDirectory: DirectoryProperty

    init {
        // 平台输入在执行期才解析；每次重扫保持与 Conveyor 时代相同的保守语义。
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun prune() {
        val inputs = payloadJars.files
        val original = inputs.singleOrNull { it.name.startsWith("material-icons-extended-desktop-") }
            ?: error("Expected one material-icons-extended-desktop JAR; review icon packaging after dependency changes")
        val subset = subsetDirectory.get().file("material-icons-extended-desktop.jar").asFile
        val result = createMaterialIconSubset(original, inputs - original, subset)
        val reportDir = subset.parentFile.parentFile.resolve("icons-report").apply { mkdirs() }
        reportDir.resolve("retained-classes.txt").writeText(result.classes.joinToString("\n", postfix = "\n"))
        reportDir.resolve("size-report.txt").writeText(
            "originalBytes=${original.length()}\nsubsetBytes=${subset.length()}\n" +
                "originalClasses=${result.originalClassCount}\nretainedClasses=${result.classes.size}\n",
        )
        logger.lifecycle(
            "Material icons: {} -> {} classes, {} -> {} bytes",
            result.originalClassCount, result.classes.size, original.length(), subset.length(),
        )
    }
}

internal data class IconSubsetResult(val originalClassCount: Int, val classes: Set<String>)

internal fun createMaterialIconSubset(original: File, consumers: Set<File>, destination: File): IconSubsetResult {
    JarFile(original).use { icons ->
        val entries = icons.entries().asSequence().filterNot { it.isDirectory }.toList()
        val classes = entries.filter { it.name.endsWith(".class") }.associateBy { it.name.removeSuffix(".class") }
        require(classes.isNotEmpty() && classes.keys.all { it.startsWith("androidx/compose/material/icons/") }) {
            "Material icon JAR contains unexpected classes; review the subset policy before packaging"
        }
        require(entries.none { it.name.uppercase().matches(Regex("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) }) {
            "Signed Material icon JAR cannot be repacked without an explicit signature policy"
        }

        val retained = sortedSetOf<String>()
        fun scan(bytes: ByteArray) {
            val references = object : Remapper() {
                override fun map(internalName: String): String {
                    if (internalName in classes) retained += internalName
                    return internalName
                }

                override fun mapValue(value: Any?): Any? {
                    // Preserve literal Class.forName references as well. Dynamically assembled icon
                    // class names are not an API of this library; use ordinary Icons.* properties.
                    if (value is String) map(value.replace('.', '/'))
                    return super.mapValue(value)
                }
            }
            // ClassWriter supplies visitors for method bodies, annotations, descriptors and handles.
            // Its output is never used: original class bytes are copied unchanged below.
            ClassReader(bytes).accept(ClassRemapper(ClassWriter(0), references), ClassReader.SKIP_DEBUG)
        }

        consumers.sortedBy { it.path }.forEach { input ->
            when {
                input.isDirectory -> input.walkTopDown().filter { it.isFile && it.extension == "class" }
                    .forEach { scan(it.readBytes()) }
                input.extension == "jar" -> JarFile(input).use { jar ->
                    jar.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                        jar.getInputStream(entry).use { scan(it.readBytes()) }
                    }
                }
                else -> error("Unexpected Desktop classpath entry: ${input.name}")
            }
        }
        require(retained.isNotEmpty()) { "No Material icon references found; refusing to package an empty icon subset" }

        val scanned = mutableSetOf<String>()
        while (true) {
            val pending = retained - scanned
            if (pending.isEmpty()) break
            pending.forEach { name ->
                icons.getInputStream(classes.getValue(name)).use { scan(it.readBytes()) }
                scanned += name
            }
        }

        destination.parentFile.mkdirs()
        JarOutputStream(destination.outputStream().buffered()).use { output ->
            entries.sortedBy { it.name }.filter { !it.name.endsWith(".class") || it.name.removeSuffix(".class") in retained }
                .forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                    icons.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
        }
        return IconSubsetResult(classes.size, retained)
    }
}
