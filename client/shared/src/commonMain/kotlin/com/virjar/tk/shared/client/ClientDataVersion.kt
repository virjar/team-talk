package com.virjar.tk.shared.client

/**
 * 本安装的数据边界取决于客户端自身的协议 major，不取决于某个服务器当前要求的版本。
 * 未标记的现有安装被纳入零号基线；minor 不清库，SQLDelight 负责逐版本迁移。
 */
internal fun prepareClientDataVersion(
    currentMajor: Int,
    readMarker: () -> String?,
    writeMarker: (String) -> Unit,
    resetOwnedData: () -> Unit,
): Boolean {
    require(currentMajor in 0..32767)
    val raw = readMarker()?.trim()
    val marker = raw?.let {
        val parts = it.split(':')
        require(parts.size == 2 && parts[0] in setOf("ready", "reset")) {
            "Client data version marker is invalid; existing data was retained"
        }
        val major = parts[1].toIntOrNull()
        require(major != null && major in 0..32767) { "Client data major is invalid; existing data was retained" }
        parts[0] to major
    }
    val storedMajor = marker?.second ?: 0
    check(storedMajor <= currentMajor) {
        "Client data belongs to protocol major $storedMajor; this client is major $currentMajor. " +
            "Install the matching or newer client; downgrading must not delete data."
    }
    val resetRequired = storedMajor < currentMajor || marker?.first == "reset"
    if (resetRequired) {
        // 先记录目标再删除。中断后只能由相同/更高 major 继续，旧客户端不能误用半清理的数据。
        writeMarker("reset:$currentMajor")
        resetOwnedData()
    }
    if (marker?.first != "ready" || storedMajor != currentMajor) writeMarker("ready:$currentMajor")
    return resetRequired
}
