package com.virjar.tk.server.e2e.capacity

import com.virjar.tk.server.e2e.RemoteTeamTalkSshTarget
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class RemoteTeamTalkResourceSamplerConfiguration(
    val sshTarget: RemoteTeamTalkSshTarget,
    val sslEnabled: Boolean,
    val httpPort: Int,
    val sslPort: Int,
) {
    init {
        require(httpPort in 1..65535) { "TeamTalk capacity HTTP port is invalid" }
        require(sslPort in 1..65535) { "TeamTalk capacity HTTPS port is invalid" }
    }

    companion object {
        fun fromSystemProperties(): RemoteTeamTalkResourceSamplerConfiguration =
            RemoteTeamTalkResourceSamplerConfiguration(
                sshTarget = RemoteTeamTalkSshTarget.fromSystemProperties(),
                sslEnabled = requireBooleanSystemProperty(SSL_ENABLED_PROPERTY),
                httpPort = requirePortSystemProperty(HTTP_PORT_PROPERTY),
                sslPort = requirePortSystemProperty(SSL_PORT_PROPERTY),
            )

        private fun requireBooleanSystemProperty(name: String): Boolean =
            when (val value = requireSystemProperty(name)) {
                "true" -> true
                "false" -> false
                else -> error("$name must be true or false, was: $value")
            }

        private fun requirePortSystemProperty(name: String): Int =
            requireSystemProperty(name).toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: error("$name must be an integer port in 1..65535")

        private fun requireSystemProperty(name: String): String =
            System.getProperty(name)?.takeIf(String::isNotBlank)
                ?: error("Missing required remote capacity property: $name")
    }
}

/**
 * 针对精确活动的 `teamtalk` systemd 主进程及其 loopback 健康状态的只读采样器。
 * 健康请求之后会再次核对进程身份，因此单个快照不可能跨越一次服务重启。
 */
class RemoteTeamTalkResourceSampler internal constructor(
    private val configuration: RemoteTeamTalkResourceSamplerConfiguration,
    private val execute: (List<String>, Long) -> String,
) {
    constructor() : this(
        configuration = RemoteTeamTalkResourceSamplerConfiguration.fromSystemProperties(),
        execute = ::executeResourceSample,
    )

    fun sample(phase: String, capturedAt: String): TeamTalkResourceSnapshot {
        require(phase.isNotBlank()) { "TeamTalk resource sample phase must not be blank" }
        require(capturedAt.isNotBlank()) {
            "TeamTalk resource sample capture time must not be blank"
        }
        val output = execute(
            remoteTeamTalkResourceSampleSshArguments(configuration),
            SAMPLE_TIMEOUT_MILLIS,
        )
        return parseTeamTalkResourceSample(output, phase, capturedAt)
    }

    private companion object {
        const val SAMPLE_TIMEOUT_MILLIS = 30_000L
    }
}

internal fun remoteTeamTalkResourceSampleSshArguments(
    configuration: RemoteTeamTalkResourceSamplerConfiguration,
): List<String> = listOf(
    "ssh",
    "-p", configuration.sshTarget.port.toString(),
    "-o", "BatchMode=yes",
    "-o", "ConnectTimeout=10",
    "-o", "ServerAliveInterval=5",
    "-o", "ServerAliveCountMax=3",
    "-o", "StrictHostKeyChecking=accept-new",
    "${configuration.sshTarget.user}@${configuration.sshTarget.host}",
    remoteTeamTalkResourceSampleCommand(configuration),
)

internal fun remoteTeamTalkResourceSampleCommand(
    configuration: RemoteTeamTalkResourceSamplerConfiguration,
): String {
    val scheme = if (configuration.sslEnabled) "https" else "http"
    val port = if (configuration.sslEnabled) configuration.sslPort else configuration.httpPort
    val tlsOption = if (configuration.sslEnabled) "--insecure " else ""
    val healthUrl = "$scheme://127.0.0.1:$port/health"
    return "set -eu; " +
        "systemctl is-active --quiet teamtalk; " +
        "invocation_id=\$(systemctl show teamtalk -p InvocationID --value); " +
        "main_pid=\$(systemctl show teamtalk -p MainPID --value); " +
        "test \"\$(printf '%s\\n' \"\$invocation_id\" | " +
        "grep -Ec '^[0-9a-fA-F]{32}\$')\" -eq 1; " +
        "case \"\$main_pid\" in ''|*[!0-9]*) exit 1 ;; esac; " +
        "test \"\$main_pid\" -gt 0; " +
        "proc_directory=\"/proc/\$main_pid\"; " +
        "test -d \"\$proc_directory\"; test ! -L \"\$proc_directory\"; " +
        "status_file=\"\$proc_directory/status\"; stat_file=\"\$proc_directory/stat\"; " +
        "fd_directory=\"\$proc_directory/fd\"; " +
        "test -r \"\$status_file\"; test -r \"\$stat_file\"; " +
        "test -d \"\$fd_directory\"; " +
        "vm_rss_kib=\$(awk '\$1 == \"VmRSS:\" { value = \$2; count += 1 } " +
        "END { if (count != 1) exit 1; print value }' \"\$status_file\"); " +
        "thread_count=\$(awk '\$1 == \"Threads:\" { value = \$2; count += 1 } " +
        "END { if (count != 1) exit 1; print value }' \"\$status_file\"); " +
        "fd_count=\$(find \"\$fd_directory\" -mindepth 1 -maxdepth 1 -printf '.\\n' | " +
        "wc -l | tr -d '[:space:]'); " +
        "cpu_ticks=\$(sed -n 's/^.*) //p' \"\$stat_file\" | " +
        "awk 'NF >= 13 { print \$12 + \$13 }'); " +
        "host_load1=\$(awk 'NR == 1 { print \$1 }' /proc/loadavg); " +
        "mem_available_kib=\$(awk '\$1 == \"MemAvailable:\" { value = \$2; count += 1 } " +
        "END { if (count != 1) exit 1; print value }' /proc/meminfo); " +
        "for value in \"\$vm_rss_kib\" \"\$thread_count\" \"\$fd_count\" " +
        "\"\$cpu_ticks\" \"\$mem_available_kib\"; do " +
        "case \"\$value\" in ''|*[!0-9]*) exit 1 ;; esac; done; " +
        "test \"\$vm_rss_kib\" -gt 0; test \"\$thread_count\" -gt 0; " +
        "test \"\$mem_available_kib\" -gt 0; test -n \"\$host_load1\"; " +
        "health_json=\$(curl --disable --silent --show-error --request GET " +
        "--connect-timeout 3 --max-time 10 --max-filesize 16384 --noproxy '*' " +
        "--proto '=$scheme' $tlsOption'$healthUrl'); " +
        "test -n \"\$health_json\"; " +
        "test \"\$(printf '%s\\n' \"\$health_json\" | awk 'END { print NR }')\" -eq 1; " +
        "systemctl is-active --quiet teamtalk; " +
        "final_invocation_id=\$(systemctl show teamtalk -p InvocationID --value); " +
        "final_main_pid=\$(systemctl show teamtalk -p MainPID --value); " +
        "test \"\$final_invocation_id\" = \"\$invocation_id\"; " +
        "test \"\$final_main_pid\" = \"\$main_pid\"; " +
        "printf 'invocationId=%s\\nmainPid=%s\\nvmRssKiB=%s\\nthreadCount=%s\\n" +
        "fdCount=%s\\ncpuTicks=%s\\nhostLoad1=%s\\nmemAvailableKiB=%s\\n" +
        "healthJson=%s\\n' \"\$invocation_id\" \"\$main_pid\" \"\$vm_rss_kib\" " +
        "\"\$thread_count\" \"\$fd_count\" \"\$cpu_ticks\" \"\$host_load1\" " +
        "\"\$mem_available_kib\" \"\$health_json\""
}

internal fun parseTeamTalkResourceSample(
    output: String,
    phase: String,
    capturedAt: String,
): TeamTalkResourceSnapshot {
    require(phase.isNotBlank()) { "TeamTalk resource sample phase must not be blank" }
    require(capturedAt.isNotBlank()) { "TeamTalk resource sample capture time must not be blank" }
    val expectedKeys = setOf(
        "invocationId",
        "mainPid",
        "vmRssKiB",
        "threadCount",
        "fdCount",
        "cpuTicks",
        "hostLoad1",
        "memAvailableKiB",
        "healthJson",
    )
    val values = linkedMapOf<String, String>()
    output.lineSequence().forEach { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) return@forEach
        val key = line.substring(0, separator)
        if (key !in expectedKeys) return@forEach
        require(key !in values) { "Remote TeamTalk resource sample contains duplicate $key" }
        values[key] = line.substring(separator + 1)
    }
    require(values.keys == expectedKeys) { "Remote TeamTalk resource sample is incomplete" }

    val invocationId = values.getValue("invocationId")
    require(invocationId.matches(INVOCATION_ID_PATTERN)) {
        "Remote TeamTalk resource invocation id is malformed"
    }
    val mainPid = positiveLong(values, "mainPid")
    val rssBytes = kibibytesToBytes(positiveLong(values, "vmRssKiB"), "VmRSS")
    val threadCount = positiveInt(values, "threadCount")
    val fdCount = nonNegativeInt(values, "fdCount")
    val cpuTicks = nonNegativeLong(values, "cpuTicks")
    val hostLoad1 = values.getValue("hostLoad1").toDoubleOrNull()
    require(hostLoad1 != null && hostLoad1.isFinite() && hostLoad1 >= 0.0) {
        "Remote TeamTalk host load1 is invalid"
    }
    val memAvailableBytes = kibibytesToBytes(
        positiveLong(values, "memAvailableKiB"),
        "MemAvailable",
    )
    val health = parseTeamTalkHealth(values.getValue("healthJson"))
    return TeamTalkResourceSnapshot(
        phase = phase,
        capturedAt = capturedAt,
        invocationId = invocationId,
        mainPid = mainPid,
        rssBytes = rssBytes,
        threadCount = threadCount,
        fdCount = fdCount,
        cpuTicks = cpuTicks,
        hostLoad1 = hostLoad1,
        memAvailableBytes = memAvailableBytes,
        healthStatus = health.status,
        buildIdentity = health.buildIdentity,
        healthyComponents = health.healthyComponents,
        totalComponents = health.totalComponents,
    )
}

private data class ParsedTeamTalkHealth(
    val status: String,
    val buildIdentity: String,
    val healthyComponents: Int,
    val totalComponents: Int,
)

private fun parseTeamTalkHealth(json: String): ParsedTeamTalkHealth {
    val root = runCatching { Json.parseToJsonElement(json) }
        .getOrElse { failure ->
            throw IllegalArgumentException("Remote TeamTalk health JSON is malformed", failure)
        } as? JsonObject
        ?: throw IllegalArgumentException("Remote TeamTalk health JSON must be an object")
    val status = requiredJsonString(root, "status", "health")
    require(status == "UP" || status == "DOWN") {
        "Remote TeamTalk health status is invalid"
    }
    val buildIdentity = requiredJsonString(root, "buildIdentity", "health")
    require(buildIdentity.isNotBlank()) { "Remote TeamTalk health build identity is blank" }
    val components = root["components"] as? JsonObject
        ?: throw IllegalArgumentException("Remote TeamTalk health components are missing")
    require(components.isNotEmpty()) { "Remote TeamTalk health components are empty" }
    require(components.keys == REQUIRED_HEALTH_COMPONENTS) {
        "Remote TeamTalk health components differ from the expected set: " +
            "${components.keys.sorted()}"
    }
    var healthyComponents = 0
    components.forEach { (name, value) ->
        require(name.isNotBlank()) { "Remote TeamTalk health component name is blank" }
        val component = value as? JsonObject
            ?: throw IllegalArgumentException("Remote TeamTalk health component $name is malformed")
        val componentStatus = requiredJsonString(component, "status", "health component $name")
        require(componentStatus == "UP" || componentStatus == "DOWN") {
            "Remote TeamTalk health component $name status is invalid"
        }
        if (componentStatus == "UP") healthyComponents += 1
    }
    return ParsedTeamTalkHealth(
        status = status,
        buildIdentity = buildIdentity,
        healthyComponents = healthyComponents,
        totalComponents = components.size,
    )
}

private fun requiredJsonString(objectValue: JsonObject, key: String, owner: String): String {
    val primitive = objectValue[key] as? JsonPrimitive
        ?: throw IllegalArgumentException("Remote TeamTalk $owner $key is missing")
    require(primitive.isString) { "Remote TeamTalk $owner $key must be a string" }
    return primitive.content
}

private fun positiveLong(values: Map<String, String>, key: String): Long {
    val value = values.getValue(key).toLongOrNull()
    require(value != null && value > 0L) { "Remote TeamTalk resource $key is invalid" }
    return value
}

private fun nonNegativeLong(values: Map<String, String>, key: String): Long {
    val value = values.getValue(key).toLongOrNull()
    require(value != null && value >= 0L) { "Remote TeamTalk resource $key is invalid" }
    return value
}

private fun positiveInt(values: Map<String, String>, key: String): Int {
    val value = values.getValue(key).toIntOrNull()
    require(value != null && value > 0) { "Remote TeamTalk resource $key is invalid" }
    return value
}

private fun nonNegativeInt(values: Map<String, String>, key: String): Int {
    val value = values.getValue(key).toIntOrNull()
    require(value != null && value >= 0) { "Remote TeamTalk resource $key is invalid" }
    return value
}

private fun kibibytesToBytes(kibibytes: Long, field: String): Long {
    require(kibibytes <= Long.MAX_VALUE / BYTES_PER_KIBIBYTE) {
        "Remote TeamTalk resource $field exceeds the supported range"
    }
    return kibibytes * BYTES_PER_KIBIBYTE
}

internal fun executeResourceSample(arguments: List<String>, timeoutMillis: Long): String {
    require(arguments.firstOrNull() == "ssh") { "Remote resource sample must use SSH" }
    require(timeoutMillis in 1L..MAX_SAMPLE_TIMEOUT_MILLIS) {
        "Remote resource sample timeout must be positive and bounded"
    }
    val process = ProcessBuilder(arguments)
        .redirectErrorStream(true)
        .start()
    val completed = try {
        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (interrupted: InterruptedException) {
        terminateResourceSample(process)
        Thread.currentThread().interrupt()
        throw AssertionError("Remote TeamTalk resource sample was interrupted", interrupted)
    }
    if (!completed) {
        terminateResourceSample(process)
        throw AssertionError("Remote TeamTalk resource sample timed out after ${timeoutMillis}ms")
    }
    val captured = process.inputStream.bufferedReader().use { reader ->
        reader.readText().takeLast(MAX_CAPTURED_OUTPUT_CHARS).trimEnd()
    }
    check(process.exitValue() == 0) {
        "Remote TeamTalk resource sample failed (exit=${process.exitValue()})" +
            captured.takeIf(String::isNotBlank)?.let { "\nOutput tail:\n$it" }.orEmpty()
    }
    return captured
}

private fun terminateResourceSample(process: Process) {
    runCatching { process.destroy() }
    runCatching {
        if (!process.waitFor(2_000L, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(2_000L, TimeUnit.MILLISECONDS)
        }
    }
    runCatching { process.inputStream.close() }
}

private const val SSL_ENABLED_PROPERTY = "tk.capacity.deploy.sslEnabled"
private const val HTTP_PORT_PROPERTY = "tk.capacity.deploy.httpPort"
private const val SSL_PORT_PROPERTY = "tk.capacity.deploy.sslPort"
private const val BYTES_PER_KIBIBYTE = 1024L
private const val MAX_SAMPLE_TIMEOUT_MILLIS = 30_000L
private const val MAX_CAPTURED_OUTPUT_CHARS = 32 * 1024
private val INVOCATION_ID_PATTERN = Regex("[0-9a-fA-F]{32}")
private val REQUIRED_HEALTH_COMPONENTS = setOf(
    "postgres",
    "rocksdb",
    "lucene",
    "sync-event-dispatcher",
    "message-projection",
    "managed-chat-projection",
    "client-telemetry",
    "file-storage",
    "tcp",
)
