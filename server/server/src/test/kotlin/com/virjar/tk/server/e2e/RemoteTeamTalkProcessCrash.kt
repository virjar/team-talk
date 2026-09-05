package com.virjar.tk.server.e2e

import com.virjar.tk.server.infra.storage.Core02ProcessCrashBoundary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class RemoteTeamTalkProcessCrashConfiguration(
    val sshTarget: RemoteTeamTalkSshTarget,
    val deployPath: String,
) {
    init {
        requireCanonicalRemoteTeamTalkDeployPath(deployPath)
    }

    companion object {
        fun fromSystemProperties(): RemoteTeamTalkProcessCrashConfiguration =
            RemoteTeamTalkProcessCrashConfiguration(
                sshTarget = RemoteTeamTalkSshTarget.fromSystemProperties(),
                deployPath = System.getProperty("tk.e2e.deploy.path")
                    ?.takeIf(String::isNotBlank)
                    ?: error(
                        "Missing required remote acceptance property: tk.e2e.deploy.path",
                    ),
            )
    }
}

internal data class TeamTalkProcessCrashEvidence(
    val chatId: String,
    val clientMsgId: String,
    val stage: String,
    val beforeInvocationId: String,
    val beforeMainPid: Long,
    val afterInvocationId: String,
    val afterMainPid: Long,
)

internal enum class TeamTalkProcessCrashCleanupState(val wireValue: String) {
    ABSENT("absent"),
    DISARMED("disarmed"),
    KILLED_HIT("killed-hit"),
    KILLED_CLAIM("killed-claim"),
    OBSERVED_RESTARTED("observed-restarted"),
}

/**
 * 针对单一已布防消息身份的窄 CORE-02 验收夹具。它只能在已配置的 TeamTalk 部署目录下
 * 写入标记，并且只有在打包进程标记与当前 unit 身份匹配后，才能对精确的 `teamtalk`
 * systemd unit 的主进程发送 SIGKILL。
 */
internal class RemoteTeamTalkProcessCrash(
    private val boundary: Core02ProcessCrashBoundary,
    private val configuration: RemoteTeamTalkProcessCrashConfiguration =
        RemoteTeamTalkProcessCrashConfiguration.fromSystemProperties(),
    private val execute: (List<String>, Long) -> String = ::executeProcessCrashFixture,
) {
    private val armedChatIds = ConcurrentHashMap<String, String>()

    fun arm(chatId: String, clientMsgId: String) {
        requireCore02ChatId(chatId)
        requireCore02ClientMsgId(clientMsgId, boundary)
        check(armedChatIds.putIfAbsent(clientMsgId, chatId) == null) {
            "CORE-02 message is already armed: $clientMsgId"
        }
        execute(
            remoteTeamTalkProcessCrashArmSshArguments(
                boundary,
                configuration.sshTarget,
                configuration.deployPath,
                chatId,
                clientMsgId,
            ),
            ARM_TIMEOUT_MILLIS,
        )
    }

    fun awaitHitKillAndRestart(clientMsgId: String): TeamTalkProcessCrashEvidence {
        requireCore02ClientMsgId(clientMsgId, boundary)
        val chatId = checkNotNull(armedChatIds[clientMsgId]) {
            "CORE-02 message was not armed by this fixture: $clientMsgId"
        }
        val output = execute(
            remoteTeamTalkProcessCrashAwaitSshArguments(
                boundary,
                configuration.sshTarget,
                configuration.deployPath,
                chatId,
                clientMsgId,
            ),
            KILL_AND_RESTART_TIMEOUT_MILLIS,
        )
        return parseTeamTalkProcessCrashEvidence(output, boundary, chatId, clientMsgId)
    }

    /**
     * 严格应急恢复。布防身份会一直保留到远端进程被证明处于活动状态且所有标记都被移除，
     * 因此调用方可以在不猜测 chatId、不扩大远端命令的前提下重试失败的清理。
     */
    fun cleanup(clientMsgId: String) {
        requireCore02ClientMsgId(clientMsgId, boundary)
        val chatId = checkNotNull(armedChatIds[clientMsgId]) {
            "CORE-02 message was not armed by this fixture: $clientMsgId"
        }
        val output = execute(
            remoteTeamTalkProcessCrashCleanupSshArguments(
                boundary,
                configuration.sshTarget,
                configuration.deployPath,
                chatId,
                clientMsgId,
            ),
            CLEANUP_TIMEOUT_MILLIS,
        )
        parseTeamTalkProcessCrashCleanupState(output)
        armedChatIds.remove(clientMsgId, chatId)
    }

    private companion object {
        const val ARM_TIMEOUT_MILLIS = 30_000L
        const val KILL_AND_RESTART_TIMEOUT_MILLIS = 145_000L
        const val CLEANUP_TIMEOUT_MILLIS = 85_000L
    }
}

internal fun remoteTeamTalkProcessCrashArmSshArguments(
    boundary: Core02ProcessCrashBoundary,
    target: RemoteTeamTalkSshTarget,
    deployPath: String,
    chatId: String,
    clientMsgId: String,
): List<String> = fixedProcessCrashSshArguments(
    target,
    remoteTeamTalkProcessCrashArmCommand(boundary, deployPath, chatId, clientMsgId),
)

internal fun remoteTeamTalkProcessCrashAwaitSshArguments(
    boundary: Core02ProcessCrashBoundary,
    target: RemoteTeamTalkSshTarget,
    deployPath: String,
    chatId: String,
    clientMsgId: String,
): List<String> = fixedProcessCrashSshArguments(
    target,
    remoteTeamTalkProcessCrashAwaitCommand(boundary, deployPath, chatId, clientMsgId),
)

internal fun remoteTeamTalkProcessCrashCleanupSshArguments(
    boundary: Core02ProcessCrashBoundary,
    target: RemoteTeamTalkSshTarget,
    deployPath: String,
    chatId: String,
    clientMsgId: String,
): List<String> = fixedProcessCrashSshArguments(
    target,
    remoteTeamTalkProcessCrashCleanupCommand(boundary, deployPath, chatId, clientMsgId),
)

private fun fixedProcessCrashSshArguments(
    target: RemoteTeamTalkSshTarget,
    remoteCommand: String,
): List<String> = listOf(
    "ssh",
    "-p", target.port.toString(),
    "-o", "BatchMode=yes",
    "-o", "ConnectTimeout=10",
    "-o", "ServerAliveInterval=5",
    "-o", "ServerAliveCountMax=3",
    "-o", "StrictHostKeyChecking=accept-new",
    "${target.user}@${target.host}",
    remoteCommand,
)

internal fun remoteTeamTalkProcessCrashArmCommand(
    boundary: Core02ProcessCrashBoundary,
    deployPath: String,
    chatId: String,
    clientMsgId: String,
): String {
    requireCanonicalRemoteTeamTalkDeployPath(deployPath)
    requireCore02ChatId(chatId)
    requireCore02ClientMsgId(clientMsgId, boundary)
    val directory = "$deployPath/data/acceptance/core02"
    return remoteTeamTalkDeployPathGuard(deployPath) +
        "umask 077; data=$deployPath/data; " +
        "test -d \"\$data\"; test ! -L \"\$data\"; " +
        "test \"\$(readlink -f -- \"\$data\")\" = \"\$data\"; " +
        "acceptance=\"\$data/acceptance\"; " +
        "if ! test -e \"\$acceptance\"; then " +
        "mkdir -- \"\$acceptance\" 2>/dev/null || test -d \"\$acceptance\"; fi; " +
        "test -d \"\$acceptance\"; test ! -L \"\$acceptance\"; " +
        "test \"\$(readlink -f -- \"\$acceptance\")\" = \"\$acceptance\"; " +
        "directory=$directory; " +
        "if ! test -e \"\$directory\"; then " +
        "mkdir -- \"\$directory\" 2>/dev/null || test -d \"\$directory\"; fi; " +
        "test -d \"\$directory\"; test ! -L \"\$directory\"; " +
        "test \"\$(readlink -f -- \"\$directory\")\" = \"\$directory\"; " +
        processCrashRemoveMarkersCommand(clientMsgId) +
        "temporary=\"\$directory/$clientMsgId.arm-\$\$.tmp\"; " +
        "printf '%s' '$chatId' > \"\$temporary\"; " +
        "mv -f -- \"\$temporary\" \"\$directory/$clientMsgId.arm\""
}

internal fun remoteTeamTalkProcessCrashAwaitCommand(
    boundary: Core02ProcessCrashBoundary,
    deployPath: String,
    chatId: String,
    clientMsgId: String,
): String {
    requireCanonicalRemoteTeamTalkDeployPath(deployPath)
    requireCore02ChatId(chatId)
    requireCore02ClientMsgId(clientMsgId, boundary)
    val directory = "$deployPath/data/acceptance/core02"
    return remoteTeamTalkDeployPathGuard(deployPath) +
        "directory=$directory; marker=\"\$directory/$clientMsgId.hit\"; " +
        "test -d \"\$directory\"; test ! -L \"\$directory\"; " +
        "test \"\$(readlink -f -- \"\$directory\")\" = \"\$directory\"; " +
        "marker_deadline=\$((\$(date +%s) + $MARKER_WAIT_DEADLINE_SECONDS)); " +
        "while ! test -f \"\$marker\"; do " +
        "test \"\$(date +%s)\" -lt \"\$marker_deadline\" || { " +
        "printf '%s\\n' 'Timed out waiting for CORE-02 hit marker' >&2; exit 124; }; " +
        "sleep 1; done; " +
        "test ! -L \"\$marker\"; " +
        strictProcessCrashHitMarkerCommand(boundary, chatId, clientMsgId) +
        "marker_pid=\$(sed -n 's/^pid=//p' \"\$marker\"); " +
        "marker_invocation=\$(sed -n 's/^invocationId=//p' \"\$marker\"); " +
        "test \"\$marker_pid\" -gt 0; " +
        "systemctl is-active --quiet teamtalk; " +
        "before_invocation=\$(systemctl show teamtalk -p InvocationID --value); " +
        "before_pid=\$(systemctl show teamtalk -p MainPID --value); " +
        "test -n \"\$before_invocation\"; test \"\$before_pid\" -gt 0; " +
        "test \"\$marker_invocation\" = \"\$before_invocation\"; " +
        "test \"\$marker_pid\" = \"\$before_pid\"; " +
        "kill_invocation=\$(systemctl show teamtalk -p InvocationID --value); " +
        "kill_pid=\$(systemctl show teamtalk -p MainPID --value); " +
        "test \"\$kill_invocation\" = \"\$marker_invocation\"; " +
        "test \"\$kill_pid\" = \"\$marker_pid\"; " +
        "systemctl kill --kill-who=main --signal=KILL teamtalk; " +
        "restart_deadline=\$((\$(date +%s) + $RESTART_WAIT_DEADLINE_SECONDS)); " +
        "after_invocation=; after_pid=0; " +
        "while :; do " +
        "if systemctl is-active --quiet teamtalk; then " +
        "after_invocation=\$(systemctl show teamtalk -p InvocationID --value); " +
        "after_pid=\$(systemctl show teamtalk -p MainPID --value); " +
        "case \"\$after_pid\" in ''|*[!0-9]*) after_pid=0 ;; esac; " +
        "if printf '%s\\n' \"\$after_invocation\" | " +
        "grep -Eq '^[0-9a-fA-F]{32}\$' && test \"\$after_pid\" -gt 0 && " +
        "test \"\$after_invocation\" != \"\$before_invocation\" && " +
        "test \"\$after_pid\" != \"\$before_pid\"; then break; fi; fi; " +
        "test \"\$(date +%s)\" -lt \"\$restart_deadline\" || { " +
        "printf '%s\\n' 'Timed out waiting for TeamTalk restart' >&2; exit 124; }; " +
        "sleep 1; done; " +
        "printf 'chatId=%s\\nclientMsgId=%s\\nstage=%s\\n" +
        "beforeInvocationId=%s\\nbeforeMainPid=%s\\n" +
        "afterInvocationId=%s\\nafterMainPid=%s\\n' " +
        "'$chatId' '$clientMsgId' '${boundary.stage.name}' " +
        "\"\$before_invocation\" \"\$before_pid\" \"\$after_invocation\" \"\$after_pid\""
}

internal fun remoteTeamTalkProcessCrashCleanupCommand(
    boundary: Core02ProcessCrashBoundary,
    deployPath: String,
    chatId: String,
    clientMsgId: String,
): String {
    requireCanonicalRemoteTeamTalkDeployPath(deployPath)
    requireCore02ChatId(chatId)
    requireCore02ClientMsgId(clientMsgId, boundary)
    val directory = "$deployPath/data/acceptance/core02"
    return remoteTeamTalkDeployPathGuard(deployPath) +
        "data=$deployPath/data; test -d \"\$data\"; test ! -L \"\$data\"; " +
        "test \"\$(readlink -f -- \"\$data\")\" = \"\$data\"; " +
        "deadline=\$((\$(date +%s) + $EMERGENCY_RECOVERY_DEADLINE_SECONDS)); " +
        "acceptance=\"\$data/acceptance\"; " +
        "if test -L \"\$acceptance\"; then exit 1; fi; " +
        "if ! test -e \"\$acceptance\"; then " +
        waitForActiveTeamTalkProcessCommand() +
        "printf 'cleanupState=absent\\n'; exit 0; fi; " +
        "test -d \"\$acceptance\"; " +
        "test \"\$(readlink -f -- \"\$acceptance\")\" = \"\$acceptance\"; " +
        "directory=$directory; " +
        "if test -L \"\$directory\"; then exit 1; fi; " +
        "if ! test -e \"\$directory\"; then " +
        waitForActiveTeamTalkProcessCommand() +
        "printf 'cleanupState=absent\\n'; exit 0; fi; " +
        "test -d \"\$directory\"; " +
        "test ! -L \"\$directory\"; " +
        "test \"\$(readlink -f -- \"\$directory\")\" = \"\$directory\"; " +
        "arm=\"\$directory/$clientMsgId.arm\"; " +
        "claim=\"\$directory/$clientMsgId.claim\"; " +
        "marker=\"\$directory/$clientMsgId.hit\"; " +
        "for candidate in \"\$arm\" \"\$claim\" \"\$marker\"; do " +
        "if test -e \"\$candidate\" || test -L \"\$candidate\"; then " +
        "test -f \"\$candidate\"; test ! -L \"\$candidate\"; fi; done; " +
        "cleanup_state=absent; needs_kill=0; needs_wait=0; " +
        "before_invocation=; before_pid=0; after_invocation=; after_pid=0; " +
        "if test -f \"\$marker\"; then " +
        strictProcessCrashHitMarkerCommand(boundary, chatId, clientMsgId) +
        "marker_pid=\$(sed -n 's/^pid=//p' \"\$marker\"); " +
        "marker_invocation=\$(sed -n 's/^invocationId=//p' \"\$marker\"); " +
        "test \"\$marker_pid\" -gt 0; " +
        "before_invocation=\$marker_invocation; before_pid=\$marker_pid; needs_wait=1; " +
        "cleanup_state=observed-restarted; " +
        "if systemctl is-active --quiet teamtalk; then " +
        "current_invocation=\$(systemctl show teamtalk -p InvocationID --value); " +
        "current_pid=\$(systemctl show teamtalk -p MainPID --value); " +
        "if test \"\$current_invocation\" = \"\$marker_invocation\" && " +
        "test \"\$current_pid\" = \"\$marker_pid\"; then " +
        "needs_kill=1; cleanup_state=killed-hit; fi; fi; " +
        "elif test -f \"\$claim\"; then " +
        exactProcessCrashIdentityFileCommand("claim", chatId) +
        "systemctl is-active --quiet teamtalk; " +
        "before_invocation=\$(systemctl show teamtalk -p InvocationID --value); " +
        "before_pid=\$(systemctl show teamtalk -p MainPID --value); " +
        "test \"\$(printf '%s\\n' \"\$before_invocation\" | " +
        "grep -Ec '^[0-9a-fA-F]{32}\$')\" -eq 1; " +
        "case \"\$before_pid\" in ''|*[!0-9]*) exit 1 ;; esac; " +
        "test \"\$before_pid\" -gt 0; needs_kill=1; needs_wait=1; " +
        "cleanup_state=killed-claim; " +
        "elif test -f \"\$arm\"; then " +
        exactProcessCrashIdentityFileCommand("arm", chatId) +
        "cleanup_state=disarmed; fi; " +
        "if test \"\$needs_kill\" -eq 1; then " +
        "kill_invocation=\$(systemctl show teamtalk -p InvocationID --value); " +
        "kill_pid=\$(systemctl show teamtalk -p MainPID --value); " +
        "if test \"\$kill_invocation\" = \"\$before_invocation\" && " +
        "test \"\$kill_pid\" = \"\$before_pid\"; then " +
        "systemctl kill --kill-who=main --signal=KILL teamtalk; " +
        "else cleanup_state=observed-restarted; fi; fi; " +
        "if test \"\$needs_wait\" -eq 1; then " +
        waitForChangedTeamTalkProcessCommand() +
        "else " +
        waitForActiveTeamTalkProcessCommand() +
        "fi; " +
        processCrashRemoveMarkersCommand(clientMsgId) +
        "printf 'cleanupState=%s\\n' \"\$cleanup_state\""
}

private fun strictProcessCrashHitMarkerCommand(
    boundary: Core02ProcessCrashBoundary,
    chatId: String,
    clientMsgId: String,
): String =
    "test \"\$(awk 'END { print NR }' \"\$marker\")\" -eq 5; " +
        "test \"\$(grep -Ec '^pid=[0-9]+\$' \"\$marker\")\" -eq 1; " +
        "test \"\$(grep -Ec '^invocationId=[0-9a-fA-F]{32}\$' \"\$marker\")\" -eq 1; " +
        "test \"\$(grep -Fxc -- 'chatId=$chatId' \"\$marker\")\" -eq 1; " +
        "test \"\$(grep -Fxc -- 'clientMsgId=$clientMsgId' \"\$marker\")\" -eq 1; " +
        "test \"\$(grep -Fxc -- 'stage=${boundary.stage.name}' \"\$marker\")\" -eq 1; "

private fun exactProcessCrashIdentityFileCommand(variableName: String, chatId: String): String =
    "test \"\$(wc -c < \"\$$variableName\" | tr -d '[:space:]')\" -eq ${chatId.length}; " +
        "test \"\$(cat \"\$$variableName\")\" = '$chatId'; "

private fun waitForChangedTeamTalkProcessCommand(): String =
    "while :; do " +
        "if systemctl is-active --quiet teamtalk; then " +
        "after_invocation=\$(systemctl show teamtalk -p InvocationID --value); " +
        "after_pid=\$(systemctl show teamtalk -p MainPID --value); " +
        "case \"\$after_pid\" in ''|*[!0-9]*) after_pid=0 ;; esac; " +
        "if printf '%s\\n' \"\$after_invocation\" | " +
        "grep -Eq '^[0-9a-fA-F]{32}\$' && test \"\$after_pid\" -gt 0 && " +
        "test \"\$after_invocation\" != \"\$before_invocation\" && " +
        "test \"\$after_pid\" != \"\$before_pid\"; then break; fi; fi; " +
        "test \"\$(date +%s)\" -lt \"\$deadline\" || { " +
        "printf '%s\\n' 'Timed out waiting for TeamTalk emergency recovery' >&2; exit 124; }; " +
        "sleep 1; done; "

private fun waitForActiveTeamTalkProcessCommand(): String =
    "while :; do " +
        "if systemctl is-active --quiet teamtalk; then " +
        "active_invocation=\$(systemctl show teamtalk -p InvocationID --value); " +
        "active_pid=\$(systemctl show teamtalk -p MainPID --value); " +
        "case \"\$active_pid\" in ''|*[!0-9]*) active_pid=0 ;; esac; " +
        "if printf '%s\\n' \"\$active_invocation\" | " +
        "grep -Eq '^[0-9a-fA-F]{32}\$' && test \"\$active_pid\" -gt 0; then break; fi; fi; " +
        "test \"\$(date +%s)\" -lt \"\$deadline\" || { " +
        "printf '%s\\n' 'Timed out waiting for active TeamTalk service' >&2; exit 124; }; " +
        "sleep 1; done; "

private fun remoteTeamTalkDeployPathGuard(deployPath: String): String =
    "set -eu; test -d $deployPath; test ! -L $deployPath; " +
        "test \"\$(readlink -f -- $deployPath)\" = $deployPath; "

private fun processCrashRemoveMarkersCommand(clientMsgId: String): String =
    "rm -f -- \"\$directory/$clientMsgId.arm\" \"\$directory/$clientMsgId.claim\" " +
        "\"\$directory/$clientMsgId.hit\" " +
        "\"\$directory/$clientMsgId\".arm-*.tmp " +
        "\"\$directory/$clientMsgId\".claim-*.tmp " +
        "\"\$directory/$clientMsgId\".hit-*.tmp; "

internal fun parseTeamTalkProcessCrashEvidence(
    output: String,
    boundary: Core02ProcessCrashBoundary,
    expectedChatId: String,
    expectedClientMsgId: String,
): TeamTalkProcessCrashEvidence {
    requireCore02ChatId(expectedChatId)
    requireCore02ClientMsgId(expectedClientMsgId, boundary)
    val expectedKeys = setOf(
        "chatId",
        "clientMsgId",
        "stage",
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
        require(key !in values) { "Remote TeamTalk process crash evidence contains duplicate $key" }
        values[key] = line.substring(separator + 1)
    }
    require(values.keys == expectedKeys) { "Remote TeamTalk process crash evidence is incomplete" }

    val chatId = values.getValue("chatId")
    val clientMsgId = values.getValue("clientMsgId")
    val stage = values.getValue("stage")
    require(chatId == expectedChatId) { "Remote TeamTalk process crash chatId does not match its arm" }
    require(clientMsgId == expectedClientMsgId) {
        "Remote TeamTalk process crash clientMsgId does not match its arm"
    }
    require(stage == boundary.stage.name) { "Remote TeamTalk process crash stage is invalid" }

    val invocationPattern = Regex("[0-9a-fA-F]{32}")
    val beforeInvocationId = values.getValue("beforeInvocationId")
    val afterInvocationId = values.getValue("afterInvocationId")
    require(beforeInvocationId.matches(invocationPattern)) {
        "Remote TeamTalk pre-crash invocation id is malformed"
    }
    require(afterInvocationId.matches(invocationPattern)) {
        "Remote TeamTalk post-crash invocation id is malformed"
    }
    require(beforeInvocationId != afterInvocationId) {
        "Remote TeamTalk systemd invocation did not change after process crash"
    }

    val beforeMainPid = values.getValue("beforeMainPid").toLongOrNull()
        ?.takeIf { it > 0L }
        ?: error("Remote TeamTalk pre-crash MainPID is invalid")
    val afterMainPid = values.getValue("afterMainPid").toLongOrNull()
        ?.takeIf { it > 0L }
        ?: error("Remote TeamTalk post-crash MainPID is invalid")
    require(beforeMainPid != afterMainPid) {
        "Remote TeamTalk MainPID did not change after process crash"
    }
    return TeamTalkProcessCrashEvidence(
        chatId = chatId,
        clientMsgId = clientMsgId,
        stage = stage,
        beforeInvocationId = beforeInvocationId,
        beforeMainPid = beforeMainPid,
        afterInvocationId = afterInvocationId,
        afterMainPid = afterMainPid,
    )
}

internal fun parseTeamTalkProcessCrashCleanupState(
    output: String,
): TeamTalkProcessCrashCleanupState {
    var value: String? = null
    output.lineSequence().forEach { line ->
        if (!line.startsWith("cleanupState=")) return@forEach
        require(value == null) { "Remote TeamTalk process crash cleanup state is duplicated" }
        value = line.substringAfter('=')
    }
    val captured = requireNotNull(value) {
        "Remote TeamTalk process crash cleanup state is missing"
    }
    return TeamTalkProcessCrashCleanupState.entries.singleOrNull { it.wireValue == captured }
        ?: error("Remote TeamTalk process crash cleanup state is invalid")
}

internal fun requireCanonicalRemoteTeamTalkDeployPath(value: String): String {
    require(value.startsWith('/') && value != "/") {
        "Remote TeamTalk deployPath must be an absolute non-root path"
    }
    val segments = value.drop(1).split('/')
    require(segments.all { segment ->
        segment.isNotEmpty() &&
            segment != "." &&
            segment != ".." &&
            segment.all(::isSafePathCharacter)
    }) {
        "Remote TeamTalk deployPath must be canonical and contain only safe path segments"
    }
    require("/${segments.joinToString("/")}" == value) {
        "Remote TeamTalk deployPath must already be in canonical form"
    }
    return value
}

private fun requireCore02ChatId(value: String): String {
    require(value.matches(SAFE_CORE02_IDENTIFIER)) { "CORE-02 chatId is invalid" }
    return value
}

private fun requireCore02ClientMsgId(
    value: String,
    boundary: Core02ProcessCrashBoundary,
): String {
    require(value.startsWith(boundary.clientMessagePrefix) && value.matches(SAFE_CORE02_IDENTIFIER)) {
        "CORE-02 clientMsgId is invalid"
    }
    return value
}

private fun isSafePathCharacter(character: Char): Boolean =
    character in 'A'..'Z' ||
        character in 'a'..'z' ||
        character in '0'..'9' ||
        character == '.' ||
        character == '_' ||
        character == '-'

private fun executeProcessCrashFixture(arguments: List<String>, timeoutMillis: Long): String {
    require(arguments.firstOrNull() == "ssh") { "Remote process crash fixture must use SSH" }
    require(timeoutMillis in 1L..MAX_PROCESS_CRASH_TIMEOUT_MILLIS) {
        "Remote process crash fixture timeout must be positive and bounded"
    }
    val process = ProcessBuilder(arguments)
        .redirectErrorStream(true)
        .start()
    val completed = try {
        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (interrupted: InterruptedException) {
        terminateProcessCrashFixture(process)
        Thread.currentThread().interrupt()
        throw AssertionError("Remote TeamTalk process crash fixture was interrupted", interrupted)
    }
    if (!completed) {
        terminateProcessCrashFixture(process)
        throw AssertionError(
            "Remote TeamTalk process crash fixture timed out after ${timeoutMillis}ms",
        )
    }
    val captured = process.inputStream.bufferedReader().use { reader ->
        reader.readText().takeLast(MAX_CAPTURED_OUTPUT_CHARS).trimEnd()
    }
    check(process.exitValue() == 0) {
        "Remote TeamTalk process crash fixture failed (exit=${process.exitValue()})" +
            captured.takeIf(String::isNotBlank)?.let { "\nOutput tail:\n$it" }.orEmpty()
    }
    return captured
}

private fun terminateProcessCrashFixture(process: Process) {
    runCatching { process.destroy() }
    runCatching {
        if (!process.waitFor(2_000L, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(2_000L, TimeUnit.MILLISECONDS)
        }
    }
    runCatching { process.inputStream.close() }
}

private const val MARKER_WAIT_DEADLINE_SECONDS = 65
private const val RESTART_WAIT_DEADLINE_SECONDS = 65
private const val EMERGENCY_RECOVERY_DEADLINE_SECONDS = 70
private const val MAX_PROCESS_CRASH_TIMEOUT_MILLIS = 145_000L
private const val MAX_CAPTURED_OUTPUT_CHARS = 16 * 1024
private val SAFE_CORE02_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
