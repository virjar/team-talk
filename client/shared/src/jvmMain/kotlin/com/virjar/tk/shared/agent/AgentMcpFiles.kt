package com.virjar.tk.shared.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/** Fixed private files inside an already validated agent owner; no caller-provided filesystem paths. */
internal class AgentMcpFiles(private val directory: AgentDataDirectory) {
    fun read(name: String, maximum: Int): ByteArray? {
        val file = path(name)
        if (!Files.exists(file, NOFOLLOW_LINKS)) return null
        validate(file)
        FileChannel.open(file, READ, NOFOLLOW_LINKS).use { channel ->
            require(channel.size() <= maximum) { "MCP local file exceeds its budget" }
            val buffer = ByteBuffer.allocate(channel.size().toInt())
            while (buffer.hasRemaining()) check(channel.read(buffer) >= 0) { "MCP local file changed during read" }
            check(channel.read(ByteBuffer.allocate(1)) < 0) { "MCP local file grew during read" }
            return buffer.array()
        }
    }

    fun replace(name: String, bytes: ByteArray) {
        val target = path(name)
        if (Files.exists(target, NOFOLLOW_LINKS)) validate(target)
        val temporary = Files.createTempFile(directory.root, ".mcp-", ".tmp", ATTRIBUTE)
        try {
            Files.setPosixFilePermissions(temporary, PERMISSIONS)
            validate(temporary)
            FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { channel ->
                write(channel, bytes); channel.force(true)
            }
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            forceDirectory()
        } finally { Files.deleteIfExists(temporary) }
    }

    fun append(name: String, bytes: ByteArray) {
        val target = path(name)
        if (!Files.exists(target, NOFOLLOW_LINKS)) {
            Files.createFile(target, ATTRIBUTE)
            Files.setPosixFilePermissions(target, PERMISSIONS)
            forceDirectory()
        }
        validate(target)
        FileChannel.open(target, WRITE, APPEND, NOFOLLOW_LINKS).use { channel -> write(channel, bytes); channel.force(true) }
    }

    fun rotate(current: String, previous: String) {
        val source = path(current)
        if (!Files.exists(source, NOFOLLOW_LINKS)) return
        validate(source)
        val target = path(previous)
        if (Files.exists(target, NOFOLLOW_LINKS)) validate(target)
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
        forceDirectory()
    }

    fun size(name: String): Long {
        val file = path(name)
        if (!Files.exists(file, NOFOLLOW_LINKS)) return 0L
        validate(file)
        return Files.size(file)
    }

    private fun validate(file: Path) {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink) { "MCP local storage must be a regular file" }
        require(Files.getPosixFilePermissions(file, NOFOLLOW_LINKS) == PERMISSIONS) { "MCP local storage must have mode 0600" }
        require(unix(file, "uid") == directory.ownerUid && unix(file, "gid") == directory.ownerGid) { "MCP local storage has the wrong owner" }
        require(unix(file, "nlink") == 1) { "MCP local storage cannot be hard-linked" }
    }
    private fun path(name: String): Path {
        require(name in NAMES)
        return directory.root.resolve(name)
    }
    private fun forceDirectory() { FileChannel.open(directory.root, READ).use { it.force(true) } }
    private fun unix(path: Path, field: String) = (Files.getAttribute(path, "unix:$field", NOFOLLOW_LINKS) as Number).toInt()
    private fun write(channel: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }
    private companion object {
        val PERMISSIONS = PosixFilePermissions.fromString("rw-------")
        val ATTRIBUTE = PosixFilePermissions.asFileAttribute(PERMISSIONS)
        val NAMES = setOf("mcp-grants.json", "mcp-audit.jsonl", "mcp-audit.previous.jsonl")
    }
}

/** Two bounded segments; admission is fsynced before a tool can produce effects. */
internal class AgentMcpAudit(private val files: AgentMcpFiles, private val segmentBytes: Int = 2 * 1024 * 1024) {
    private var recovered = false
    init { require(segmentBytes in 1024..2 * 1024 * 1024) }

    @Synchronized fun append(record: JsonObject) {
        recoverIncompleteTail()
        val bytes = (record.toString() + "\n").toByteArray()
        require(bytes.size <= segmentBytes) { "MCP audit record exceeds its budget" }
        if (files.size(CURRENT) + bytes.size > segmentBytes) files.rotate(CURRENT, PREVIOUS)
        try { files.append(CURRENT, bytes) }
        catch (failure: Exception) { recovered = false; throw failure }
    }

    @Synchronized fun tail(limit: Int): List<JsonObject> {
        require(limit in 1..500)
        recoverIncompleteTail()
        val records = ArrayDeque<JsonObject>()
        for (name in listOf(PREVIOUS, CURRENT)) {
            val data = files.read(name, segmentBytes) ?: continue
            data.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).forEach { line ->
                records.addLast(Json.parseToJsonElement(line).jsonObject)
                if (records.size > limit) records.removeFirst()
            }
        }
        return records.toList()
    }

    private fun recoverIncompleteTail() {
        if (recovered) return
        files.read(CURRENT, segmentBytes)?.let { bytes ->
            if (bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) {
                val boundary = bytes.indexOfLast { it == '\n'.code.toByte() } + 1
                files.replace(CURRENT, bytes.copyOf(boundary))
            }
        }
        recovered = true
    }
    private companion object {
        const val CURRENT = "mcp-audit.jsonl"
        const val PREVIOUS = "mcp-audit.previous.jsonl"
    }
}
