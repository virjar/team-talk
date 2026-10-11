import java.io.File

internal fun isArchitectureMainSourceSet(name: String): Boolean = name == "main" || name.endsWith("Main")

/** 与当前宿主启用的编译目标无关，独立检查已入库源码的归属。 */
internal fun architectureMainSourceRoots(root: File, modules: List<String>): List<String> = modules.flatMap { module ->
    val sourceDirectory = root.resolve("$module/src")
    check(sourceDirectory.isDirectory) { "Architecture source inventory is missing $module/src" }
    sourceDirectory.listFiles().orEmpty()
        .filter { it.isDirectory && isArchitectureMainSourceSet(it.name) }
        .sortedBy { it.name }
        .map { it.relativeTo(root).invariantSeparatorsPath }
}
