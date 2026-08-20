package com.virjar.tk.agent

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

internal data class AgentUnixIdentity(
    val userName: String,
    val uid: Int,
    val gid: Int,
    val primaryGroupName: String = userName,
)

/** A verified, dedicated agent directory. Child creation never repairs pre-existing paths. */
internal class AgentDataDirectory internal constructor(
    val root: Path,
    internal val ownerUid: Int,
    internal val ownerGid: Int,
) {
    fun ensurePrivateChild(name: String): Path {
        require(PRIVATE_CHILD_NAME.matches(name)) { "Invalid private child directory name" }
        val child = root.resolve(name)
        var created = false
        if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(child, DIRECTORY_ATTRIBUTE)
            created = true
            // POSIX creation attributes are still subject to the process umask. This path was
            // created by us in the already verified private root, so setting its exact mode does
            // not adopt or repair caller-owned storage.
            Files.setPosixFilePermissions(child, DIRECTORY_PERMISSIONS)
            forceDirectory(root)
        }
        return try {
            AgentDataDirectoryPolicy.validatePrivateDirectory(child, ownerUid, ownerGid)
            child
        } catch (failure: Throwable) {
            if (created) Files.deleteIfExists(child)
            throw failure
        }
    }

    private companion object {
        val PRIVATE_CHILD_NAME = Regex("[A-Za-z0-9._-]{1,64}")
        val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        val DIRECTORY_ATTRIBUTE = PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS)

        fun forceDirectory(directory: Path) {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
    }
}

/**
 * Establishes a dedicated data root without ever adopting or chmod'ing an arbitrary directory.
 * Every existing path component is inspected with NOFOLLOW; only one missing leaf may be created.
 */
internal object AgentDataDirectoryPolicy {
    private const val MARKER_NAME = ".tt-agent-data"
    private const val MARKER_CONTENT = "team-talk-agent-data-v1\n"
    private val directoryPermissions = PosixFilePermissions.fromString("rwx------")
    private val filePermissions = PosixFilePermissions.fromString("rw-------")
    private val directoryAttribute = PosixFilePermissions.asFileAttribute(directoryPermissions)
    private val fileAttribute = PosixFilePermissions.asFileAttribute(filePermissions)
    private val broadRoots = setOf(
        "/etc",
        "/var",
        "/var/lib",
        "/opt",
        "/usr",
        "/root",
        "/home",
        "/Users",
        "/tmp",
        "/private/tmp",
    ).flatMap { raw ->
        val lexical = Path.of(raw).toAbsolutePath().normalize()
        listOf(lexical, runCatching { lexical.toRealPath() }.getOrDefault(lexical))
    }.toSet()
    private val temporaryRoots = buildSet {
        listOfNotNull(
            "/tmp",
            "/private/tmp",
            "/var/tmp",
            System.getProperty("java.io.tmpdir")?.takeIf(String::isNotBlank),
        ).forEach { raw ->
            val lexical = Path.of(raw).toAbsolutePath().normalize()
            add(lexical)
            add(runCatching { lexical.toRealPath() }.getOrDefault(lexical))
        }
    }
    private val unsafeParentPermissions = setOf(
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.OTHERS_WRITE,
    )

    fun openRuntime(
        dataDir: File,
        userHome: File? = System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { File(it) },
    ): AgentDataDirectory {
        val requested = validateRequestedPath(dataDir.toPath(), userHome)
        if (!Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
            createDedicatedLeaf(requested, owner = null)
        }
        val canonical = validateKnownDirectory(
            requested,
            expectedOwner = null,
            expectedUserName = System.getProperty("user.name")?.takeIf { it.isNotBlank() },
        )
        rejectBroadCanonical(canonical, userHome)
        return AgentDataDirectory(canonical, unixInt(canonical, "uid"), unixInt(canonical, "gid"))
    }

    /** Called only by the privileged installer. Existing directories are validated, never repaired. */
    fun prepareForService(dataDir: File, owner: AgentUnixIdentity): AgentDataDirectory {
        require(owner.uid > 0 && owner.gid > 0) { "Agent service identity must be non-root" }
        val requested = validateRequestedPath(dataDir.toPath(), userHome = null)
        if (!Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
            createDedicatedLeaf(requested, owner)
        }
        val canonical = validateKnownDirectory(requested, owner, expectedUserName = null)
        rejectBroadCanonical(canonical, userHome = null)
        return AgentDataDirectory(canonical, owner.uid, owner.gid)
    }

    /** Installer final step: validates a prior explicit preparation and never creates anything. */
    fun openPreparedForService(dataDir: File, owner: AgentUnixIdentity): AgentDataDirectory {
        require(owner.uid > 0 && owner.gid > 0) { "Agent service identity must be non-root" }
        val requested = validateRequestedPath(dataDir.toPath(), userHome = null)
        require(Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
            "Agent service dataDir is not prepared; run prepare-service-data first"
        }
        val canonical = validateKnownDirectory(requested, owner, expectedUserName = null)
        rejectBroadCanonical(canonical, userHome = null)
        return AgentDataDirectory(canonical, owner.uid, owner.gid)
    }

    internal fun validatePrivateDirectory(path: Path, expectedUid: Int, expectedGid: Int) {
        val attributes = basicAttributes(path)
        require(!attributes.isSymbolicLink && attributes.isDirectory) {
            "Agent private path must be a real directory"
        }
        require(posixPermissions(path) == directoryPermissions) {
            "Agent private directory must already have mode 0700"
        }
        require(unixInt(path, "uid") == expectedUid && unixInt(path, "gid") == expectedGid) {
            "Agent private directory has the wrong owner"
        }
    }

    private fun validateRequestedPath(rawPath: Path, userHome: File?): Path {
        val requested = rawPath.toAbsolutePath().normalize()
        require(requested.parent != null) { "Agent data directory cannot be a filesystem root" }
        require(requested !in broadRoots) { "Agent data directory cannot be a broad system or home root" }
        require(temporaryRoots.none { requested.startsWith(it) }) {
            "Agent data directory cannot be placed below a temporary filesystem root"
        }
        val home = userHome?.toPath()?.toAbsolutePath()?.normalize()
        require(home == null || requested != home) { "Agent data directory cannot be the whole user home" }
        validateExistingDirectoryChain(requireNotNull(requested.parent))
        return requested
    }

    private fun validateExistingDirectoryChain(directory: Path) {
        val root = requireNotNull(directory.root)
        var current = root
        for (component in root.relativize(directory)) {
            current = current.resolve(component)
            require(Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                "Agent data directory parent must already exist"
            }
            val attributes = basicAttributes(current)
            require(!attributes.isSymbolicLink && attributes.isDirectory) {
                "Agent data directory parent chain cannot contain symlinks"
            }
            require(posixPermissions(current).none { it in unsafeParentPermissions }) {
                "Agent data directory parent chain cannot be writable by group or others"
            }
        }
    }

    private fun createDedicatedLeaf(path: Path, owner: AgentUnixIdentity?) {
        val parent = requireNotNull(path.parent)
        require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "Agent data directory already exists" }
        Files.createDirectory(path, directoryAttribute)
        var complete = false
        try {
            Files.setPosixFilePermissions(path, directoryPermissions)
            val marker = path.resolve(MARKER_NAME)
            val options: Set<OpenOption> = setOf(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            FileChannel.open(marker, options, fileAttribute).use { channel ->
                val bytes = MARKER_CONTENT.toByteArray()
                var buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.setPosixFilePermissions(marker, filePermissions)
            owner?.let {
                setUnixOwner(marker, it)
                setUnixOwner(path, it)
            }
            forceDirectory(path)
            forceDirectory(parent)
            complete = true
        } finally {
            if (!complete) {
                Files.deleteIfExists(path.resolve(MARKER_NAME))
                Files.deleteIfExists(path)
            }
        }
    }

    private fun validateKnownDirectory(
        path: Path,
        expectedOwner: AgentUnixIdentity?,
        expectedUserName: String?,
    ): Path {
        val attributes = basicAttributes(path)
        require(!attributes.isSymbolicLink && attributes.isDirectory) {
            "Agent data path must be a real dedicated directory"
        }
        require(posixPermissions(path) == directoryPermissions) {
            "Existing agent data directory must already have mode 0700"
        }
        val marker = path.resolve(MARKER_NAME)
        require(Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            "Existing directory is not a recognized dedicated agent data directory"
        }
        val markerAttributes = basicAttributes(marker)
        require(!markerAttributes.isSymbolicLink && markerAttributes.isRegularFile) {
            "Existing directory is not a recognized dedicated agent data directory"
        }
        require(posixPermissions(marker) == filePermissions) {
            "Agent data marker must already have mode 0600"
        }
        require(unixInt(marker, "nlink") == 1) {
            "Agent data marker cannot be hard-linked"
        }
        require(readMarker(marker) == MARKER_CONTENT) {
            "Existing directory has an unknown agent data marker"
        }
        val uid = unixInt(path, "uid")
        val gid = unixInt(path, "gid")
        require(unixInt(marker, "uid") == uid && unixInt(marker, "gid") == gid) {
            "Agent data marker has the wrong owner"
        }
        expectedOwner?.let {
            require(uid == it.uid && gid == it.gid) { "Agent data directory has the wrong service owner" }
        }
        expectedUserName?.let { expected ->
            val actual = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).name.substringAfterLast('\\')
            require(actual == expected) { "Agent data directory is not owned by the runtime user" }
        }
        return path.toRealPath()
    }

    private fun rejectBroadCanonical(path: Path, userHome: File?) {
        require(path !in broadRoots) { "Agent data directory resolves to a broad root" }
        val canonicalHome = userHome?.toPath()?.let { home ->
            runCatching { home.toRealPath() }.getOrElse { home.toAbsolutePath().normalize() }
        }
        require(canonicalHome == null || path != canonicalHome) {
            "Agent data directory resolves to the whole user home"
        }
    }

    private fun readMarker(path: Path): String {
        val options: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        return Files.newByteChannel(path, options).use { channel ->
            val size = channel.size()
            require(size in 1L..128L) { "Invalid agent data marker size" }
            val buffer = ByteBuffer.allocate(size.toInt())
            while (buffer.hasRemaining()) {
                require(channel.read(buffer) >= 0) { "Truncated agent data marker" }
            }
            buffer.flip()
            Charsets.UTF_8.decode(buffer).toString()
        }
    }

    private fun basicAttributes(path: Path): BasicFileAttributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )

    private fun posixPermissions(path: Path): Set<PosixFilePermission> {
        val view = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: error("Agent storage requires a POSIX filesystem")
        return view.readAttributes().permissions()
    }

    private fun unixInt(path: Path, attribute: String): Int =
        (Files.getAttribute(path, "unix:$attribute", LinkOption.NOFOLLOW_LINKS) as Number).toInt()

    private fun setUnixOwner(path: Path, owner: AgentUnixIdentity) {
        if (unixInt(path, "gid") != owner.gid) {
            Files.setAttribute(path, "unix:gid", owner.gid, LinkOption.NOFOLLOW_LINKS)
        }
        if (unixInt(path, "uid") != owner.uid) {
            Files.setAttribute(path, "unix:uid", owner.uid, LinkOption.NOFOLLOW_LINKS)
        }
    }

    private fun forceDirectory(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }
}
