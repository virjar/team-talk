package com.virjar.tk.shared.agent

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.Reader

/** 本地 CLI/MCP 边界必须保持有界，即使其配置或 loopback 对端已经损坏。 */
internal object AgentClientIoPolicy {
    const val MAX_TOKEN_FILE_BYTES = 4 * 1024
    const val MAX_HTTP_RESPONSE_BYTES = 32 * 1024 * 1024

    fun endpoint(raw: String): AgentBindEndpoint = try {
        AgentBindPolicy.parse(raw)
    } catch (_: IllegalArgumentException) {
        throw CliException("agent API 地址必须是带端口的本机回环地址")
    }

    fun readTokenFile(file: File): String? {
        if (!file.exists()) return null
        val text = try {
            if (!file.isFile) throw CliException("token 配置路径不是普通文件")
            file.inputStream().use { input ->
                readBoundedUtf8(
                    input = input,
                    maximumBytes = MAX_TOKEN_FILE_BYTES,
                    tooLargeMessage = "token 配置文件过大",
                )
            }
        } catch (failure: CliException) {
            throw failure
        } catch (_: Exception) {
            throw CliException("token 配置文件无法读取")
        }
        val token = text.trim()
        if (token.isEmpty()) return null
        if (token.length > 256 || token.any(Char::isISOControl)) {
            throw CliException("token 配置文件内容无效")
        }
        return token
    }

    fun readHttpResponse(
        input: InputStream?,
        maximumBytes: Int = MAX_HTTP_RESPONSE_BYTES,
    ): String {
        require(maximumBytes in 1..MAX_HTTP_RESPONSE_BYTES) {
            "CLI response budget must be within the product maximum"
        }
        return input?.use { stream ->
            readBoundedUtf8(
                input = stream,
                maximumBytes = maximumBytes,
                tooLargeMessage = "agent 响应超过本地内存预算，请缩小分页范围",
            )
        } ?: "{}"
    }

    private fun readBoundedUtf8(
        input: InputStream,
        maximumBytes: Int,
        tooLargeMessage: String,
    ): String {
        val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_BYTES, maximumBytes))
        val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (total > maximumBytes - read) throw CliException(tooLargeMessage)
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private const val DEFAULT_BUFFER_BYTES = 8 * 1024
}

/**
 * tt-mcp 的有界 JSON-RPC 行分帧。
 *
 * `BufferedReader.readLine()` 没有大小限制，并且可能永久保留攻击者控制的缓冲区。
 * 这个 reader 会在上报超大帧之前先把它排空，因此下一个格式良好的请求
 * 仍然可以被处理，而不会使 stdio 分帧失去同步。
 */
internal class McpStdioLineReader(
    private val reader: Reader,
    private val maximumChars: Int = MAX_MCP_STDIO_REQUEST_CHARS,
) {
    private var pushedBack: Int? = null

    init {
        require(maximumChars > 0) { "MCP stdio request budget must be positive" }
    }

    fun readLine(): McpStdioLine {
        val value = StringBuilder(minOf(maximumChars, 8 * 1024))
        var oversized = false
        while (true) {
            val character = readCharacter()
            when (character) {
                -1 -> return when {
                    oversized -> McpStdioLine.Oversized
                    value.isEmpty() -> McpStdioLine.EndOfInput
                    else -> McpStdioLine.Value(value.toString())
                }
                '\n'.code -> return completed(value, oversized)
                '\r'.code -> {
                    val next = readCharacter()
                    if (next != -1 && next != '\n'.code) pushedBack = next
                    return completed(value, oversized)
                }
                else -> if (value.length < maximumChars) {
                    value.append(character.toChar())
                } else {
                    oversized = true
                }
            }
        }
    }

    private fun readCharacter(): Int = pushedBack?.also { pushedBack = null } ?: reader.read()

    private fun completed(value: StringBuilder, oversized: Boolean): McpStdioLine =
        if (oversized) McpStdioLine.Oversized else McpStdioLine.Value(value.toString())
}

internal sealed interface McpStdioLine {
    data object EndOfInput : McpStdioLine
    data object Oversized : McpStdioLine
    data class Value(val text: String) : McpStdioLine
}

internal const val MAX_MCP_STDIO_REQUEST_CHARS = 64 * 1024
