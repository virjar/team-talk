package com.virjar.tk.headless.agent

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

/** The token is durable before the HTTP request, so an unknown response can be retried with the same grant. */
internal object HeadlessTokenFile {
    fun createOrReuse(file: File, generate: () -> String = {
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    }): String {
        try {
            val path = file.toPath().toAbsolutePath().normalize()
            val parent = requireNotNull(path.parent)
            require(parent.toRealPath() == parent && Files.isDirectory(parent, NOFOLLOW_LINKS))
            val owner = parent.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
            require(Files.getOwner(parent, NOFOLLOW_LINKS) == owner)
            val permissions = Files.getPosixFilePermissions(parent, NOFOLLOW_LINKS)
            require(PosixFilePermission.GROUP_WRITE !in permissions && PosixFilePermission.OTHERS_WRITE !in permissions)
            if (Files.exists(path, NOFOLLOW_LINKS)) return readMcpTokenFile(path.toFile())
            val token = generate()
            require(token.matches(Regex("[A-Za-z0-9_-]{32,128}")))
            FileChannel.open(path, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
                val bytes = ByteBuffer.wrap("$token\n".toByteArray())
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            FileChannel.open(parent, READ).use { it.force(true) }
            return readMcpTokenFile(path.toFile())
        } catch (failure: CliException) { throw failure }
        catch (_: Exception) { throw CliException("Token file must be in an existing owned directory and must not overwrite another file") }
    }
}

internal fun runMcpManagement(cli: Cli, arguments: List<String>, flags: Map<String, String>): String {
    fun exactSize(size: Int) {
        if (arguments.size != size) throw CliException("Usage: tt mcp grant <id> | list | revoke <id> | audit")
    }
    return when (arguments.firstOrNull()) {
        "list" -> { exactSize(1); cli.get("/v1/mcp/grants") }
        "revoke" -> { exactSize(2); cli.post("/v1/mcp/revoke", mapOf("id" to arguments[1])) }
        "audit" -> {
            exactSize(1)
            val limit = flags["limit"]?.toIntOrNull() ?: if ("limit" in flags) 0 else 100
            if (limit !in 1..500) throw CliException("Audit limit must be between 1 and 500")
            cli.get("/v1/mcp/audit?limit=$limit")
        }
        "grant" -> {
            exactSize(2)
            val id = arguments[1]
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) throw CliException("Grant ID must contain 1–64 letters, digits, underscores or hyphens")
            val tools = flags["tools"]?.split(',')?.map(String::trim)?.toSet().orEmpty()
            if (tools.isEmpty() || !AgentMcpAccess.TOOLS.containsAll(tools)) throw CliException("--tools must explicitly list supported MCP tools")
            val allChats = flags["all-chats"] == "true"
            val chats = flags["chats"]?.split(',')?.map(String::trim)?.toSet().orEmpty()
            if (allChats == flags.containsKey("chats")) throw CliException("Specify either --chats <id,...> or --all-chats")
            if (chats.size > 128 || chats.any { runCatching { requireAgentChatId(it) }.isFailure }) throw CliException("Invalid chat allowlist")
            if (!allChats && "chat_with" in tools) throw CliException("chat_with requires --all-chats")
            val tokenFile = File(flags["token-file"] ?: throw CliException("--token-file is required"))
            val token = HeadlessTokenFile.createOrReuse(tokenFile)
            try {
                cli.post("/v1/mcp/grants", mapOf(
                    "id" to id, "token" to token, "tools" to tools.sorted().joinToString(","),
                    "chatIds" to chats.sorted().joinToString(","), "allChats" to allChats.toString(),
                ))
            } catch (failure: CliException) {
                throw CliException("${failure.message}; keep the token file and retry with the same grant ID, tools and chats")
            }
        }
        else -> throw CliException("Usage: tt mcp grant <id> | list | revoke <id> | audit")
    }
}
