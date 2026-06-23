package profiles

/**
 * SSH 远程执行和本地进程工具函数。
 *
 * 不依赖 Project 上下文，可在任何 Gradle 脚本中直接调用。
 */

/** 通过 SSH 在远程主机上执行命令，实时打印输出，返回退出码。 */
fun remoteExec(host: String, user: String, cmd: String): Int {
    val pb = ProcessBuilder(
        "ssh", "-o", "ConnectTimeout=10", "-o", "StrictHostKeyChecking=accept-new",
        "$user@$host", cmd
    ).redirectErrorStream(true)
    val proc = pb.start()
    proc.inputStream.bufferedReader().forEachLine { println("  $it") }
    return proc.waitFor()
}

/** 通过 SSH 在远程主机上执行命令，仅返回是否成功（退出码 == 0）。 */
fun remoteCheck(host: String, user: String, cmd: String): Boolean {
    val pb = ProcessBuilder(
        "ssh", "-o", "ConnectTimeout=10", "-o", "StrictHostKeyChecking=accept-new",
        "$user@$host", cmd
    ).redirectErrorStream(true)
    val proc = pb.start()
    proc.inputStream.readBytes() // drain
    return proc.waitFor() == 0
}

/** 通过 SSH 在远程主机上执行命令，成功时返回标准输出文本，失败返回 null。 */
fun remoteOutput(host: String, user: String, cmd: String): String? {
    val pb = ProcessBuilder(
        "ssh", "-o", "ConnectTimeout=10", "-o", "StrictHostKeyChecking=accept-new",
        "$user@$host", cmd
    ).redirectErrorStream(true)
    val proc = pb.start()
    val output = proc.inputStream.bufferedReader().readText().trim()
    return if (proc.waitFor() == 0) output else null
}

/** 本地执行命令，实时打印输出，返回退出码。 */
fun localExec(vararg args: String): Int {
    val pb = ProcessBuilder(*args).redirectErrorStream(true)
    val proc = pb.start()
    proc.inputStream.bufferedReader().forEachLine { println("  $it") }
    return proc.waitFor()
}

/** 本地执行命令，静默丢弃输出，返回退出码。 */
fun localExecSilent(vararg args: String): Int {
    val pb = ProcessBuilder(*args).redirectErrorStream(true)
    val proc = pb.start()
    proc.inputStream.readBytes() // drain
    return proc.waitFor()
}

/**
 * 返回远程主机上可用的 docker compose 检测命令。
 * 优先使用 v2 (`docker compose`)，不可用时回退到 v1 (`docker-compose`)。
 *
 * @param systemdContext 如果为 true，输出中的 `$` 会转义为 `$$` 以适配 systemd unit 文件。
 */
fun dockerComposeCmd(systemdContext: Boolean = false): String {
    val esc = if (systemdContext) "\$\$" else "\$"
    return "${esc}(docker compose version &>/dev/null && echo docker compose || echo docker-compose)"
}

/** 生成 32 字符随机密码。 */
fun genPassword(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..32).map { chars.random() }.joinToString("")
}
