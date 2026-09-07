package com.virjar.tk.shared.client

import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.UserPrincipal

private const val MAX_DIAGNOSTIC_ENTRIES = 32
private const val MAX_DIAGNOSTIC_CHARS = 24 * 1024

/** 只在拒绝时格式化已用于判定的快照，不读取别的目录，也不主动记录或上传。 */
internal fun describeWindowsParentAclFailure(
    path: Path,
    currentOwner: UserPrincipal,
    actualOwner: UserPrincipal,
    trustedOwners: Set<UserPrincipal>,
    entries: List<AclEntry>,
): String {
    val names = AclDiagnosticNames(currentOwner)
    val decisions = entries.map { WindowsSafeParentAclPolicy.evaluateEntry(currentOwner, trustedOwners, it) }
    val rejected = decisions.indices.filter {
        decisions[it] == WindowsSafeParentAclPolicy.EntryDecision.REJECT_UNTRUSTED_MUTATION
    }
    // 先列拒绝项；即使前面有许多只读 ACE，导致失败的条目仍会出现在有界报告中。
    val shown = (rejected.asSequence() + entries.indices.asSequence()).distinct()
        .take(MAX_DIAGNOSTIC_ENTRIES).toList()
    val report = buildString {
        appendLine("Private data parent grants mutation rights to another Windows principal.")
        appendLine("ACL snapshot (current account/profile names masked):")
        appendLine("path=${names.path(path)}")
        appendLine("currentOwner=${names.principal(currentOwner)}")
        appendLine("actualOwner=${names.principal(actualOwner)}")
        appendLine("trustedOwners=${trustedOwners.take(16).joinToString { names.principal(it) }}")
        if (trustedOwners.size > 16) appendLine("trustedOwners omitted=${trustedOwners.size - 16}")
        appendLine("reason=Untrusted ALLOW entry can modify this existing directory")
        appendLine("entries=${entries.size}, rejected=${rejected.size}, shown=${shown.size}, omitted=${entries.size - shown.size}")
        shown.forEach { index ->
            val entry = entries[index]
            appendLine(
                "ACE[$index] principal=${names.principal(entry.principal())} " +
                    "type=${entry.type()} flags=${entry.flags().sortedBy { it.name }} " +
                    "permissions=${entry.permissions().sortedBy { it.name }} result=${decisions[index]}",
            )
        }
    }
    return if (report.length <= MAX_DIAGNOSTIC_CHARS) report
    else report.take(MAX_DIAGNOSTIC_CHARS) + "\n[ACL diagnostic truncated]\n"
}

/** 一份报告内用相等语义分配编号，遮蔽当前名称后仍可区分同名但不相等的主体。 */
private class AclDiagnosticNames(private val currentOwner: UserPrincipal) {
    private val principals = mutableListOf<UserPrincipal>()
    private val accountName = currentOwner.name
    private val accountLeaf = accountName.substringAfterLast('\\')

    fun principal(value: UserPrincipal): String {
        val existing = principals.indexOf(value)
        val number = if (existing >= 0) existing + 1 else {
            principals.add(value)
            principals.size
        }
        val name = if (value == currentOwner) "<current-user>" else maskAccount(value.name)
        return "p$number(${singleLine(name, 192)})"
    }

    fun path(value: Path): String {
        var text = value.toString()
        val userHome = System.getProperty("user.home").orEmpty()
        if (userHome.isNotBlank()) text = text.replace(userHome, "<user-home>", ignoreCase = true)
        text = text.replace(Regex("(?i)([a-z]:[\\\\/]+Users[\\\\/]+)[^\\\\/]+"), "$1<user>")
        return singleLine(maskAccount(text), 512)
    }

    private fun maskAccount(value: String): String {
        var text = value
        if (accountName.isNotBlank()) text = text.replace(accountName, "<current-user>", ignoreCase = true)
        if (accountLeaf.isNotBlank()) {
            val component = Regex("(?iu)(?<![\\p{L}\\p{N}_])${Regex.escape(accountLeaf)}(?![\\p{L}\\p{N}_])")
            text = text.replace(component, "<user>")
        }
        return text
    }

    private fun singleLine(value: String, limit: Int): String = value.take(limit)
        .map { if (it.isISOControl()) ' ' else it }.joinToString("")
}
