package com.virjar.tk.env

import java.io.File
import java.util.jar.JarFile

/**
 * 开发模式下预加载所有类到 JVM 内存，防止 `gradle clean` 删除 .class 文件后
 * 正在运行的服务端出现 `NoClassDefFoundError`。
 *
 * 同时支持两种启动方式：
 * - `./gradlew :server:run`：classpath 为目录结构，扫描 .class 文件
 * - IDEA 直接运行：classpath 为 JAR 包，扫描 JAR 内 .class 条目
 *
 * 用 `Class.forName(name, false, classLoader)` 加载字节码但不执行静态初始化器。
 * 生产模式下此函数不做任何操作。
 */
object ClassPreloader {

    fun preloadDevClasses() {
        if (!Environment.isDevelopment) return

        val classLoader = Thread.currentThread().contextClassLoader
        val classpath = System.getProperty("java.class.path") ?: return
        val entries = classpath.split(File.pathSeparatorChar).map { File(it) }
        var loaded = 0

        // 扫描目录条目（./gradlew :server:run）
        for (dir in entries.filter { it.isDirectory }) {
            val rootPath = dir.toPath()
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { file ->
                    loaded += loadClass(rootPath, file, classLoader)
                }
        }

        // 扫描项目 JAR 条目（IDEA：build/libs/*.jar）
        for (jar in entries.filter { it.isFile && it.name.endsWith(".jar") && it.absolutePath.contains("build") }) {
            try {
                JarFile(jar).use { jf ->
                    jf.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .forEach { entry ->
                            loaded += loadClassFromJar(entry, classLoader)
                        }
                }
            } catch (_: Throwable) {
                // 跳过无法读取的 JAR
            }
        }

        if (loaded > 0) {
            println("[TeamTalk] Preloaded $loaded classes (development mode)")
        }
    }

    private fun loadClass(rootPath: java.nio.file.Path, file: File, classLoader: ClassLoader): Int {
        val className = rootPath.relativize(file.toPath())
            .toString()
            .removeSuffix(".class")
            .replace('/', '.')
            .replace('\\', '.')
        return try {
            Class.forName(className, false, classLoader)
            1
        } catch (_: Throwable) {
            0
        }
    }

    private fun loadClassFromJar(entry: java.util.jar.JarEntry, classLoader: ClassLoader): Int {
        val className = entry.name.removeSuffix(".class").replace('/', '.')
        return try {
            Class.forName(className, false, classLoader)
            1
        } catch (_: Throwable) {
            0
        }
    }
}
