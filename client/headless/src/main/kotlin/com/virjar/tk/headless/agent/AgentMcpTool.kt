package com.virjar.tk.headless.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class McpToolMethod { GET, POST }
internal enum class McpToolScope { ACCOUNT, REQUIRED_CHAT, OPTIONAL_CHAT, ALL_CHATS }

/** The MCP subset of agent REST. Names remain the persisted grant and wire identities. */
internal enum class AgentMcpTool(
    val wireName: String,
    val path: String,
    val method: McpToolMethod,
    val scope: McpToolScope,
    description: String,
    vararg properties: Pair<String, String>,
    required: List<String>? = null,
) {
    STATUS("status", "/v1/status", McpToolMethod.GET, McpToolScope.ACCOUNT, "获取 IM 连接状态与当前账号"),
    CONVERSATIONS("conversations", "/v1/conversations", McpToolMethod.GET, McpToolScope.ACCOUNT, "列出获准会话（含未读数/最后一条消息）"),
    FRIENDS("friends", "/v1/friends", McpToolMethod.GET, McpToolScope.ACCOUNT, "列出好友"),
    SEND_TEXT("send_text", "/v1/send-text", McpToolMethod.POST, McpToolScope.REQUIRED_CHAT, "发送纯文本消息",
        "chatId" to "目标会话 ID（可从 conversations 获取）", "text" to "消息文本",
        "clientMsgId" to "调用方生成并在重试时复用的稳定消息 ID",
        required = listOf("chatId", "text", "clientMsgId")),
    SEND_MARKDOWN("send_markdown", "/v1/send-rich", McpToolMethod.POST, McpToolScope.REQUIRED_CHAT, "发送 markdown 富文本消息",
        "chatId" to "目标会话 ID", "markdown" to "markdown 内容",
        "clientMsgId" to "调用方生成并在重试时复用的稳定消息 ID",
        required = listOf("chatId", "markdown", "clientMsgId")),
    SEND_FILE("send_file", "/v1/send-file", McpToolMethod.POST, McpToolScope.REQUIRED_CHAT, "上传并持久排队发送文件",
        "chatId" to "目标会话 ID", "path" to "agent outgoing 目录内的文件路径",
        "clientMsgId" to "调用方生成并在重试时复用的稳定消息 ID",
        required = listOf("chatId", "path", "clientMsgId")),
    OUTGOING_STATUS("outgoing_status", "/v1/outgoing", McpToolMethod.GET, McpToolScope.REQUIRED_CHAT, "查询持久发送回执",
        "chatId" to "目标会话 ID", "clientMsgId" to "发送时使用的稳定消息 ID",
        required = listOf("chatId", "clientMsgId")),
    RECV("recv", "/v1/recv-wait", McpToolMethod.GET, McpToolScope.OPTIONAL_CHAT, "按全局事件游标等待新消息（长轮询）",
        "chatId" to "可选，只等该会话", "timeout" to "等待秒数默认 10", "afterEventId" to "可选，全局事件游标",
        required = emptyList()),
    MESSAGES("messages", "/v1/messages", McpToolMethod.GET, McpToolScope.OPTIONAL_CHAT, "按全局事件游标读取持久消息",
        "limit" to "条数默认 20", "chatId" to "可选，会话过滤", "afterEventId" to "可选，全局事件游标",
        required = emptyList()),
    HISTORY("history", "/v1/history", McpToolMethod.POST, McpToolScope.REQUIRED_CHAT, "拉取服务端历史消息",
        "chatId" to "会话 ID", "fromSeq" to "起始 seq（0 为最新）", "limit" to "条数"),
    SEARCH_USERS("search_users", "/v1/users-search", McpToolMethod.POST, McpToolScope.ACCOUNT, "按关键词搜索用户",
        "keyword" to "用户名/昵称关键词"),
    CHAT_WITH("chat_with", "/v1/chat-personal", McpToolMethod.POST, McpToolScope.ALL_CHATS, "与用户建立私聊会话，返回 chatId",
        "targetUid" to "目标用户 uid"),
    MARK_READ("mark_read", "/v1/mark-read", McpToolMethod.POST, McpToolScope.REQUIRED_CHAT, "标记会话已读",
        "chatId" to "会话 ID", "readSeq" to "已读水位", required = listOf("chatId", "readSeq")),
    REVOKE("revoke", "/v1/revoke", McpToolMethod.POST, McpToolScope.REQUIRED_CHAT, "撤回自己发的消息",
        "chatId" to "会话 ID", "serverSeq" to "消息 seq", required = listOf("chatId", "serverSeq"));

    val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            properties.forEach { (name, description) ->
                put(name, buildJsonObject { put("type", "string"); put("description", description) })
            }
        })
        val requiredProperties = required ?: properties.take(1).map { it.first }
        if (requiredProperties.isNotEmpty()) put("required", buildJsonArray {
            requiredProperties.forEach { add(JsonPrimitive(it)) }
        })
    }
    val definition = buildJsonObject {
        put("name", wireName)
        put("description", description)
        put("inputSchema", inputSchema)
    }

    companion object {
        val byName = entries.associateBy { it.wireName }
        val byPath = entries.associateBy { it.path }
        val names: Set<String> = byName.keys
    }
}
