package com.virjar.tk.shared.agent

import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class AgentMcpAccessTest {
    @Test fun `grants persist as digests and exact retry cannot change or resurrect them`() = directory { data ->
        val created = AgentMcpAccess(data, OWNER, MASTER).use { access ->
            val result = access.create(ADMIN, fields())
            assertEquals(result, access.create(ADMIN, fields().toMutableMap().apply { this["tools"] = "messages,status,recv" }))
            failure(409) { access.create(ADMIN, fields().toMutableMap().apply { this["chatIds"] = "chat-b" }) }
            failure(400) { access.create(ADMIN, fields().toMutableMap().apply { this["token"] = MASTER }) }
            result
        }
        assertFalse(File(data, "mcp-grants.json").readText().contains(TOKEN))
        AgentMcpAccess(data, OWNER, MASTER).use { access ->
            val principal = access.authenticate("Bearer $TOKEN")
            assertEquals(created["grant"], access.describe(principal))
            failure(403) { access.describe(ADMIN) }
            failure(403) { access.list(principal) }
            access.revoke(ADMIN, "assistant")
            failure(401) { access.authenticate("Bearer $TOKEN") }
            failure(409) { access.create(ADMIN, fields()) }
        }
        AgentMcpAccess(data, OWNER, MASTER).use { failure(401) { it.authenticate("Bearer $TOKEN") } }
    }

    @Test fun `grants never follow the same uid into another dataset or deployment`() = directory { data ->
        AgentMcpAccess(data, OWNER, MASTER).use { it.create(ADMIN, fields()) }
        for (other in listOf(OWNER.copy(datasetId = "other"), OWNER.copy(deployment = "other"), OWNER.copy(uid = "other"))) {
            AgentMcpAccess(data, other, MASTER).use { access ->
                failure(401) { access.authenticate("Bearer $TOKEN") }
                failure(409) { access.create(ADMIN, fields()) }
            }
        }
    }

    @Test fun `read scopes reject missing or foreign chats and unmapped rest actions`() = directory { data ->
        AgentMcpAccess(data, OWNER, MASTER).use { access ->
            access.create(ADMIN, fields())
            val principal = access.authenticate("Bearer $TOKEN")
            for ((path, args) in listOf("/v1/messages" to emptyMap(), "/v1/messages" to mapOf("chatId" to "chat-b"),
                "/v1/send-text" to mapOf("chatId" to "chat-a"), "/v1/upload" to mapOf("path" to "secret"))) {
                failure(403) { access.begin(principal, path, args) }
            }
            val call = access.begin(principal, "/v1/messages", mapOf("chatId" to "chat-a"))
            access.recheck(call); access.finish(call, 200)
            assertTrue(access.allowsChat(principal, "chat-a")); assertFalse(access.allowsChat(principal, "chat-b"))
            val log = access.auditTail(ADMIN, 100).toString()
            assertTrue(log.contains("admitted")); assertTrue(log.contains("403"))
            assertFalse(log.contains(TOKEN)); assertFalse(log.contains("secret"))
        }
    }

    @Test fun `waiter admission stays bounded while revocation remains independent of the wait`() = directory { data ->
        var now = 1_000L
        AgentMcpAccess(data, OWNER, MASTER, clock = { now }).use { access ->
            access.create(ADMIN, fields())
            val principal = access.authenticate("Bearer $TOKEN")
            val calls = List(2) { access.begin(principal, "/v1/recv-wait", mapOf("chatId" to "chat-a")) }
            failure(429) { access.begin(principal, "/v1/recv-wait", mapOf("chatId" to "chat-a")) }
            access.finish(calls[0], 200)
            val third = access.begin(principal, "/v1/recv-wait", mapOf("chatId" to "chat-a"))
            access.finish(calls[1], 200); access.finish(third, 200)
            repeat(116) { val call = access.begin(principal, "/v1/status", emptyMap()); access.finish(call, 200) }
            failure(429) { access.begin(principal, "/v1/status", emptyMap()) }
            now += 60_000
            val call = access.begin(principal, "/v1/recv-wait", mapOf("chatId" to "chat-a"))
            access.revoke(ADMIN, "assistant")
            failure(401) { access.recheck(call) }
            access.finish(call, 401)
        }
    }

    @Test fun `unwritable private audit rejects new effects but keeps grants and prior records`() = directory { data ->
        AgentMcpAccess(data, OWNER, MASTER).use { access ->
            access.create(ADMIN, fields())
            val audit = File(data, "mcp-audit.jsonl").toPath()
            Files.setPosixFilePermissions(audit, PosixFilePermissions.fromString("rw-r--r--"))
            failure(503) { access.begin(access.authenticate("Bearer $TOKEN"), "/v1/status", emptyMap()) }
            Files.setPosixFilePermissions(audit, PosixFilePermissions.fromString("rw-------"))
            assertTrue(access.auditTail(ADMIN, 100).getValue("records").jsonArray.isNotEmpty())
        }
    }

    @Test fun `audit segments rotate within budget and recover only an incomplete final line`() = directory { data ->
        val files = AgentMcpFiles(AgentDataDirectoryPolicy.openRuntime(data))
        val audit = AgentMcpAudit(files, 1024)
        repeat(100) { audit.append(buildJsonObject { put("sequence", it); put("phase", "result") }) }
        assertTrue(files.size("mcp-audit.jsonl") <= 1024)
        assertTrue(files.size("mcp-audit.previous.jsonl") <= 1024)
        assertEquals(99, audit.tail(1).single().getValue("sequence").jsonPrimitive.int)
        files.append("mcp-audit.jsonl", "{\"interrupted\":".toByteArray())
        val recovered = AgentMcpAudit(files, 1024)
        assertEquals(99, recovered.tail(1).single().getValue("sequence").jsonPrimitive.int)
        recovered.append(buildJsonObject { put("sequence", 100) })
        assertEquals(listOf(99, 100), recovered.tail(2).map { it.getValue("sequence").jsonPrimitive.int })
    }

    @Test fun `MCP token input is dedicated bounded and private`() = directory { data ->
        assertEquals(mapOf("token-file" to "token"), parseMcpOptions(arrayOf("--token-file", "token")))
        assertFailsWith<CliException> { parseMcpOptions(arrayOf("--token", MASTER)) }
        val file = File(data, "mcp-token")
        AgentDataDirectoryPolicy.openRuntime(data)
        file.writeText(TOKEN)
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        assertEquals(TOKEN, readMcpTokenFile(file))
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        assertFailsWith<CliException> { readMcpTokenFile(file) }
    }


    @Test fun `revocation cannot relabel an already queued effect as a failed execution`() = directory { data ->
        AgentMcpAccess(data, OWNER, MASTER).use { access ->
            access.create(ADMIN, fields().toMutableMap().apply { this["tools"] = "send_text" })
            val call = access.begin(access.authenticate("Bearer $TOKEN"), "/v1/send-text", mapOf("chatId" to "chat-a"))
            access.revoke(ADMIN, "assistant")
            failure(401) { access.recheck(call) }
            access.finish(call, 202, "queued", 401)
            val record = access.auditTail(ADMIN, 1).getValue("records").jsonArray.single().jsonObject
            assertEquals(202, record.getValue("status").jsonPrimitive.int)
            assertEquals(401, record.getValue("responseStatus").jsonPrimitive.int)
            assertEquals("queued", record.getValue("outgoingState").jsonPrimitive.content)
        }
    }

    @Test fun `aggregate grant budget refuses growth without making the next startup unreadable`() = directory { data ->
        val chats = (1..128).joinToString(",") { it.toString().padStart(256, 'x') }
        var accepted = 0
        AgentMcpAccess(data, OWNER, MASTER).use { access ->
            for (number in 1..128) {
                val file = File(data, "mcp-grants.json")
                val previous = file.takeIf(File::exists)?.readBytes()
                try {
                    access.create(ADMIN, fields().toMutableMap().apply {
                        this["id"] = "grant-$number"
                        this["token"] = "token-" + number.toString().padStart(32, '0')
                        this["chatIds"] = chats
                    })
                    accepted++
                } catch (failure: AgentMcpAccessException) {
                    assertEquals(409, failure.status)
                    assertContentEquals(previous, file.readBytes())
                    break
                }
            }
            assertTrue(accepted in 1..127)
            assertTrue(File(data, "mcp-grants.json").length() <= 1024 * 1024)
        }
        AgentMcpAccess(data, OWNER, MASTER).use {
            assertEquals(accepted, it.list(ADMIN).getValue("grants").jsonArray.size)
        }
    }

    @Test fun `multiple grants cannot consume every HTTP worker with long polling`() = directory { data ->
        AgentMcpAccess(data, OWNER, MASTER).use { access ->
            val principals = (1..5).map { number ->
                val token = "token-" + number.toString().padStart(32, '0')
                access.create(ADMIN, fields().toMutableMap().apply { this["id"] = "grant-$number"; this["token"] = token })
                access.authenticate("Bearer $token")
            }
            val calls = principals.take(4).flatMap { principal ->
                List(2) { access.begin(principal, "/v1/recv-wait", mapOf("chatId" to "chat-a")) }
            }
            failure(429) { access.begin(principals.last(), "/v1/recv-wait", mapOf("chatId" to "chat-a")) }
            access.revoke(ADMIN, "grant-1")
            assertEquals(5, access.list(ADMIN).getValue("grants").jsonArray.size)
            calls.forEach { access.finish(it, 200) }
            access.finish(access.begin(principals.last(), "/v1/recv-wait", mapOf("chatId" to "chat-a")), 200)
        }
    }

    private fun directory(block: (File) -> Unit) {
        val root = createAgentSecurityTestRoot("mcp-access-")
        try { block(File(root, "data")) } finally { root.deleteRecursively() }
    }
    private fun failure(code: Int, block: () -> Unit) = assertEquals(code, assertFailsWith<AgentMcpAccessException> { block() }.status)
    companion object {
        internal val OWNER = AgentMcpOwner("deployment-one", "dataset-one", "uid-one")
        internal val ADMIN = AgentApiPrincipal.Administrator
        const val MASTER = "master-012345678901234567890123456789"
        const val TOKEN = "scoped-012345678901234567890123456789"
        fun fields() = mapOf("id" to "assistant", "token" to TOKEN, "tools" to "status,messages,recv", "chatIds" to "chat-a", "allChats" to "false")
    }
}
