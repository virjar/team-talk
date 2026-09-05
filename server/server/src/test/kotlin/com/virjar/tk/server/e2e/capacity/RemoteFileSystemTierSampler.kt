package com.virjar.tk.server.e2e.capacity

import com.virjar.tk.server.e2e.RemoteTeamTalkSshTarget
import com.virjar.tk.server.e2e.RemoteTeamTalkServiceRestart

internal data class RemoteFileSystemTierSamplerConfiguration(
    val sshTarget: RemoteTeamTalkSshTarget,
    val deployPath: String,
) {
    init {
        require(deployPath.startsWith('/') && deployPath != "/") {
            "TeamTalk deploy path must be an absolute non-root path"
        }
        require(deployPath.matches(Regex("/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*"))) {
            "TeamTalk deploy path contains unsafe characters"
        }
        require(deployPath.split('/').drop(1).none { segment -> segment == "." || segment == ".." }) {
            "TeamTalk deploy path must be canonical"
        }
    }

    companion object {
        fun fromSystemProperties(): RemoteFileSystemTierSamplerConfiguration =
            RemoteFileSystemTierSamplerConfiguration(
                sshTarget = RemoteTeamTalkSshTarget.fromSystemProperties(),
                deployPath = System.getProperty("tk.e2e.deploy.path")
                    ?.takeIf(String::isNotBlank)
                    ?: error("Missing required remote capacity property: tk.e2e.deploy.path"),
            )
    }
}

/** 针对精确部署的 FileStore 文件系统目录的只读指标。 */
class RemoteFileSystemTierSampler internal constructor(
    private val configuration: RemoteFileSystemTierSamplerConfiguration,
    private val execute: (List<String>, Long) -> String,
) {
    constructor() : this(
        RemoteFileSystemTierSamplerConfiguration.fromSystemProperties(),
        ::executeResourceSample,
    )

    fun sample(phase: String, capturedAt: String, payloadBytes: Long): FileSystemTierSnapshot {
        require(phase.isNotBlank() && capturedAt.isNotBlank())
        require(payloadBytes > FILESYSTEM_TIER_BOUNDARY_BYTES)
        val output = execute(
            remoteFileSystemTierSampleSshArguments(configuration, payloadBytes),
            SAMPLE_TIMEOUT_MILLIS,
        )
        return parseFileSystemTierSample(output, phase, capturedAt)
    }

    private companion object {
        const val SAMPLE_TIMEOUT_MILLIS = 30_000L
    }
}

/** capacity 源集看到的是这个公开的窄包装器，永远不是调用方提供的 SSH 命令。 */
fun restartTeamTalkExactlyOnceForFileSystemTier(): FileSystemTierRestartEvidence {
    val evidence = RemoteTeamTalkServiceRestart().restart()
    return FileSystemTierRestartEvidence(
        beforeInvocationId = evidence.beforeInvocationId,
        beforeMainPid = evidence.beforeMainPid,
        afterInvocationId = evidence.afterInvocationId,
        afterMainPid = evidence.afterMainPid,
    )
}

internal fun remoteFileSystemTierSampleSshArguments(
    configuration: RemoteFileSystemTierSamplerConfiguration,
    payloadBytes: Long,
): List<String> = listOf(
    "ssh",
    "-p", configuration.sshTarget.port.toString(),
    "-o", "BatchMode=yes",
    "-o", "ConnectTimeout=10",
    "-o", "ServerAliveInterval=5",
    "-o", "ServerAliveCountMax=3",
    "-o", "StrictHostKeyChecking=accept-new",
    "${configuration.sshTarget.user}@${configuration.sshTarget.host}",
    remoteFileSystemTierSampleCommand(configuration, payloadBytes),
)

internal fun remoteFileSystemTierSampleCommand(
    configuration: RemoteFileSystemTierSamplerConfiguration,
    payloadBytes: Long,
): String {
    require(payloadBytes > FILESYSTEM_TIER_BOUNDARY_BYTES)
    val root = "${configuration.deployPath}/data/file-store/files"
    return "set -eu; " +
        "systemctl is-active --quiet teamtalk; " +
        "invocation_id=\$(systemctl show teamtalk -p InvocationID --value); " +
        "main_pid=\$(systemctl show teamtalk -p MainPID --value); " +
        "test \"\$(printf '%s\\n' \"\$invocation_id\" | " +
        "grep -Ec '^[0-9a-fA-F]{32}\$')\" -eq 1; " +
        "case \"\$main_pid\" in ''|*[!0-9]*) exit 1 ;; esac; test \"\$main_pid\" -gt 0; " +
        "tier_root='$root'; test -d \"\$tier_root\"; test ! -L \"\$tier_root\"; " +
        "test \"\$(readlink -f -- \"\$tier_root\")\" = \"\$tier_root\"; " +
        "sizes=\$(find \"\$tier_root\" -xdev -type f -name '*.dat' -printf '%s\\n'); " +
        "metrics=\$(printf '%s' \"\$sizes\" | " +
        "awk -v payload='$payloadBytes' '{ files += 1; bytes += \$1; " +
        "if (\$1 == payload) matching += 1 } END { " +
        "printf \"%.0f %.0f %.0f\\n\", files + 0, bytes + 0, matching + 0 }'); " +
        "set -- \$metrics; file_count=\$1; stored_bytes=\$2; payload_files=\$3; " +
        "available_bytes=\$(df -B1 --output=avail \"\$tier_root\" | " +
        "awk 'NR == 2 { print \$1 }'); " +
        "for value in \"\$file_count\" \"\$stored_bytes\" \"\$payload_files\" " +
        "\"\$available_bytes\"; do case \"\$value\" in ''|*[!0-9]*) exit 1 ;; esac; done; " +
        "test \"\$available_bytes\" -gt 0; systemctl is-active --quiet teamtalk; " +
        "final_invocation_id=\$(systemctl show teamtalk -p InvocationID --value); " +
        "final_main_pid=\$(systemctl show teamtalk -p MainPID --value); " +
        "test \"\$final_invocation_id\" = \"\$invocation_id\"; " +
        "test \"\$final_main_pid\" = \"\$main_pid\"; " +
        "printf 'invocationId=%s\\nmainPid=%s\\nfileCount=%s\\nstoredBytes=%s\\n" +
        "payloadSizedFileCount=%s\\navailableBytes=%s\\n' \"\$invocation_id\" " +
        "\"\$main_pid\" \"\$file_count\" \"\$stored_bytes\" \"\$payload_files\" " +
        "\"\$available_bytes\""
}

internal fun parseFileSystemTierSample(
    output: String,
    phase: String,
    capturedAt: String,
): FileSystemTierSnapshot {
    val expectedKeys = setOf(
        "invocationId",
        "mainPid",
        "fileCount",
        "storedBytes",
        "payloadSizedFileCount",
        "availableBytes",
    )
    val values = linkedMapOf<String, String>()
    output.lineSequence().forEach { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) return@forEach
        val key = line.substring(0, separator)
        if (key !in expectedKeys) return@forEach
        require(key !in values) { "Remote filesystem-tier sample contains duplicate $key" }
        values[key] = line.substring(separator + 1)
    }
    require(values.keys == expectedKeys) { "Remote filesystem-tier sample is incomplete" }
    fun long(key: String): Long = values.getValue(key).toLongOrNull()
        ?.takeIf { it >= 0L }
        ?: error("Remote filesystem-tier $key is invalid")
    return FileSystemTierSnapshot(
        phase = phase,
        capturedAt = capturedAt,
        invocationId = values.getValue("invocationId"),
        mainPid = long("mainPid"),
        fileCount = long("fileCount"),
        storedBytes = long("storedBytes"),
        payloadSizedFileCount = long("payloadSizedFileCount"),
        availableBytes = long("availableBytes"),
    )
}
