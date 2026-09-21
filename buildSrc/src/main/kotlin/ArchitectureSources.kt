import java.io.File

internal fun isArchitectureMainSourceSet(name: String): Boolean = name == "main" || name.endsWith("Main")

/** Inspect checked-in source ownership independently of the targets enabled on the current host. */
internal fun architectureMainSourceRoots(root: File, modules: List<String>): List<String> = modules.flatMap { module ->
    val sourceDirectory = root.resolve("$module/src")
    check(sourceDirectory.isDirectory) { "Architecture source inventory is missing $module/src" }
    sourceDirectory.listFiles().orEmpty()
        .filter { it.isDirectory && isArchitectureMainSourceSet(it.name) }
        .sortedBy { it.name }
        .map { it.relativeTo(root).invariantSeparatorsPath }
}
