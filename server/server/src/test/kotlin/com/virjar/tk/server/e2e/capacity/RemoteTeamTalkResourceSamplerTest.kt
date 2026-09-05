package com.virjar.tk.server.e2e.capacity

import com.virjar.tk.server.e2e.RemoteTeamTalkSshTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteTeamTalkResourceSamplerTest {
    @Test
    fun `fixture runs one fixed bounded ssh sample and returns complete resource evidence`() {
        val configuration = configuration()
        var observedArguments = emptyList<String>()
        var observedTimeoutMillis = 0L
        val sampler = RemoteTeamTalkResourceSampler(configuration) { arguments, timeoutMillis ->
            observedArguments = arguments
            observedTimeoutMillis = timeoutMillis
            validOutput()
        }

        val snapshot = sampler.sample(PHASE, CAPTURED_AT)

        assertEquals("ssh", observedArguments.first())
        assertTrue("BatchMode=yes" in observedArguments)
        assertTrue("ConnectTimeout=10" in observedArguments)
        assertTrue("ServerAliveInterval=5" in observedArguments)
        assertTrue("ServerAliveCountMax=3" in observedArguments)
        assertEquals("deploy@deploy.example.com", observedArguments[observedArguments.lastIndex - 1])
        assertEquals(remoteTeamTalkResourceSampleCommand(configuration), observedArguments.last())
        assertEquals(30_000L, observedTimeoutMillis)
        assertEquals(PHASE, snapshot.phase)
        assertEquals(CAPTURED_AT, snapshot.capturedAt)
        assertEquals(INVOCATION_ID, snapshot.invocationId)
        assertEquals(4242L, snapshot.mainPid)
        assertEquals(2_048L * 1024L, snapshot.rssBytes)
        assertEquals(37, snapshot.threadCount)
        assertEquals(91, snapshot.fdCount)
        assertEquals(123_456L, snapshot.cpuTicks)
        assertEquals(0.42, snapshot.hostLoad1)
        assertEquals(8_192L * 1024L, snapshot.memAvailableBytes)
        assertEquals("UP", snapshot.healthStatus)
        assertEquals(BUILD_IDENTITY, snapshot.buildIdentity)
        assertEquals(9, snapshot.healthyComponents)
        assertEquals(9, snapshot.totalComponents)
    }

    @Test
    fun `remote command is read only and samples one stable exact teamtalk main process`() {
        val command = remoteTeamTalkResourceSampleCommand(configuration())

        assertTrue(command.startsWith("set -eu; systemctl is-active --quiet teamtalk;"))
        assertEquals(
            2,
            "systemctl show teamtalk -p InvocationID --value".toRegex().findAll(command).count(),
        )
        assertEquals(
            2,
            "systemctl show teamtalk -p MainPID --value".toRegex().findAll(command).count(),
        )
        assertEquals(
            2,
            "systemctl is-active --quiet teamtalk".toRegex().findAll(command).count(),
        )
        assertTrue(command.contains("proc_directory=\"/proc/\$main_pid\""))
        assertTrue(command.contains("VmRSS:"))
        assertTrue(command.contains("Threads:"))
        assertTrue(command.contains("find \"\$fd_directory\" -mindepth 1 -maxdepth 1"))
        assertTrue(command.contains("\"\$stat_file\""))
        assertTrue(command.contains("/proc/loadavg"))
        assertTrue(command.contains("MemAvailable:"))
        assertTrue(command.contains("/proc/meminfo"))
        assertTrue(command.contains("curl --disable"))
        assertTrue(command.contains("--request GET"))
        assertTrue(command.contains("--connect-timeout 3"))
        assertTrue(command.contains("--max-time 10"))
        assertTrue(command.contains("--max-filesize 16384"))
        assertTrue(command.contains("--noproxy '*'"))
        assertTrue(command.contains("--proto '=http'"))
        assertTrue(command.contains("'http://127.0.0.1:8080/health'"))
        assertFalse(command.contains("--insecure"))
        listOf(
            "systemctl start",
            "systemctl stop",
            "systemctl restart",
            "systemctl kill",
            "pkill",
            "kill -",
            " rm ",
            " mv ",
            "mkdir",
            "touch",
            "chmod",
            "chown",
            "tee ",
            "docker",
            "iptables",
            "nft ",
            "nmcli",
            "--location",
            "--request POST",
            "--request PUT",
            "--request DELETE",
        ).forEach { forbidden ->
            assertFalse(command.contains(forbidden), "remote sample must not contain $forbidden")
        }
    }

    @Test
    fun `health curl selects only the configured loopback connector`() {
        val plaintext = remoteTeamTalkResourceSampleCommand(
            configuration(sslEnabled = false, httpPort = 18080, sslPort = 18443),
        )
        val tls = remoteTeamTalkResourceSampleCommand(
            configuration(sslEnabled = true, httpPort = 18080, sslPort = 18443),
        )

        assertTrue(plaintext.contains("--proto '=http'"))
        assertTrue(plaintext.contains("'http://127.0.0.1:18080/health'"))
        assertFalse(plaintext.contains("18443/health"))
        assertFalse(plaintext.contains("--insecure"))
        assertTrue(tls.contains("--proto '=https'"))
        assertTrue(tls.contains("--insecure"))
        assertTrue(tls.contains("'https://127.0.0.1:18443/health'"))
        assertFalse(tls.contains("18080/health"))
        assertFalse(tls.contains("deploy.example.com/health"))
    }

    @Test
    fun `parser keeps a structurally complete down health snapshot for reporting`() {
        val snapshot = parseTeamTalkResourceSample(
            validOutput(
                healthJson = healthJson(
                    overallStatus = "DOWN",
                    componentStatuses = listOf(
                        "UP",
                        "DOWN",
                        "DOWN",
                        "DOWN",
                        "DOWN",
                        "DOWN",
                        "DOWN",
                        "DOWN",
                        "DOWN",
                    ),
                ),
            ),
            phase = "hold",
            capturedAt = CAPTURED_AT,
        )

        assertEquals("DOWN", snapshot.healthStatus)
        assertEquals(1, snapshot.healthyComponents)
        assertEquals(9, snapshot.totalComponents)
        assertEquals(BUILD_IDENTITY, snapshot.buildIdentity)
    }

    @Test
    fun `parser rejects missing duplicate and invalid process evidence`() {
        val complete = validOutput()
        val missingCpu = complete.lineSequence()
            .filterNot { it.startsWith("cpuTicks=") }
            .joinToString("\n")

        assertFailsWith<IllegalArgumentException> {
            parseTeamTalkResourceSample(missingCpu, PHASE, CAPTURED_AT)
        }
        assertFailsWith<IllegalArgumentException> {
            parseTeamTalkResourceSample(
                "$complete\nmainPid=5252",
                PHASE,
                CAPTURED_AT,
            )
        }
        listOf("", "0", "-1", "not-a-pid").forEach { mainPid ->
            assertFailsWith<IllegalArgumentException>(mainPid) {
                parseTeamTalkResourceSample(
                    validOutput(mainPid = mainPid),
                    PHASE,
                    CAPTURED_AT,
                )
            }
        }
    }

    @Test
    fun `parser requires status build identity and complete component health objects`() {
        val completeHealth = healthJson()
        listOf(
            """{"buildIdentity":"$BUILD_IDENTITY","components":{"postgres":{"status":"UP"}}}""",
            """{"status":"UP","components":{"postgres":{"status":"UP"}}}""",
            """{"status":"UP","buildIdentity":"$BUILD_IDENTITY"}""",
            """{"status":"UP","buildIdentity":"$BUILD_IDENTITY","components":{}}""",
            """{"status":"DEGRADED","buildIdentity":"$BUILD_IDENTITY","components":{"postgres":{"status":"UP"}}}""",
            completeHealth.replace(
                "\"postgres\":{\"status\":\"UP\"}",
                "\"postgres\":\"UP\"",
            ),
            completeHealth.replace(
                "\"postgres\":{\"status\":\"UP\"}",
                "\"postgres\":{\"status\":\"DEGRADED\"}",
            ),
            completeHealth.replace(
                "\"components\":{",
                "\"components\":{\"unexpected\":{\"status\":\"UP\"},",
            ),
        ).forEach { invalidHealth ->
            assertFailsWith<IllegalArgumentException>(invalidHealth) {
                parseTeamTalkResourceSample(
                    validOutput(healthJson = invalidHealth),
                    PHASE,
                    CAPTURED_AT,
                )
            }
        }
    }

    @Test
    fun `configuration requires valid fixed connector ports`() {
        listOf(0, 65_536).forEach { invalidPort ->
            assertFailsWith<IllegalArgumentException> {
                configuration(httpPort = invalidPort)
            }
            assertFailsWith<IllegalArgumentException> {
                configuration(sslPort = invalidPort)
            }
        }
    }

    @Test
    fun `configuration reads the deployment ssh and connector system properties`() {
        withSystemProperties(
            mapOf(
                "tk.e2e.deploy.host" to "capacity.example.com",
                "tk.e2e.deploy.user" to "capacity",
                "tk.e2e.deploy.port" to "2202",
                "tk.capacity.deploy.sslEnabled" to "true",
                "tk.capacity.deploy.httpPort" to "18080",
                "tk.capacity.deploy.sslPort" to "18443",
            ),
        ) {
            val configuration = RemoteTeamTalkResourceSamplerConfiguration.fromSystemProperties()

            assertEquals("capacity.example.com", configuration.sshTarget.host)
            assertEquals("capacity", configuration.sshTarget.user)
            assertEquals(2202, configuration.sshTarget.port)
            assertTrue(configuration.sslEnabled)
            assertEquals(18080, configuration.httpPort)
            assertEquals(18443, configuration.sslPort)
        }
    }

    private fun configuration(
        sslEnabled: Boolean = false,
        httpPort: Int = 8080,
        sslPort: Int = 8443,
    ): RemoteTeamTalkResourceSamplerConfiguration = RemoteTeamTalkResourceSamplerConfiguration(
        sshTarget = RemoteTeamTalkSshTarget("deploy.example.com", "deploy", 2222),
        sslEnabled = sslEnabled,
        httpPort = httpPort,
        sslPort = sslPort,
    )

    private fun validOutput(
        invocationId: String = INVOCATION_ID,
        mainPid: String = "4242",
        vmRssKiB: String = "2048",
        threadCount: String = "37",
        fdCount: String = "91",
        cpuTicks: String = "123456",
        hostLoad1: String = "0.42",
        memAvailableKiB: String = "8192",
        healthJson: String = healthJson(),
    ): String = """
        Warning: accepted host key
        invocationId=$invocationId
        mainPid=$mainPid
        vmRssKiB=$vmRssKiB
        threadCount=$threadCount
        fdCount=$fdCount
        cpuTicks=$cpuTicks
        hostLoad1=$hostLoad1
        memAvailableKiB=$memAvailableKiB
        healthJson=$healthJson
    """.trimIndent()

    private fun healthJson(
        overallStatus: String = "UP",
        componentStatuses: List<String> = List(HEALTH_COMPONENT_NAMES.size) { "UP" },
    ): String {
        require(componentStatuses.size == HEALTH_COMPONENT_NAMES.size)
        val components = HEALTH_COMPONENT_NAMES.zip(componentStatuses)
            .joinToString(",") { (name, status) ->
                "\"$name\":{\"status\":\"$status\"}"
            }
        return """{"status":"$overallStatus","components":{$components},"buildIdentity":"$BUILD_IDENTITY"}"""
    }

    private fun <T> withSystemProperties(
        replacements: Map<String, String>,
        block: () -> T,
    ): T = synchronized(System.getProperties()) {
        val previous = replacements.keys.associateWith(System::getProperty)
        try {
            replacements.forEach(System::setProperty)
            block()
        } finally {
            previous.forEach { (name, value) ->
                if (value == null) System.clearProperty(name) else System.setProperty(name, value)
            }
        }
    }

    private companion object {
        const val PHASE = "baseline"
        const val CAPTURED_AT = "2026-08-31T12:34:56Z"
        const val INVOCATION_ID = "00000000000000000000000000000042"
        const val BUILD_IDENTITY = "1.0.7+0123456789abcdef0123456789abcdef01234567"
        val HEALTH_COMPONENT_NAMES = listOf(
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
    }
}
