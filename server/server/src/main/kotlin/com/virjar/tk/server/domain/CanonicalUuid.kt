package com.virjar.tk.server.domain

import java.util.UUID

private const val UUID_TEXT_LENGTH = 36

/**
 * 严格的规范 UUID 判定：36 位文本且与 `UUID.fromString` 往返一致，
 * 拒绝大写、花括号等变体。各领域与持久化仓储共用同一规则，
 * 避免校验口径分散。
 */
fun canonicalUuidOrNull(value: String): String? =
    value.takeIf { it.length == UUID_TEXT_LENGTH }
        ?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
        ?.takeIf { it == value }
