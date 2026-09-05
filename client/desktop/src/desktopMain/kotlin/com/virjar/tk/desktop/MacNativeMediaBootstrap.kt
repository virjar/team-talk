package com.virjar.tk.desktop

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

/**
 * 在 ComposeMediaPlayer 的 bridge 初始化之前，把精确匹配的 classpath 原生资源发布到它的 macOS 缓存。
 *
 * ComposeMediaPlayer 0.9 会在字节长度不变时复用已解压的 dylib。TeamTalk 的 manifest 携带完整的二进制 SHA，
 * 因此启动时可以在不改变上游公开播放器 API、不触碰任何用户媒体的前提下，确定性地做出缓存决策。
 * 唯一命名的临时文件加原子重命名也避免把不完整的 dylib 暴露给另一个 TeamTalk 进程。
 */
internal object MacNativeMediaBootstrap {
    private const val MANIFEST_RESOURCE =
        "composemediaplayer/native/teamtalk-local-player.properties"
    private const val LIBRARY_NAME = "libNativeVideoPlayer.dylib"

    @Volatile
    private var prepared = false

    fun prepare() {
        if (!System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("mac")) return
        if (prepared) return

        synchronized(this) {
            if (prepared) return
            // Conveyor 从应用 jar 中解压原生库，并把已验证的平台产物放到 java.library.path 上。
            // 在这种环境下有意不提供要发布到 ComposeMediaPlayer 开发缓存的 classpath dylib。
            if (!System.getProperty("app.dir").isNullOrBlank()) {
                prepared = true
                return
            }
            val platform = when (System.getProperty("os.arch", "").lowercase(Locale.ROOT)) {
                "aarch64", "arm64" -> "darwin-aarch64"
                "amd64", "x86_64" -> "darwin-x86-64"
                else -> error("Unsupported macOS architecture for local media playback")
            }
            val manifest = Properties().apply {
                val stream = checkNotNull(
                    MacNativeMediaBootstrap::class.java.classLoader.getResourceAsStream(MANIFEST_RESOURCE),
                ) { "Missing TeamTalk macOS native media manifest" }
                stream.use(::load)
            }
            check(manifest.getProperty("format") == "2") {
                "Unsupported TeamTalk macOS native media manifest"
            }
            val expectedHashes = listOf("$platform.sha256", "$platform.stripped.sha256")
                .map { key -> checkNotNull(manifest.getProperty(key)) { "Missing $key" } }
                .toSet()
            check(expectedHashes.all { it.matches(Regex("[0-9a-f]{64}")) }) {
                "Missing or invalid TeamTalk macOS native media hash for $platform"
            }
            val resourcePath = "composemediaplayer/native/$platform/$LIBRARY_NAME"
            val resourceBytes = checkNotNull(
                MacNativeMediaBootstrap::class.java.classLoader.getResourceAsStream(resourcePath),
            ) { "Missing TeamTalk macOS native media resource for $platform" }.use { it.readBytes() }
            val resourceSha = sha256(resourceBytes)
            check(resourceSha in expectedHashes) {
                "Classpath resolved the wrong macOS native media resource for $platform"
            }

            val cacheDirectory = Path.of(
                System.getProperty("user.home"),
                ".cache",
                "composemediaplayer",
                "native",
                platform,
            )
            val cachedLibrary = cacheDirectory.resolve(LIBRARY_NAME)
            val cacheMatches = Files.isRegularFile(cachedLibrary) && sha256(cachedLibrary) == resourceSha
            if (!cacheMatches) {
                Files.createDirectories(cacheDirectory)
                val temporaryLibrary = Files.createTempFile(cacheDirectory, ".$LIBRARY_NAME.", ".tmp")
                try {
                    Files.write(temporaryLibrary, resourceBytes)
                    check(temporaryLibrary.toFile().setExecutable(true)) {
                        "Cannot make the macOS native media cache executable"
                    }
                    try {
                        Files.move(
                            temporaryLibrary,
                            cachedLibrary,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temporaryLibrary, cachedLibrary, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(temporaryLibrary)
                }
            }
            prepared = true
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
