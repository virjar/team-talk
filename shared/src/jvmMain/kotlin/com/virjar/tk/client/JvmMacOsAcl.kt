package com.virjar.tk.client

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Auditable macOS extended-ACL gate for the POSIX NIO provider.
 *
 * Java exposes mode bits on macOS but not the extended ACL that can override those bits. Commands
 * are fixed absolute binaries, receive the path as a separate non-shell argument, run in the C
 * locale, and have bounded output and time. Any command or parse uncertainty fails closed.
 */
object JvmMacOsAcl {
    fun requirePrivateLeaf(path: Path) {
        if (!isMacOs()) return
        require(readEntries(path).none { it.allow }) {
            "Private macOS path has an extended ALLOW ACL"
        }
    }

    fun requireSafeParent(path: Path) {
        if (!isMacOs()) return
        require(
            readEntries(path).none { entry ->
                entry.allow && entry.permissions.any { it !in NON_MUTATING_PARENT_PERMISSIONS }
            },
        ) { "macOS parent ACL grants mutation rights through an ALLOW entry" }
    }

    /** Only newly created empty paths may be changed; existing paths stay validation-only. */
    fun clearNewPathAcl(path: Path) {
        if (!isMacOs()) return
        runBounded(CHMOD, listOf("-N", absolute(path)))
    }

    internal fun isMacOs(): Boolean = System.getProperty("os.name").let { name ->
        name.startsWith("Mac", ignoreCase = true) || name.contains("Darwin", ignoreCase = true)
    }

    private fun readEntries(path: Path): List<MacOsAclEntry> =
        parseLsOutput(runBounded(LS, listOf("-lde", "-q", absolute(path))))

    private fun parseLsOutput(output: String): List<MacOsAclEntry> {
        val lines = output.lineSequence().toList()
        require(lines.isNotEmpty() && lines.first().isNotBlank()) { "macOS ACL listing has no header" }
        return lines.drop(1)
            .filter(String::isNotBlank)
            .map { line ->
                val match = ACL_LINE.matchEntire(line)
                    ?: throw IllegalStateException("Unrecognized macOS ACL listing")
                val permissions = match.groupValues[2].split(',').map(String::trim).filter(String::isNotEmpty)
                require(permissions.isNotEmpty()) { "macOS ACL entry has no permissions" }
                MacOsAclEntry(
                    allow = match.groupValues[1] == "allow",
                    permissions = permissions.toSet(),
                )
            }
    }

    private fun runBounded(executable: String, arguments: List<String>): String {
        val process = ProcessBuilder(listOf(executable) + arguments)
            .redirectErrorStream(true)
            .apply {
                environment()["LC_ALL"] = "C"
                environment()["LANG"] = "C"
            }
            .start()
        val output = ByteArrayOutputStream()
        val readerFailure = AtomicReference<Throwable?>()
        val reader = thread(start = true, isDaemon = true, name = "teamtalk-macos-acl-reader") {
            try {
                process.inputStream.use { input ->
                    val chunk = ByteArray(COMMAND_READ_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        if (output.size() + count > MAX_COMMAND_OUTPUT_BYTES) {
                            throw IllegalStateException("macOS ACL command output exceeds the safety limit")
                        }
                        output.write(chunk, 0, count)
                    }
                }
            } catch (failure: Throwable) {
                readerFailure.set(failure)
                process.destroyForcibly()
            }
        }

        val exited = try {
            process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            throw IllegalStateException("macOS ACL command was interrupted", failure)
        }
        if (!exited) process.destroyForcibly()
        reader.join(COMMAND_READER_JOIN_MILLIS)
        require(exited && !reader.isAlive) { "macOS ACL command timed out" }
        readerFailure.get()?.let { throw IllegalStateException("macOS ACL command output failed", it) }
        require(process.exitValue() == 0) { "macOS ACL command failed closed" }
        return output.toByteArray().decodeToString()
    }

    private fun absolute(path: Path): String = path.toAbsolutePath().normalize().toString()

    private data class MacOsAclEntry(
        val allow: Boolean,
        val permissions: Set<String>,
    )

    private val ACL_LINE = Regex("^\\s*\\d+:\\s+.+\\s+(allow|deny)\\s+([^\\s]+)\\s*$")
    private val NON_MUTATING_PARENT_PERMISSIONS = setOf(
        "read",
        "list",
        "search",
        "execute",
        "readattr",
        "readextattr",
        "readsecurity",
        "file_inherit",
        "directory_inherit",
        "limit_inherit",
        "only_inherit",
    )
    private const val LS = "/bin/ls"
    private const val CHMOD = "/bin/chmod"
    private const val MAX_COMMAND_OUTPUT_BYTES = 64 * 1024
    private const val COMMAND_READ_BUFFER_BYTES = 4096
    private const val COMMAND_TIMEOUT_SECONDS = 3L
    private const val COMMAND_READER_JOIN_MILLIS = 1_000L
}
