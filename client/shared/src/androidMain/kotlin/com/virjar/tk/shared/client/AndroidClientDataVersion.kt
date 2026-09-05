package com.virjar.tk.shared.client

import android.content.Context
import android.util.AtomicFile
import com.virjar.tk.protocol.ProtocolVersions
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** 仅在 Application.onCreate、所有持久 owner 初始化前执行，不随 Activity 重建重复执行。 */
fun prepareAndroidClientDataVersion(context: Context, currentMajor: Int = ProtocolVersions.MAJOR): Boolean {
    val app = context.applicationContext
    val markerFile = File(app.noBackupFilesDir, ".client-data-version")
    val marker = AtomicFile(markerFile)
    return prepareClientDataVersion(
        currentMajor = currentMajor,
        readMarker = {
            if (markerFile.exists() || File(markerFile.path + ".bak").exists()) {
                marker.openRead().use { input ->
                    val bytes = input.readBytes()
                    check(bytes.size <= 128) { "Client data version marker is too large" }
                    bytes.toString(Charsets.UTF_8)
                }
            } else null
        },
        writeMarker = { text ->
            val output = marker.startWrite()
            try {
                output.write(text.toByteArray(Charsets.UTF_8))
                marker.finishWrite(output)
            } catch (failure: Throwable) {
                marker.failWrite(output)
                throw failure
            }
        },
        resetOwnedData = {
            // 根路径全部来自当前应用 sandbox；不调用 clearApplicationUserData 杀进程或碰其他应用。
            val roots = listOf(
                app.filesDir, app.cacheDir, app.noBackupFilesDir,
                File(app.applicationInfo.dataDir, "databases"),
                File(app.applicationInfo.dataDir, "shared_prefs"),
                File(app.applicationInfo.dataDir, "app_teamtalk"),
            ) + app.externalCacheDirs.filterNotNull()
            roots.distinctBy { it.absolutePath }.forEach { root ->
                if (root.isDirectory) {
                    checkNotNull(root.listFiles()) { "Cannot list client data directory" }
                        .filter { it != markerFile && it.name !in setOf(".client-data-version.bak", ".client-data-version.new") }
                        .forEach { deleteOwnedAndroidTree(it.toPath()) }
                }
            }
        },
    )
}

private fun deleteOwnedAndroidTree(root: Path) {
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}
