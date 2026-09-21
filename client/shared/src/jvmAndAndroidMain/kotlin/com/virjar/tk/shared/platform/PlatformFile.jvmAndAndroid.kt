package com.virjar.tk.shared.platform

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.readBytes as jvmReadBytes
import kotlin.io.writeBytes as jvmWriteBytes
import kotlin.io.appendText as jvmAppendText
import kotlin.io.deleteRecursively as jvmDeleteRecursively

import kotlin.io.copyTo as jvmCopyTo

actual typealias PlatformFile = File
actual fun PlatformFile.readBytes(): ByteArray = jvmReadBytes()
actual fun PlatformFile.writeBytes(bytes: ByteArray) = jvmWriteBytes(bytes)
actual fun PlatformFile.appendText(text: String) = jvmAppendText(text)
actual fun PlatformFile.deleteRecursively(): Boolean = jvmDeleteRecursively()
actual fun PlatformFile.isSymbolicLink(): Boolean = Files.isSymbolicLink(toPath())
actual fun PlatformFile.atomicReplaceWith(source: PlatformFile) {
    Files.move(source.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
actual fun PlatformFile.syncToDisk() { RandomAccessFile(this, "rw").use { it.fd.sync() } }

actual fun PlatformFile.copyTo(target: PlatformFile, overwrite: Boolean): PlatformFile = jvmCopyTo(target, overwrite)
