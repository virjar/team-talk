package com.virjar.tk.server.domain.telemetry

import com.virjar.tk.protocol.telemetry.ClientTelemetryLimits
import kotlin.text.CharCategory

/**
 * 针对遥测 V1 所准入的仅有的自由格式字段的服务器侧纵深防御。
 *
 * 官方客户端在上传前会清洗，但一个已认证设备仍然是一个不可信的 HTTP 对端。因此事件
 * 存储与管理 API 绝不能依赖客户端侧清洗器作为其隐私边界。
 */
internal fun sanitizeTelemetryDiagnosticText(
    raw: String,
    maxChars: Int = ClientTelemetryLimits.MAX_MESSAGE_CHARS,
): String = sanitizeTelemetryText(raw, maxChars, redactOpaque = true, emptyValue = "empty diagnostic")

/** 运行时元数据可搜索，但绝不需要携带凭证、PII、URL 或路径。 */
internal fun sanitizeTelemetryRuntimeText(raw: String, maxChars: Int): String {
    val sanitized = sanitizeTelemetryText(raw, maxChars, redactOpaque = true, emptyValue = "unknown")
    return if (TELEMETRY_REDACTION_MARKER.containsMatchIn(sanitized)) "unknown" else sanitized
}

/** 稳定的代码/类字段保留代码形态，同时拒绝身份或不透明秘密材料。 */
internal fun sanitizeTelemetryStableText(
    raw: String,
    maxChars: Int = ClientTelemetryLimits.MAX_NAME_CHARS,
    fallback: String = "redacted",
): String {
    val sanitized = sanitizeTelemetryText(raw, maxChars, redactOpaque = true, emptyValue = fallback)
    if (TELEMETRY_REDACTION_MARKER.containsMatchIn(sanitized)) return fallback
    return sanitized
        .map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' || char == ':' || char == '$') {
                char
            } else {
                '_'
            }
        }
        .joinToString("")
        .trim('_')
        .take(maxChars)
        .ifBlank { fallback }
}

private fun sanitizeTelemetryText(
    raw: String,
    maxChars: Int,
    redactOpaque: Boolean,
    emptyValue: String,
): String {
    require(maxChars > 0)
    var value = raw
        .replace('\r', ' ')
        .replace('\n', ' ')
        // 在脱敏之前归一化不可见格式字符，使零宽分隔符无法拆开一个
        // 本可识别的凭证、手机号、URL 或路径。
        .filterNot { it.category == CharCategory.FORMAT }
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(TELEMETRY_URL, "[url-redacted]")
        .replace(TELEMETRY_AUTHORIZATION, "[credential-redacted]")
        .replace(TELEMETRY_STRUCTURED_CREDENTIAL, "[credential-redacted]")
        .replace(TELEMETRY_BEARER, "[credential-redacted]")
        .replace(TELEMETRY_JWT, "[credential-redacted]")
        .replace(TELEMETRY_CREDENTIAL, "[credential-redacted]")
        .replace(TELEMETRY_EMAIL, "[email-redacted]")
        .replace(TELEMETRY_CHINA_PHONE, "[phone-redacted]")
        .replace(TELEMETRY_INTERNATIONAL_PHONE, "[phone-redacted]")
        .replace(TELEMETRY_UNC_PATH, "[path-redacted]")
        .replace(TELEMETRY_WINDOWS_PATH, "[path-redacted]")
        .replace(TELEMETRY_RELATIVE_PATH, "[path-redacted]")
        .replace(TELEMETRY_UNIX_PATH, "[path-redacted]")
        .replace(TELEMETRY_UUID, "[id-redacted]")
    if (redactOpaque) value = value.replace(TELEMETRY_OPAQUE_SECRET, "[opaque-redacted]")
    value = value
        .replace(TELEMETRY_WHITESPACE, " ")
        .trim()
    if (value.isEmpty()) value = emptyValue
    return value.take(maxChars)
}

internal fun sanitizeTelemetryStackFileName(raw: String): String =
    sanitizeTelemetryDiagnosticText(
        raw.substringAfterLast('/').substringAfterLast('\\'),
        ClientTelemetryLimits.MAX_STACK_FIELD_CHARS,
    )

private val TELEMETRY_URL = Regex("(?i)\\b(?:https?|ftp|wss?|file|content)://\\S+")
private val TELEMETRY_STRUCTURED_CREDENTIAL = Regex(
    """(?i)["']?(?:password|passwd|token|authorization|cookie|secret|api[_-]?key|access[_-]?key|""" +
        """private[_-]?key|client[_-]?secret|refresh[_-]?token|access[_-]?token|session[_-]?token)["']?""" +
        """\s*[:=]\s*(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,}\]]+)""",
)
private val TELEMETRY_AUTHORIZATION = Regex(
    "(?i)\\bauthorization\\b\\s*[:=]?\\s*\\S+(?:\\s+\\S+)?",
)
private val TELEMETRY_CREDENTIAL = Regex(
    "(?i)\\b(?:password|passwd|token|cookie|secret|api[_-]?key|access[_-]?key|private[_-]?key|" +
        "client[_-]?secret|refresh[_-]?token|access[_-]?token|session[_-]?token)\\b" +
        "\\s*(?::|=|\\bis\\b)?\\s*\\S+",
)
private val TELEMETRY_BEARER = Regex("(?i)\\bbearer\\s+\\S+")
private val TELEMETRY_JWT = Regex(
    "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])",
)
private val TELEMETRY_EMAIL = Regex("(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9._%+-])")
private val TELEMETRY_CHINA_PHONE = Regex(
    "(?<!\\d)(?:\\+?86[ -]?)?1[3-9](?:[ -]?\\d){9}(?!\\d)",
)
private val TELEMETRY_INTERNATIONAL_PHONE = Regex(
    "(?<![A-Za-z0-9])(?:\\+|00)[1-9](?:[ ()-]*\\d){7,14}(?!\\d)",
)
private val TELEMETRY_UNC_PATH = Regex(
    """(?<![A-Za-z0-9_])\\\\[^\s\\]+\\[^\s\\]+(?:\\[^\s\\]+)*""",
)
private val TELEMETRY_WINDOWS_PATH = Regex("[A-Za-z]:\\\\(?:[^\\s\\\\]+\\\\)*[^\\s\\\\]+")
private val TELEMETRY_RELATIVE_PATH = Regex(
    """(?<![A-Za-z0-9_.])\.\.?[/\\](?:[^\s/\\]+[/\\])*[^\s/\\]+""",
)
private val TELEMETRY_UNIX_PATH = Regex(
    """(?<![\p{L}\p{N}])/(?!/)(?:[^\s/]+/)*[^\s/]+""",
)
private val TELEMETRY_UUID = Regex(
    "(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}(?![0-9a-f])",
)
private val TELEMETRY_OPAQUE_SECRET = Regex(
    "(?<![A-Za-z0-9_+/=-])(?=[A-Za-z0-9_+/=-]{24,}(?![A-Za-z0-9_+/=-]))(?=[A-Za-z0-9_+/=-]*[A-Za-z])(?=[A-Za-z0-9_+/=-]*\\d)[A-Za-z0-9_+/=-]+",
)
private val TELEMETRY_REDACTION_MARKER = Regex("\\[(?:url|credential|email|phone|path|id|opaque)-redacted]")
private val TELEMETRY_WHITESPACE = Regex("\\s+")
