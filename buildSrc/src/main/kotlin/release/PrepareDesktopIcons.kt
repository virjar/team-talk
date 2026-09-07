package release

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.File
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * Conveyor consumes this subset, while compilation and ordinary development use the original API.
 * Only generated Material icon classes are removed. All retained bytes and all other dependencies
 * stay untouched: this is deliberately not a second whole-application shrinker or native filter.
 */
abstract class PrepareDesktopIcons : DefaultTask() {
    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:InputFile
    abstract val originalConfig: RegularFileProperty

    @get:OutputFile
    abstract val destinationConfig: RegularFileProperty

    @get:OutputDirectory
    abstract val subsetDirectory: DirectoryProperty

    init {
        // Platform inputs are resolved by Conveyor's plugin at execution time. Rescan this small
        // subset on every package build instead of treating host-only classpath snapshots as proof
        // that every target is unchanged. Deterministic bytes still reuse Conveyor's content cache.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun prepare() {
        val sourceConfig = originalConfig.get().asFile.readText()
        val inputs = runtimeClasspath.files + conveyorJarInputs(sourceConfig)
        val original = inputs.singleOrNull { it.name.startsWith("material-icons-extended-desktop-") }
            ?: error("Expected one material-icons-extended-desktop JAR; review icon packaging after dependency changes")
        val subset = subsetDirectory.get().file(original.name).asFile
        val result = createMaterialIconSubset(original, inputs - original, subset)
        val rewritten = replaceConveyorIconInput(sourceConfig, original.path, subset.path)
        destinationConfig.get().asFile.apply {
            parentFile.mkdirs()
            writeText(rewritten)
        }
        subset.parentFile.resolve("retained-classes.txt").writeText(result.classes.joinToString("\n", postfix = "\n"))
        subset.parentFile.resolve("size-report.txt").writeText(
            "originalBytes=${original.length()}\nsubsetBytes=${subset.length()}\n" +
                "originalClasses=${result.originalClassCount}\nretainedClasses=${result.classes.size}\n",
        )
        logger.lifecycle("Material icons: {} -> {} classes, {} -> {} bytes", result.originalClassCount,
            result.classes.size, original.length(), subset.length())
    }
}

internal fun conveyorJarInputs(config: String): List<File> {
    val paths = Regex("(?m)^(app(?:\\.[\\w-]+)*\\.inputs)\\s*[+=]")
        .findAll(config).map { it.groupValues[1] }.toSet()
    val fallback = ConfigFactory.parseMap(paths.associateWith { emptyList<String>() } + ("app.jvm.options" to emptyList<String>()))
    val resolved = ConfigFactory.parseString(config).withFallback(fallback).resolve()
    return paths.flatMap { path -> resolved.getStringList(path) }
        .map { File(it.substringBefore(" -> ")) }.filter { it.extension == "jar" }.distinct()
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

/** Change exactly one dependency input line, retaining platform sections and plugin rename arrows. */
internal fun replaceConveyorIconInput(config: String, originalPath: String, subsetPath: String): String {
    var replacements = 0
    val rewritten = config.lineSequence().joinToString("\n") { line ->
        // Conveyor Gradle 2.0 emits dependency list entries with four leading spaces. Parse the
        // scalar using HOCON so spaces and Windows backslashes are not mistaken for shell syntax.
        if (!line.startsWith("    ")) return@joinToString line
        val input = line.trim().substringBefore(" -> ")
        val path = runCatching { ConfigFactory.parseString("input=$input").getString("input") }.getOrNull()
        if (path != originalPath) return@joinToString line
        replacements++
        "    " + ConfigValueFactory.fromAnyRef(subsetPath).render() + line.trim().removePrefix(input)
    }
    check(replacements == 1) { "Expected exactly one Conveyor icon input, found $replacements; review generated configuration" }
    return rewritten
}
