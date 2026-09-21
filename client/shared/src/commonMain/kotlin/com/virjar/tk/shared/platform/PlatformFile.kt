package com.virjar.tk.shared.platform

/** Platform path. JVM aliases java.io.File so existing platform callers keep their source API. */
expect class PlatformFile {
    constructor(pathname: String)
    constructor(parent: PlatformFile?, child: String)
    constructor(parent: String?, child: String)
    fun getPath(): String
    fun getName(): String
    fun getParent(): String?
    fun getParentFile(): PlatformFile?
    fun getAbsolutePath(): String
    fun getAbsoluteFile(): PlatformFile
    fun getCanonicalPath(): String
    fun getCanonicalFile(): PlatformFile
    fun exists(): Boolean
    fun isFile(): Boolean
    fun isDirectory(): Boolean
    fun mkdirs(): Boolean
    fun mkdir(): Boolean
    fun createNewFile(): Boolean
    fun delete(): Boolean
    fun renameTo(destination: PlatformFile): Boolean
    fun length(): Long
    fun lastModified(): Long
    fun setLastModified(time: Long): Boolean
    fun listFiles(): Array<PlatformFile>?
    fun list(): Array<String>?
    fun getUsableSpace(): Long
}
val PlatformFile.path: String get() = getPath()
val PlatformFile.name: String get() = getName()
val PlatformFile.parent: String? get() = getParent()
val PlatformFile.parentFile: PlatformFile? get() = getParentFile()
val PlatformFile.absolutePath: String get() = getAbsolutePath()
val PlatformFile.absoluteFile: PlatformFile get() = getAbsoluteFile()
val PlatformFile.canonicalPath: String get() = getCanonicalPath()
val PlatformFile.canonicalFile: PlatformFile get() = getCanonicalFile()
val PlatformFile.isFile: Boolean get() = isFile()
val PlatformFile.isDirectory: Boolean get() = isDirectory()
val PlatformFile.usableSpace: Long get() = getUsableSpace()
val PlatformFile.extension: String get() = name.substringAfterLast('.', "")
val PlatformFile.nameWithoutExtension: String get() = name.substringBeforeLast('.', name)
fun PlatformFile.resolve(child: String): PlatformFile = PlatformFile(this, child)
expect fun PlatformFile.readBytes(): ByteArray
expect fun PlatformFile.writeBytes(bytes: ByteArray)
fun PlatformFile.readText(): String = readBytes().decodeToString()
fun PlatformFile.writeText(text: String) = writeBytes(text.encodeToByteArray())
expect fun PlatformFile.appendText(text: String)
expect fun PlatformFile.deleteRecursively(): Boolean
expect fun PlatformFile.isSymbolicLink(): Boolean
expect fun PlatformFile.atomicReplaceWith(source: PlatformFile)
expect fun PlatformFile.syncToDisk()
expect fun PlatformFile.copyTo(target: PlatformFile, overwrite: Boolean = false): PlatformFile
