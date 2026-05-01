package com.virjar.tk.env

import java.io.File

/**
 * 运行环境检测与数据目录管理。
 *
 * 通过检测 classpath 中 application.conf 的来源判断运行模式：
 * - 来源路径含 `build/resources/main`（Gradle 开发） → 开发环境
 * - 其他（如 `conf/` 目录） → 生产部署环境
 *
 * 所有数据子目录从单一根目录 [dataRoot] 派生，不可单独配置：
 * ```
 * $dataRoot/
 * ├── rocksdb/            # RocksDB 消息存储
 * ├── lucene-index/       # Lucene 全文索引
 * └── logs/               # 应用日志
 *     └── device-traces/  # 设备 trace 日志
 * ```
 *
 * 开发环境 dataRoot 为 ~/.tk/，生产环境为 classpath 同级 data/。
 */
object Environment {
    /** 应用名称，用于开发环境用户目录下的子目录 */
    private const val APP_DIR_NAME = ".tk"

    /** 运行时 classpath 根目录（application.conf 所在目录） */
    val runtimeClassPathDir: File = resolveClassPathDir()

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

    /** 日志目录 */
    val logsDir: File = ensureDir(File(dataRoot, "logs"))

    init {
        // 使用 println 而非 logger，因为此时 logback 尚未完成初始化
        println("[TeamTalk] isDevelopment=$isDevelopment, dataRoot=${dataRoot.absolutePath}")
    }

    private fun resolveClassPathDir(): File {
        // 1. 尝试从 classpath 加载（开发环境：文件在 build/resources/main 下）
        val configUrl = Environment::class.java.classLoader.getResource("application.conf")
        if (configUrl != null && configUrl.protocol == "file") {
            return File(configUrl.file).parentFile
        }
        // 2. Fallback：从 -Dconfig.file 系统属性推断（生产部署场景）
        val configFile = System.getProperty("config.file")
        if (configFile != null) {
            val file = File(configFile)
            if (file.exists()) {
                return file.absoluteFile.parentFile
            }
        }
        // 3. Fallback：从 JAR 所在目录推断（lib/ 的父目录）
        val jarLocation = Environment::class.java.protectionDomain.codeSource.location
        if (jarLocation != null) {
            val jarFile = File(jarLocation.toURI())
            return if (jarFile.parentFile?.name == "lib") jarFile.parentFile.parentFile else jarFile.parentFile
        }
        throw IllegalStateException("Cannot resolve classpath directory from application.conf")
    }

    private fun detectDevelopment(): Boolean {
        val path = runtimeClassPathDir.absolutePath
        // Gradle 开发环境：资源文件在 build/resources/main 下
        return path.endsWith("build/resources/main")
    }

    private fun resolveStorageRoot(): File {
        return if (isDevelopment) {
            File(System.getProperty("user.home"), APP_DIR_NAME)
        } else {
            // 生产环境：classpath 根目录的父目录下的 data/
            File(runtimeClassPathDir.parent, "data")
        }
    }

    private fun ensureDir(dir: File): File {
        dir.mkdirs()
        return dir
    }
}
