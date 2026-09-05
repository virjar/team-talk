package com.virjar.tk.server.e2e

import java.util.concurrent.TimeUnit

internal data class RemoteTeamTalkSshTarget(
    val host: String,
    val user: String,
    val port: Int,
) {
    init {
        require(host.matches(Regex("[A-Za-z0-9._-]+"))) { "Remote restart host is invalid" }
        require(user.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))) {
            "Remote restart user is invalid"
        }
        require(port in 1..65535) { "Remote restart SSH port is invalid" }
    }

    companion object {
        fun fromSystemProperties(): RemoteTeamTalkSshTarget = RemoteTeamTalkSshTarget(
            host = requireSystemProperty("tk.e2e.deploy.host"),
            user = requireSystemProperty("tk.e2e.deploy.user"),
            port = requireSystemProperty("tk.e2e.deploy.port").toIntOrNull()
                ?: error("tk.e2e.deploy.port must be an integer"),
        )

        private fun requireSystemProperty(name: String): String =
            System.getProperty(name)?.takeIf(String::isNotBlank)
                ?: error("Missing required remote acceptance property: $name")
    }
}

internal data class TeamTalkServiceRestartEvidence(
    val beforeInvocationId: String,
    val beforeMainPid: Long,
    val afterInvocationId: String,
    val afterMainPid: Long,
)

/**
 * 一个刻意保持窄范围的远程验收夹具。它只能重启精确的 `teamtalk` systemd unit，
 * 不能执行调用方提供的命令，也不能改动测试主机的网络。
 */
internal class RemoteTeamTalkServiceRestart(
    private val target: RemoteTeamTalkSshTarget = RemoteTeamTalkSshTarget.fromSystemProperties(),
    private val execute: (List<String>, Long) -> String = ::executeRestartProcess,
) {
    fun restart(): TeamTalkServiceRestartEvidence {
        val output = execute(remoteTeamTalkRestartSshArguments(target), RESTART_TIMEOUT_MILLIS)
        return parseTeamTalkRestartEvidence(output)
    }

    private companion object {
        const val RESTART_TIMEOUT_MILLIS = 90_000L
    }
}

internal fun remoteTeamTalkRestartSshArguments(target: RemoteTeamTalkSshTarget): List<String> =
    listOf(
        "ssh",
        "-p", target.port.toString(),
        "-o", "BatchMode=yes",
        "-o", "ConnectTimeout=10",
        "-o", "ServerAliveInterval=5",
        "-o", "ServerAliveCountMax=3",
        "-o", "StrictHostKeyChecking=accept-new",
        "${target.user}@${target.host}",
        remoteTeamTalkRestartCommand(),
    )

internal fun remoteTeamTalkRestartCommand(): String =
    "set -eu; " +
        "systemctl is-active --quiet teamtalk; " +
        "before_invocation=\$(systemctl show teamtalk -p InvocationID --value); " +
        "before_pid=\$(systemctl show teamtalk -p MainPID --value); " +
        "test -n \"\$before_invocation\"; test \"\$before_pid\" -gt 0; " +
        "systemctl restart teamtalk; " +
        "systemctl is-active --quiet teamtalk; " +
        "after_invocation=\$(systemctl show teamtalk -p InvocationID --value); " +
        "after_pid=\$(systemctl show teamtalk -p MainPID --value); " +
        "test -n \"\$after_invocation\"; test \"\$after_pid\" -gt 0; " +
        "test \"\$before_invocation\" != \"\$after_invocation\"; " +
        "test \"\$before_pid\" != \"\$after_pid\"; " +
        "printf 'beforeInvocationId=%s\\nbeforeMainPid=%s\\n" +
        "afterInvocationId=%s\\nafterMainPid=%s\\n' " +
        "\"\$before_invocation\" \"\$before_pid\" \"\$after_invocation\" \"\$after_pid\""

internal fun parseTeamTalkRestartEvidence(output: String): TeamTalkServiceRestartEvidence {
    val expectedKeys = setOf(
        "beforeInvocationId",
        "beforeMainPid",
        "afterInvocationId",
        "afterMainPid",
    )
    val values = linkedMapOf<String, String>()
    output.lineSequence().forEach { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) return@forEach
        val key = line.substring(0, separator)
        if (key !in expectedKeys) return@forEach
        require(key !in values) { "Remote TeamTalk restart evidence contains duplicate $key" }
        values[key] = line.substring(separator + 1)
    }
    require(values.keys == expectedKeys) { "Remote TeamTalk restart evidence is incomplete" }

    val invocationPattern = Regex("[0-9a-fA-F]{32}")
    val beforeInvocationId = values.getValue("beforeInvocationId")
    val afterInvocationId = values.getValue("afterInvocationId")
    require(beforeInvocationId.matches(invocationPattern)) {
        "Remote TeamTalk pre-restart invocation id is malformed"
    }
    require(afterInvocationId.matches(invocationPattern)) {
        "Remote TeamTalk post-restart invocation id is malformed"
    }
    require(beforeInvocationId != afterInvocationId) {
        "Remote TeamTalk systemd invocation did not change"
    }

    val beforeMainPid = values.getValue("beforeMainPid").toLongOrNull()
        ?.takeIf { it > 0L }
        ?: error("Remote TeamTalk pre-restart MainPID is invalid")
    val afterMainPid = values.getValue("afterMainPid").toLongOrNull()
        ?.takeIf { it > 0L }
        ?: error("Remote TeamTalk post-restart MainPID is invalid")
    require(beforeMainPid != afterMainPid) { "Remote TeamTalk MainPID did not change" }
    return TeamTalkServiceRestartEvidence(
        beforeInvocationId = beforeInvocationId,
        beforeMainPid = beforeMainPid,
        afterInvocationId = afterInvocationId,
        afterMainPid = afterMainPid,
    )
}

private fun executeRestartProcess(arguments: List<String>, timeoutMillis: Long): String {
    require(arguments.firstOrNull() == "ssh") { "Remote restart must use SSH" }
    require(timeoutMillis > 0L) { "Remote restart timeout must be positive" }
    val process = ProcessBuilder(arguments)
        .redirectErrorStream(true)
        .start()
    val completed = try {
        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (interrupted: InterruptedException) {
        terminateRestartProcess(process)
        Thread.currentThread().interrupt()
        throw AssertionError("Remote TeamTalk restart was interrupted", interrupted)
    }
    if (!completed) {
        terminateRestartProcess(process)
        throw AssertionError("Remote TeamTalk restart timed out after ${timeoutMillis}ms")
    }
    val captured = process.inputStream.bufferedReader().use { reader ->
        reader.readText().takeLast(MAX_CAPTURED_OUTPUT_CHARS).trimEnd()
    }
    check(process.exitValue() == 0) {
        "Remote TeamTalk restart failed (exit=${process.exitValue()})" +
            captured.takeIf(String::isNotBlank)?.let { "\nOutput tail:\n$it" }.orEmpty()
    }
    return captured
}

private fun terminateRestartProcess(process: Process) {
    runCatching { process.destroy() }
    runCatching {
        if (!process.waitFor(2_000L, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(2_000L, TimeUnit.MILLISECONDS)
        }
    }
    runCatching { process.inputStream.close() }
}

private const val MAX_CAPTURED_OUTPUT_CHARS = 16 * 1024
