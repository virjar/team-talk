package profiles

import java.io.File

/**
 * 从 `gradle/profiles/` 目录加载所有 JSON Profile 文件。
 *
 * 内置 Profile（dev/demo/production）入版本控制。
 * 外部 Profile（如私有化部署的 mycompany.json）通过以下方式注入：
 * - 本地构建：直接放入 `gradle/profiles/` 目录
 * - GitHub Actions：通过 `profile_json` input 注入，构建前写入 `gradle/profiles/`
 *
 * 外部 Profile 不存在于代码仓库，不需要 .gitignore。
 */
fun loadAllProfiles(profileDir: File): List<BuildProfile> {
    if (!profileDir.isDirectory) {
        throw IllegalStateException("Profile directory not found: ${profileDir.absolutePath}")
    }
    val jsonFiles = profileDir.listFiles { f -> f.name.endsWith(".json") }
        ?: emptyArray()
    if (jsonFiles.isEmpty()) {
        throw IllegalStateException("No .json profile files found in: ${profileDir.absolutePath}")
    }
    return jsonFiles.sortedBy { it.name }.map { f ->
        BuildProfile.fromJson(f.readText())
    }
}
