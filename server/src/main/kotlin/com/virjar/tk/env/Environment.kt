package com.virjar.tk.env

import java.io.File

/**
 * 运行环境检测与数据目录管理。
 *
 * 通过系统属性 `teamtalk.data.root` 或 classpath 路径判断运行模式：
 *
 * **开发模式**（Gradle 运行）：
 * - dataRoot 通过 `-Dteamtalk.data.root=<projectDir>/data` 传入
 * - 数据目录在项目根目录的 data/ 下，方便开发调试，git ignore 掉
 *
 * **生产模式**（部署运行）：
 * - dataRoot 为 classpath 父目录下的 data/
 * - 数据随部署目录一起管理，卸载即清理
 *
 * ```
 * $dataRoot/
 * ├── rocksdb/            # RocksDB 消息存储
 * ├── tokenstore/         # RocksDB Token 存储
 * ├── lucene-index/       # Lucene 全文索引
 * ├── file-store/
 * │   ├── rocksdb         # 文件存储索引
 * │   ├── files           # 大文件存储
 * │   └── tmp             # 临时文件
 * └── logs/               # 应用日志
 * ```
 */
object Environment {

    /** 是否为 IDE/Gradle 开发环境 */
    val isDevelopment: Boolean = detectDevelopment()

    /** 数据根目录，所有子目录从此派生 */
    val dataRoot: File = resolveStorageRoot()

    /** RocksDB 消息存储目录 */
    val rocksdbDir: File = ensureDir(File(dataRoot, "rocksdb"))

    /** Lucene 全文索引目录 */
    val luceneIndexDir: File = ensureDir(File(dataRoot, "lucene-index"))

    /** 文件存储 RocksDB 目录 */
    val fileStoreRocksdbDir: File = ensureDir(File(dataRoot, "file-store/rocksdb"))

    /** 文件存储文件系统目录（大文件） */
    val fileStoreFsDir: File = ensureDir(File(dataRoot, "file-store/files"))

    /** 文件存储临时目录 */
    val fileStoreTmpDir: File = ensureDir(File(dataRoot, "file-store/tmp"))

    /** Token 存储 RocksDB 目录 */
    val tokenStoreDir: File = ensureDir(File(dataRoot, "tokenstore"))

    /** 日志目录 */
    val logsDir: File = ensureDir(File(dataRoot, "logs"))

    /** 运行时 classpath 根目录（application.conf 所在目录） */
    val runtimeClassPathDir: File = resolveClassPathDir()

    /**
     * 启动信息输出。
     *
     * 注意：Environment 在 logback 初始化之前加载（logback 的 LOG_DIR 可能依赖
     * Environment 解析的系统属性），此处不能用 SLF4J，否则日志目录解析不正确。
     * 用 System.err 输出到 stderr，不经过 logback，不影响日志框架初始化。
     */
    init {
        System.err.println("[TeamTalk] isDevelopment=$isDevelopment, dataRoot=${dataRoot.absolutePath}")
    }

    private fun detectDevelopment(): Boolean {
        // 优先检查系统属性（Gradle runServer 会设置此属性）
        val dataRoot = System.getProperty("teamtalk.data.root")
        if (dataRoot != null) {
            // 如果显式设置了数据根目录，通过路径判断
            return dataRoot.contains("/team-talk") || dataRoot.contains("/build/")
        }
        // Fallback: 检测 classpath
        val configUrl = Environment::class.java.classLoader.getResource("application.conf")
        if (configUrl != null && configUrl.protocol == "file") {
            return configUrl.file.contains("build/resources/main")
        }
        return false
    }

    private fun resolveStorageRoot(): File {
        // 1. 最高优先级：系统属性（开发模式下 Gradle 传入项目根/data）
        val dataRoot = System.getProperty("teamtalk.data.root")
        if (dataRoot != null) {
            return ensureDir(File(dataRoot))
        }

        // 2. 生产模式：classpath 根目录的父目录下的 data/
        val classPathDir = resolveClassPathDir()
        return ensureDir(File(classPathDir.parent, "data"))
    }

    private fun resolveClassPathDir(): File {
        // 1. 从 classpath 加载
        val configUrl = Environment::class.java.classLoader.getResource("application.conf")
        if (configUrl != null && configUrl.protocol == "file") {
            return File(configUrl.file).parentFile
        }
        // 2. 从 -Dconfig.file 系统属性推断
        val configFile = System.getProperty("config.file")
        if (configFile != null) {
            val file = File(configFile)
            if (file.exists()) {
                return file.absoluteFile.parentFile
            }
        }
        // 3. 从 JAR 所在目录推断
        val jarLocation = Environment::class.java.protectionDomain.codeSource.location
        if (jarLocation != null) {
            val jarFile = File(jarLocation.toURI())
            return if (jarFile.parentFile?.name == "lib") jarFile.parentFile.parentFile else jarFile.parentFile
        }
        throw IllegalStateException("Cannot resolve classpath directory")
    }

    private fun ensureDir(dir: File): File {
        dir.mkdirs()
        return dir
    }
}
