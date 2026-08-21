package com.virjar.tk.client

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * A JVM data root whose leaf and authenticated children are private to one operating-system owner.
 *
 * Existing paths are only inspected; their owner or permissions are never repaired. New paths are
 * created with exact POSIX modes or an exact owner-only Windows ACL. This keeps Desktop credentials,
 * SQLite and crash state behind one fail-closed path policy instead of several subtly different
 * `mkdirs`/`FileOutputStream` implementations.
 */
class JvmPrivateDataDirectory private constructor(
    val root: Path,
    private val security: JvmPrivatePathSecurity,
) {
    /** Create or validate a private leaf below a trusted, already existing owner anchor. */
    companion object {
        fun openOrCreate(dataDir: File, ownerAnchor: File): JvmPrivateDataDirectory =
            open(dataDir, ownerAnchor, requireNew = false)

        /** Atomically claim a new private leaf; an existing path is never adopted. */
        fun createNew(dataDir: File, ownerAnchor: File): JvmPrivateDataDirectory =
            open(dataDir, ownerAnchor, requireNew = true)

        private fun open(
            dataDir: File,
            ownerAnchor: File,
            requireNew: Boolean,
        ): JvmPrivateDataDirectory {
            val root = dataDir.toPath().toAbsolutePath().normalize()
            require(root.parent != null) { "Private data directory cannot be a filesystem root" }
            val anchor = ownerAnchor.toPath().toAbsolutePath().normalize()
            val anchorAttributes = basicAttributes(anchor)
            requireRealDirectory(anchorAttributes, "Private data owner anchor")
            val expectedOwner = Files.getOwner(anchor, LinkOption.NOFOLLOW_LINKS)

            val parent = requireNotNull(root.parent)
            val parentAttributes = basicAttributes(parent)
            requireRealDirectory(parentAttributes, "Private data directory parent")
            requireSameOwner(parent, expectedOwner, "Private data directory parent")
            requireSafeOwnerChain(anchor, parent, expectedOwner)
            val security = JvmPrivatePathSecurity.forPath(parent, expectedOwner)

            var created = false
            if (requireNew || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                security.createDirectory(root)
                created = true
                security.forceDirectory(parent)
            }
            return try {
                security.requirePrivateDirectory(root)
                JvmPrivateDataDirectory(root.toRealPath(LinkOption.NOFOLLOW_LINKS), security)
            } catch (failure: Throwable) {
                if (created) runCatching { Files.deleteIfExists(root) }
                throw failure
            }
        }

        /**
         * Open a caller-selected root that is already private. Its owner becomes the immutable owner
         * for every child. Desktop startup uses the anchor-taking overload above before publishing
         * the path; this overload also supports SDK tests and the separately verified headless root.
         */
        fun openExisting(dataDir: File): JvmPrivateDataDirectory {
            val root = dataDir.toPath().toAbsolutePath().normalize()
            val attributes = basicAttributes(root)
            requireRealDirectory(attributes, "Private data directory")
            val expectedOwner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS)
            val security = JvmPrivatePathSecurity.forPath(root, expectedOwner)
            security.requirePrivateDirectory(root)
            return JvmPrivateDataDirectory(root.toRealPath(LinkOption.NOFOLLOW_LINKS), security)
        }

        /** Validate an existing root against a separate current-user anchor. */
        fun openExisting(dataDir: File, ownerAnchor: File): JvmPrivateDataDirectory {
            val root = dataDir.toPath().toAbsolutePath().normalize()
            val anchor = ownerAnchor.toPath().toAbsolutePath().normalize()
            val anchorAttributes = basicAttributes(anchor)
            requireRealDirectory(anchorAttributes, "Private data owner anchor")
            val expectedOwner = Files.getOwner(anchor, LinkOption.NOFOLLOW_LINKS)
            root.parent?.takeIf { it.startsWith(anchor) }?.let { parent ->
                requireSafeOwnerChain(anchor, parent, expectedOwner)
            }
            val security = JvmPrivatePathSecurity.forPath(root, expectedOwner)
            security.requirePrivateDirectory(root)
            return JvmPrivateDataDirectory(root.toRealPath(LinkOption.NOFOLLOW_LINKS), security)
        }
    }

    fun ensureDirectory(vararg components: String): File =
        ensureDirectory(components.asList()).toFile()

    fun atomicTextFile(
        privateDirectories: List<String> = emptyList(),
        fileName: String,
    ): JvmPrivateAtomicTextFile {
        requireSafeComponent(fileName)
        return JvmPrivateAtomicTextFile(this, privateDirectories, fileName)
    }

    /**
     * Pre-create a 0600/owner-only regular file before handing it to a library such as SQLite.
     * The library therefore never gets a chance to choose a process-umask-dependent mode.
     */
    fun preparePrivateFile(privateDirectories: List<String>, fileName: String): File {
        requireSafeComponent(fileName)
        val directory = ensureDirectory(privateDirectories)
        val target = directory.resolve(fileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try {
                security.createEmptyFile(target)
                security.forceDirectory(directory)
            } catch (_: FileAlreadyExistsException) {
                // A racing path is validated below and never silently adopted.
            }
        }
        security.requirePrivateFile(target)
        return target.toFile()
    }

    fun requirePrivateFile(privateDirectories: List<String>, fileName: String): File {
        requireSafeComponent(fileName)
        val directory = ensureDirectory(privateDirectories, create = false)
            ?: error("Private namespace does not exist")
        val target = directory.resolve(fileName)
        security.requirePrivateFile(target)
        return target.toFile()
    }

    /**
     * Copy an old user-owned tree into a newly created private staging root.
     *
     * The source is never deleted or chmodded. Every source node must be a real directory or file
     * owned by the same OS principal. POSIX directories must be exactly 0700 and files exactly 0600
     * with one link; Windows nodes must have the exact owner-only ACL. Files are opened with
     * NOFOLLOW, copied to a private temporary file, and checked for identity/size changes before
     * atomic publication. The legacy tree itself must already satisfy the same private access
     * policy; migration never leaves a refresh credential behind in an installation directory
     * readable by another account.
     */
    fun copyTrustedLegacyTreeFrom(sourceDirectory: File, ignoredRootNames: Set<String> = emptySet()) {
        require(isEmpty()) { "Private migration destination must be empty" }
        ignoredRootNames.forEach(::requireSafeComponent)
        val source = sourceDirectory.toPath().toAbsolutePath().normalize()
        validateTrustedLegacyTree(source, ignoredRootNames)
        require(!root.startsWith(source) && !source.startsWith(root)) {
            "Legacy and private data directories must not overlap"
        }
        try {
            copyLegacyDirectory(source, emptyList(), ignoredRootNames)
        } catch (failure: Throwable) {
            runCatching(::deletePrivateContents).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    /** Read-only whole-tree validation used before accepting an existing migration receipt. */
    fun validateTrustedLegacyTree(
        sourceDirectory: File,
        ignoredRootNames: Set<String> = emptySet(),
    ) {
        ignoredRootNames.forEach(::requireSafeComponent)
        validateTrustedLegacyTree(
            sourceDirectory.toPath().toAbsolutePath().normalize(),
            ignoredRootNames,
        )
    }

    /** Recursively revalidate a completed private tree immediately before external publication. */
    fun validatePrivateTree() {
        security.requirePrivateDirectory(root)
        Files.newDirectoryStream(root).use { children -> children.forEach(::validatePrivateNode) }
    }

    fun isEmpty(): Boolean = Files.newDirectoryStream(root).use { !it.iterator().hasNext() }

    internal fun ensureDirectory(components: List<String>): Path =
        checkNotNull(ensureDirectory(components, create = true))

    internal fun security(): JvmPrivatePathSecurity = security

    private fun ensureDirectory(components: List<String>, create: Boolean): Path? {
        components.forEach(::requireSafeComponent)
        security.requirePrivateDirectory(root)
        var directory = root
        components.forEach { component ->
            val child = directory.resolve(component)
            if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return null
                try {
                    security.createDirectory(child)
                    security.forceDirectory(directory)
                } catch (_: FileAlreadyExistsException) {
                    // Validate below; a competing unsafe path is never adopted.
                }
            }
            security.requirePrivateDirectory(child)
            directory = child
        }
        return directory
    }

    private fun copyLegacyDirectory(
        source: Path,
        relativeComponents: List<String>,
        ignoredRootNames: Set<String>,
    ) {
        val destination = ensureDirectory(relativeComponents)
        Files.newDirectoryStream(source).use { children ->
            for (child in children) {
                val name = child.fileName.toString()
                requireSafeComponent(name)
                if (relativeComponents.isEmpty() && name in ignoredRootNames) continue
                val attributes = basicAttributes(child)
                when {
                    attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther -> {
                        security.requireTrustedLegacyDirectory(child)
                        copyLegacyDirectory(child, relativeComponents + name, ignoredRootNames)
                    }
                    attributes.isRegularFile && !attributes.isSymbolicLink && !attributes.isOther -> {
                        security.requireTrustedLegacyFile(child)
                        copyLegacyFile(child, destination, name, attributes)
                    }
                    else -> error("Legacy data contains a link or unsupported path: $child")
                }
            }
        }
    }

    private fun validateTrustedLegacyTree(source: Path, ignoredRootNames: Set<String>) {
        security.requirePrivateDirectory(source)
        Files.newDirectoryStream(source).use { children ->
            for (child in children) {
                val name = child.fileName.toString()
                requireSafeComponent(name)
                // Ignored names are root-level process artifacts such as `.lock`.
                if (name in ignoredRootNames) continue
                validateLegacyNode(child)
            }
        }
    }

    private fun validateLegacyNode(path: Path) {
        val attributes = basicAttributes(path)
        when {
            attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther -> {
                security.requireTrustedLegacyDirectory(path)
                Files.newDirectoryStream(path).use { children -> children.forEach(::validateLegacyNode) }
            }
            attributes.isRegularFile && !attributes.isSymbolicLink && !attributes.isOther ->
                security.requireTrustedLegacyFile(path)
            else -> error("Legacy data contains a link or unsupported path: $path")
        }
    }

    private fun validatePrivateNode(path: Path) {
        val attributes = basicAttributes(path)
        when {
            attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther -> {
                security.requirePrivateDirectory(path)
                Files.newDirectoryStream(path).use { children -> children.forEach(::validatePrivateNode) }
            }
            attributes.isRegularFile && !attributes.isSymbolicLink && !attributes.isOther ->
                security.requirePrivateFile(path)
            else -> error("Private tree contains a link or unsupported path: $path")
        }
    }

    private fun deletePrivateContents() {
        val paths = Files.walk(root).use { stream ->
            stream.filter { it != root }
                .sorted(Comparator.comparingInt<Path> { it.nameCount }.reversed())
                .toList()
        }
        paths.forEach { path ->
            val attributes = basicAttributes(path)
            when {
                attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther ->
                    security.requirePrivateDirectory(path)
                attributes.isRegularFile && !attributes.isSymbolicLink && !attributes.isOther ->
                    security.requirePrivateFile(path)
                else -> error("Private migration cleanup encountered an unsafe path")
            }
            Files.delete(path)
        }
        security.forceDirectory(root)
    }

    private fun copyLegacyFile(
        source: Path,
        destinationDirectory: Path,
        fileName: String,
        expectedAttributes: BasicFileAttributes,
    ) {
        val target = destinationDirectory.resolve(fileName)
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            "Legacy migration destination already exists"
        }
        val temporary = security.createTempFile(destinationDirectory, ".$fileName-", ".migrating")
        var installed = false
        try {
            val readOptions: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            FileChannel.open(source, readOptions).use { input ->
                security.requireTrustedLegacyFile(source)
                FileChannel.open(temporary, PRIVATE_WRITE_OPTIONS).use { output ->
                    val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
                    while (true) {
                        buffer.clear()
                        val count = input.read(buffer)
                        if (count < 0) break
                        buffer.flip()
                        while (buffer.hasRemaining()) output.write(buffer)
                    }
                    output.force(true)
                }
            }
            security.requirePrivateFile(temporary)
            val after = basicAttributes(source)
            require(
                expectedAttributes.fileKey() != null &&
                    expectedAttributes.fileKey() == after.fileKey() &&
                    expectedAttributes.size() == after.size() &&
                    expectedAttributes.lastModifiedTime() == after.lastModifiedTime(),
            ) { "Legacy data changed while it was being copied" }
            security.requireTrustedLegacyFile(source)
            moveAtomically(temporary, target, replaceExisting = false)
            security.requirePrivateFile(target)
            security.forceDirectory(destinationDirectory)
            installed = true
        } finally {
            if (!installed || Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(temporary)
            }
        }
    }
}

/** An atomic, bounded text payload in a [JvmPrivateDataDirectory]. */
class JvmPrivateAtomicTextFile internal constructor(
    private val dataDirectory: JvmPrivateDataDirectory,
    private val privateDirectories: List<String>,
    private val fileName: String,
) {
    init {
        privateDirectories.forEach(::requireSafeComponent)
        requireSafeComponent(fileName)
    }

    fun existsNonEmpty(): Boolean {
        val directory = openNamespace(create = false) ?: return false
        val target = directory.resolve(fileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        return dataDirectory.security().requirePrivateFile(target).size() > 0L
    }

    fun readText(maxBytes: Long = DEFAULT_MAX_TEXT_BYTES): String? {
        require(maxBytes in 1L..Int.MAX_VALUE.toLong()) { "Invalid private text size limit" }
        val directory = openNamespace(create = false) ?: return null
        val target = directory.resolve(fileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        val expected = dataDirectory.security().requirePrivateFile(target)
        require(expected.size() <= maxBytes) { "Private text file is too large" }
        val bytes = FileChannel.open(target, PRIVATE_READ_OPTIONS).use { channel ->
            val size = channel.size()
            require(size == expected.size() && size <= maxBytes) {
                "Private text file changed before read"
            }
            val buffer = ByteBuffer.allocate(size.toInt())
            while (buffer.hasRemaining()) {
                require(channel.read(buffer) >= 0) { "Private text file changed during read" }
            }
            buffer.array()
        }
        val after = dataDirectory.security().requirePrivateFile(target)
        require(
            expected.fileKey() != null && expected.fileKey() == after.fileKey() &&
                expected.size() == after.size() && expected.lastModifiedTime() == after.lastModifiedTime(),
        ) { "Private text file changed during read" }
        return bytes.decodeToString()
    }

    fun replaceText(content: String, maxBytes: Long = DEFAULT_MAX_TEXT_BYTES) {
        val bytes = content.encodeToByteArray()
        require(bytes.size.toLong() <= maxBytes) { "Private text content is too large" }
        val directory = checkNotNull(openNamespace(create = true))
        val target = directory.resolve(fileName)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            dataDirectory.security().requirePrivateFile(target)
        }
        val temporary = dataDirectory.security().createTempFile(directory, ".$fileName-", ".tmp")
        var installed = false
        try {
            FileChannel.open(temporary, PRIVATE_WRITE_OPTIONS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            dataDirectory.security().requirePrivateFile(temporary)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                dataDirectory.security().requirePrivateFile(target)
            }
            moveAtomically(temporary, target, replaceExisting = true)
            dataDirectory.security().requirePrivateFile(target)
            dataDirectory.security().forceDirectory(directory)
            installed = true
        } finally {
            if (!installed || Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(temporary)
            }
        }
    }

    fun delete(): Boolean {
        val directory = openNamespace(create = false) ?: return false
        val target = directory.resolve(fileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        dataDirectory.security().requirePrivateFile(target)
        Files.delete(target)
        dataDirectory.security().forceDirectory(directory)
        return true
    }

    private fun openNamespace(create: Boolean): Path? = if (create) {
        dataDirectory.ensureDirectory(privateDirectories)
    } else {
        var directory = dataDirectory.root
        dataDirectory.security().requirePrivateDirectory(directory)
        for (component in privateDirectories) {
            val child = directory.resolve(component)
            if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) return null
            dataDirectory.security().requirePrivateDirectory(child)
            directory = child
        }
        directory
    }
}

private fun requireSafeComponent(component: String) {
    require(
        component.isNotBlank() && component != "." && component != ".." &&
            component.none { it == '/' || it == '\\' || it.isISOControl() },
    ) { "Unsafe private path component" }
}

private fun moveAtomically(source: Path, target: Path, replaceExisting: Boolean) {
    val options = buildList {
        add(StandardCopyOption.ATOMIC_MOVE)
        if (replaceExisting) add(StandardCopyOption.REPLACE_EXISTING)
    }.toTypedArray()
    try {
        Files.move(source, target, *options)
    } catch (unsupported: AtomicMoveNotSupportedException) {
        throw IllegalStateException("Private storage requires atomic replacement", unsupported)
    }
}

private val PRIVATE_READ_OPTIONS: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
private val PRIVATE_WRITE_OPTIONS: Set<OpenOption> = setOf(
    StandardOpenOption.WRITE,
    StandardOpenOption.TRUNCATE_EXISTING,
    LinkOption.NOFOLLOW_LINKS,
)
private const val DEFAULT_MAX_TEXT_BYTES = 1024L * 1024L
private const val COPY_BUFFER_BYTES = 64 * 1024
