package com.virjar.tk.shared.agent

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** 安全测试刻意避开被生产环境拒绝的 OS 临时文件系统树。 */
internal fun createAgentSecurityTestRoot(prefix: String): File {
    val parent = Path.of(System.getProperty("user.dir"))
        .toAbsolutePath()
        .normalize()
        .resolve("build")
        .resolve("agent-security-test-roots")
    Files.createDirectories(parent)
    require(!Files.isSymbolicLink(parent)) { "Agent security test root cannot be a symlink" }
    Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
    val root = Files.createTempDirectory(
        parent,
        prefix,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
    )
    require(Files.exists(root, LinkOption.NOFOLLOW_LINKS))
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
    return root.toFile().canonicalFile
}
