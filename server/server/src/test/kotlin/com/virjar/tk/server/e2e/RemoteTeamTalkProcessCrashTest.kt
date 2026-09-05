package com.virjar.tk.server.e2e

import com.virjar.tk.server.infra.storage.Core02ProcessCrashBoundary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteTeamTalkProcessCrashTest {
    @Test
    fun `each boundary uses three fixed bounded ssh operations and returns its restart evidence`() {
        Core02ProcessCrashBoundary.entries.forEach { boundary ->
            val clientMsgId = clientMsgIdFor(boundary)
            val calls = mutableListOf<Pair<List<String>, Long>>()
            val fixture = RemoteTeamTalkProcessCrash(
                boundary,
                configuration(),
            ) { arguments, timeoutMillis ->
                calls += arguments to timeoutMillis
                when {
                    arguments.last().contains("printf 'chatId=%s") -> validEvidence(
                        clientMsgId = clientMsgId,
                        stage = boundary.stage.name,
                    )
                    arguments.last().contains("cleanupState=") -> "cleanupState=observed-restarted"
                    else -> ""
                }
            }

            fixture.arm(CHAT_ID, clientMsgId)
            val evidence = fixture.awaitHitKillAndRestart(clientMsgId)
            fixture.cleanup(clientMsgId)

            assertEquals(3, calls.size)
            calls.forEach { (arguments, timeoutMillis) ->
                assertEquals("ssh", arguments.first())
                assertTrue("BatchMode=yes" in arguments)
                assertTrue("ConnectTimeout=10" in arguments)
                assertTrue("ServerAliveInterval=5" in arguments)
                assertTrue("ServerAliveCountMax=3" in arguments)
                assertEquals("deploy@deploy.example.com", arguments[arguments.lastIndex - 1])
                assertTrue(timeoutMillis in 1L..145_000L)
            }
            assertEquals(30_000L, calls[0].second)
            assertEquals(145_000L, calls[1].second)
            assertEquals(85_000L, calls[2].second)
            assertEquals(
                remoteTeamTalkProcessCrashArmCommand(boundary, DEPLOY_PATH, CHAT_ID, clientMsgId),
                calls[0].first.last(),
            )
            assertEquals(
                remoteTeamTalkProcessCrashAwaitCommand(boundary, DEPLOY_PATH, CHAT_ID, clientMsgId),
                calls[1].first.last(),
            )
            assertEquals(
                remoteTeamTalkProcessCrashCleanupCommand(boundary, DEPLOY_PATH, CHAT_ID, clientMsgId),
                calls[2].first.last(),
            )
            assertTrue(calls[1].first.last().contains("stage=${boundary.stage.name}"))
            assertTrue(calls[2].first.last().contains("stage=${boundary.stage.name}"))
            assertFalse(calls[0].first.last().contains("systemctl kill"))
            listOf(calls[1], calls[2]).forEach { (arguments, _) ->
                val command = arguments.last()
                assertEquals(
                    1,
                    "systemctl kill --kill-who=main --signal=KILL teamtalk".toRegex()
                        .findAll(command)
                        .count(),
                )
                assertFalse(command.contains("systemctl restart"))
                assertFalse(command.contains("pkill"))
            }
            assertEquals(CHAT_ID, evidence.chatId)
            assertEquals(clientMsgId, evidence.clientMsgId)
            assertEquals(boundary.stage.name, evidence.stage)
            assertEquals(101L, evidence.beforeMainPid)
            assertEquals(202L, evidence.afterMainPid)
        }
    }

    @Test
    fun `fixture commands and evidence reject a client identity from another boundary`() {
        Core02ProcessCrashBoundary.entries.forEach { boundary ->
            val nextBoundaryIndex = (boundary.ordinal + 1) % Core02ProcessCrashBoundary.entries.size
            val wrongBoundary = Core02ProcessCrashBoundary.entries[nextBoundaryIndex]
            val wrongClientMsgId = clientMsgIdFor(wrongBoundary)
            val fixture = RemoteTeamTalkProcessCrash(boundary, configuration()) { _, _ ->
                error("boundary validation must happen before SSH execution")
            }

            assertFailsWith<IllegalArgumentException> {
                fixture.arm(CHAT_ID, wrongClientMsgId)
            }
            assertFailsWith<IllegalArgumentException> {
                fixture.awaitHitKillAndRestart(wrongClientMsgId)
            }
            assertFailsWith<IllegalArgumentException> {
                fixture.cleanup(wrongClientMsgId)
            }
            listOf<() -> String>(
                {
                    remoteTeamTalkProcessCrashArmCommand(
                        boundary,
                        DEPLOY_PATH,
                        CHAT_ID,
                        wrongClientMsgId,
                    )
                },
                {
                    remoteTeamTalkProcessCrashAwaitCommand(
                        boundary,
                        DEPLOY_PATH,
                        CHAT_ID,
                        wrongClientMsgId,
                    )
                },
                {
                    remoteTeamTalkProcessCrashCleanupCommand(
                        boundary,
                        DEPLOY_PATH,
                        CHAT_ID,
                        wrongClientMsgId,
                    )
                },
            ).forEach { command ->
                assertFailsWith<IllegalArgumentException> { command() }
            }
            assertFailsWith<IllegalArgumentException> {
                parseTeamTalkProcessCrashEvidence(
                    validEvidence(
                        clientMsgId = wrongClientMsgId,
                        stage = boundary.stage.name,
                    ),
                    boundary,
                    CHAT_ID,
                    wrongClientMsgId,
                )
            }
        }
    }

    @Test
    fun `arm writes only the exact chat id atomically under the deployment data directory`() {
        val command = remoteTeamTalkProcessCrashArmCommand(
            BOUNDARY,
            DEPLOY_PATH,
            CHAT_ID,
            CLIENT_MSG_ID,
        )

        assertTrue(command.contains("directory=$DEPLOY_PATH/data/acceptance/core02"))
        val dataValidation = command.indexOf("test -d \"\$data\"")
        val acceptanceCreation = command.indexOf("mkdir -- \"\$acceptance\"")
        val markerDirectoryCreation = command.indexOf("mkdir -- \"\$directory\"")
        assertTrue(dataValidation >= 0)
        assertTrue(acceptanceCreation > dataValidation)
        assertTrue(markerDirectoryCreation > acceptanceCreation)
        assertFalse(command.contains("mkdir -p"))
        assertTrue(command.contains("printf '%s' '$CHAT_ID'"))
        assertTrue(command.contains("$CLIENT_MSG_ID.arm"))
        assertTrue(command.contains("$CLIENT_MSG_ID.claim"))
        assertTrue(command.contains("mv -f"))
        assertTrue(command.contains("readlink -f"))
        assertFalse(command.contains("systemctl"))
        assertFalse(command.contains("$CHAT_ID\\n"))
    }

    @Test
    fun `kill command validates the complete hit against systemd before one fixed main kill`() {
        val command = remoteTeamTalkProcessCrashAwaitCommand(
            BOUNDARY,
            DEPLOY_PATH,
            CHAT_ID,
            CLIENT_MSG_ID,
        )

        assertTrue(command.contains("$CLIENT_MSG_ID.hit"))
        assertTrue(command.contains("^pid=[0-9]+$"))
        assertTrue(command.contains("^invocationId=[0-9a-fA-F]{32}$"))
        assertTrue(command.contains("chatId=$CHAT_ID"))
        assertTrue(command.contains("clientMsgId=$CLIENT_MSG_ID"))
        assertTrue(command.contains("stage=AFTER_PENDING_BEFORE_PROJECTION"))
        assertTrue(command.contains("systemctl show teamtalk -p InvocationID --value"))
        assertTrue(command.contains("systemctl show teamtalk -p MainPID --value"))
        assertTrue(command.contains("test \"\$marker_invocation\" = \"\$before_invocation\""))
        assertTrue(command.contains("test \"\$marker_pid\" = \"\$before_pid\""))
        assertTrue(command.contains("test \"\$kill_invocation\" = \"\$marker_invocation\""))
        assertTrue(command.contains("test \"\$kill_pid\" = \"\$marker_pid\""))
        val markerDeadline = command.indexOf("marker_deadline=\$((\$(date +%s) + 65))")
        val fixedKill = command.indexOf("systemctl kill --kill-who=main --signal=KILL teamtalk")
        val restartDeadline = command.indexOf("restart_deadline=\$((\$(date +%s) + 65))")
        val finalIdentityCheck = command.indexOf(
            "test \"\$kill_invocation\" = \"\$marker_invocation\"",
        )
        assertTrue(markerDeadline >= 0)
        assertTrue(finalIdentityCheck > markerDeadline)
        assertTrue(fixedKill > finalIdentityCheck)
        assertTrue(fixedKill > markerDeadline)
        assertTrue(restartDeadline > fixedKill)
        assertTrue(command.contains("-lt \"\$marker_deadline\""))
        assertTrue(command.contains("-lt \"\$restart_deadline\""))
        assertEquals(
            1,
            "systemctl kill --kill-who=main --signal=KILL teamtalk".toRegex()
                .findAll(command)
                .count(),
        )
        assertTrue(command.contains("systemctl is-active --quiet teamtalk"))
        assertTrue(command.contains("after_invocation"))
        assertTrue(command.contains("after_pid"))
        assertFalse(command.contains("systemctl restart"))
        assertFalse(command.contains("pkill"))
        assertFalse(command.contains("docker"))
        assertFalse(command.contains("network"))

        val cleanup = remoteTeamTalkProcessCrashCleanupCommand(
            BOUNDARY,
            DEPLOY_PATH,
            CHAT_ID,
            CLIENT_MSG_ID,
        )
        assertTrue(cleanup.contains("$CLIENT_MSG_ID.claim"))
    }

    @Test
    fun `cleanup is a strict emergency recovery state machine`() {
        val command = remoteTeamTalkProcessCrashCleanupCommand(
            BOUNDARY,
            DEPLOY_PATH,
            CHAT_ID,
            CLIENT_MSG_ID,
        )

        val hitBranch = command.indexOf("if test -f \"\$marker\"; then")
        val claimBranch = command.indexOf("elif test -f \"\$claim\"; then")
        val armBranch = command.indexOf("elif test -f \"\$arm\"; then")
        val conditionalKill = command.indexOf("if test \"\$needs_kill\" -eq 1; then")
        val markerRemoval = command.indexOf("rm -f --")
        assertTrue(hitBranch >= 0)
        assertTrue(claimBranch > hitBranch)
        assertTrue(armBranch > claimBranch)
        assertTrue(conditionalKill > armBranch)
        assertTrue(markerRemoval > conditionalKill)
        val armOnlyBranch = command.substring(armBranch, conditionalKill)
        assertFalse(armOnlyBranch.contains("needs_kill=1"))
        assertFalse(armOnlyBranch.contains("systemctl kill"))

        assertTrue(command.contains("^pid=[0-9]+$"))
        assertTrue(command.contains("^invocationId=[0-9a-fA-F]{32}$"))
        assertTrue(command.contains("chatId=$CHAT_ID"))
        assertTrue(command.contains("clientMsgId=$CLIENT_MSG_ID"))
        assertTrue(command.contains("stage=AFTER_PENDING_BEFORE_PROJECTION"))
        assertTrue(command.contains("current_invocation"))
        assertTrue(command.contains("current_pid"))
        assertTrue(command.contains("cleanup_state=observed-restarted"))
        assertTrue(command.contains("cleanup_state=killed-hit"))
        assertTrue(command.contains("kill_invocation="))
        assertTrue(command.contains("kill_pid="))
        assertTrue(command.contains("test \"\$kill_invocation\" = \"\$before_invocation\""))
        assertTrue(command.contains("test \"\$kill_pid\" = \"\$before_pid\""))

        assertTrue(command.contains("wc -c < \"\$claim\""))
        assertTrue(command.contains("cat \"\$claim\""))
        assertTrue(command.contains("cleanup_state=killed-claim"))
        assertTrue(command.contains("wc -c < \"\$arm\""))
        assertTrue(command.contains("cat \"\$arm\""))
        assertTrue(command.contains("cleanup_state=disarmed"))
        val activeProof = command.indexOf("active_invocation=")
        val firstAbsentSuccess = command.indexOf("printf 'cleanupState=absent")
        assertTrue(activeProof >= 0)
        assertTrue(firstAbsentSuccess > activeProof)
        assertEquals(
            1,
            "systemctl kill --kill-who=main --signal=KILL teamtalk".toRegex()
                .findAll(command)
                .count(),
        )
        assertTrue(command.contains("systemctl is-active --quiet teamtalk"))
        assertTrue(command.contains("after_invocation"))
        assertTrue(command.contains("after_pid"))
        assertFalse(command.contains("systemctl restart"))
        assertFalse(command.contains("pkill"))
    }

    @Test
    fun `cleanup parser accepts only explicit completed recovery states`() {
        TeamTalkProcessCrashCleanupState.entries.forEach { state ->
            assertEquals(
                state,
                parseTeamTalkProcessCrashCleanupState(
                    "Warning: accepted host key\ncleanupState=${state.wireValue}",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseTeamTalkProcessCrashCleanupState("")
        }
        assertFailsWith<IllegalStateException> {
            parseTeamTalkProcessCrashCleanupState("cleanupState=unknown")
        }
        assertFailsWith<IllegalArgumentException> {
            parseTeamTalkProcessCrashCleanupState(
                "cleanupState=disarmed\ncleanupState=absent",
            )
        }
    }

    @Test
    fun `deploy path and armed identities reject traversal and shell syntax`() {
        listOf(
            "relative/teamtalk",
            "/",
            "/opt/../teamtalk",
            "/opt//teamtalk",
            "/opt/teamtalk/",
            "/opt/team talk",
            "/opt/teamtalk;shutdown",
        ).forEach { deployPath ->
            assertFailsWith<IllegalArgumentException>(deployPath) {
                RemoteTeamTalkProcessCrashConfiguration(
                    RemoteTeamTalkSshTarget("deploy.example.com", "deploy", 22),
                    deployPath,
                )
            }
        }

        listOf("", "chat/escape", "chat;shutdown", "chat\nother").forEach { chatId ->
            assertFailsWith<IllegalArgumentException>(chatId) {
                remoteTeamTalkProcessCrashArmCommand(
                    BOUNDARY,
                    DEPLOY_PATH,
                    chatId,
                    CLIENT_MSG_ID,
                )
            }
        }
        listOf(
            "ordinary-message",
            "core02-rocks-../escape",
            "core02-rocks-bad;shutdown",
            "core02-rocks-bad\nother",
        ).forEach { clientMsgId ->
            assertFailsWith<IllegalArgumentException>(clientMsgId) {
                remoteTeamTalkProcessCrashCleanupCommand(
                    BOUNDARY,
                    DEPLOY_PATH,
                    CHAT_ID,
                    clientMsgId,
                )
            }
        }
    }

    @Test
    fun `evidence parser requires the armed identity exact stage and changed process identity`() {
        val evidence = parseTeamTalkProcessCrashEvidence(
            "Warning: accepted host key\n${validEvidence()}",
            BOUNDARY,
            CHAT_ID,
            CLIENT_MSG_ID,
        )
        assertEquals(BEFORE_INVOCATION_ID, evidence.beforeInvocationId)
        assertEquals(AFTER_INVOCATION_ID, evidence.afterInvocationId)

        assertFailsWith<IllegalArgumentException> {
            parseTeamTalkProcessCrashEvidence(
                validEvidence(chatId = "other-chat"),
                BOUNDARY,
                CHAT_ID,
                CLIENT_MSG_ID,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseTeamTalkProcessCrashEvidence(
                validEvidence(clientMsgId = "core02-rocks-other"),
                BOUNDARY,
                CHAT_ID,
                CLIENT_MSG_ID,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseTeamTalkProcessCrashEvidence(
                validEvidence(stage = "AFTER_LUCENE_BEFORE_POSTGRES"),
                BOUNDARY,
                CHAT_ID,
                CLIENT_MSG_ID,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseTeamTalkProcessCrashEvidence(
                validEvidence(afterInvocationId = BEFORE_INVOCATION_ID),
                BOUNDARY,
                CHAT_ID,
                CLIENT_MSG_ID,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseTeamTalkProcessCrashEvidence(
                validEvidence(afterMainPid = "101"),
                BOUNDARY,
                CHAT_ID,
                CLIENT_MSG_ID,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseTeamTalkProcessCrashEvidence(
                validEvidence() + "\nbeforeMainPid=303",
                BOUNDARY,
                CHAT_ID,
                CLIENT_MSG_ID,
            )
        }
    }

    @Test
    fun `await and cleanup require an arm identity`() {
        val fixture = RemoteTeamTalkProcessCrash(BOUNDARY, configuration()) { _, _ ->
            throw IllegalStateException("offline")
        }

        assertFailsWith<IllegalStateException> {
            fixture.awaitHitKillAndRestart(CLIENT_MSG_ID)
        }
        assertFailsWith<IllegalStateException> {
            fixture.cleanup(CLIENT_MSG_ID)
        }
    }

    @Test
    fun `arm and cleanup failures retain identity until emergency recovery succeeds`() {
        var attempt = 0
        val fixture = RemoteTeamTalkProcessCrash(BOUNDARY, configuration()) { arguments, _ ->
            attempt += 1
            when (attempt) {
                1 -> throw IllegalStateException("arm transport failed")
                2 -> throw IllegalStateException("cleanup transport failed")
                else -> {
                    assertTrue(arguments.last().contains("cleanupState="))
                    "cleanupState=absent"
                }
            }
        }

        assertFailsWith<IllegalStateException> {
            fixture.arm(CHAT_ID, CLIENT_MSG_ID)
        }
        assertFailsWith<IllegalStateException> {
            fixture.cleanup(CLIENT_MSG_ID)
        }
        fixture.cleanup(CLIENT_MSG_ID)
        assertEquals(3, attempt)
        assertFailsWith<IllegalStateException> {
            fixture.cleanup(CLIENT_MSG_ID)
        }
    }

    @Test
    fun `invalid cleanup result retains identity for a second recovery attempt`() {
        var attempt = 0
        val fixture = RemoteTeamTalkProcessCrash(BOUNDARY, configuration()) { arguments, _ ->
            attempt += 1
            when (attempt) {
                1 -> ""
                2 -> "cleanupState=invalid"
                else -> {
                    assertTrue(arguments.last().contains("cleanupState="))
                    "cleanupState=disarmed"
                }
            }
        }

        fixture.arm(CHAT_ID, CLIENT_MSG_ID)
        assertFailsWith<IllegalStateException> {
            fixture.cleanup(CLIENT_MSG_ID)
        }
        fixture.cleanup(CLIENT_MSG_ID)
        assertEquals(3, attempt)
    }

    private fun configuration(): RemoteTeamTalkProcessCrashConfiguration =
        RemoteTeamTalkProcessCrashConfiguration(
            sshTarget = RemoteTeamTalkSshTarget("deploy.example.com", "deploy", 2222),
            deployPath = DEPLOY_PATH,
        )

    private fun clientMsgIdFor(boundary: Core02ProcessCrashBoundary): String =
        "${boundary.clientMessagePrefix}$CLIENT_MSG_ID_SUFFIX"

    private fun validEvidence(
        chatId: String = CHAT_ID,
        clientMsgId: String = CLIENT_MSG_ID,
        stage: String = "AFTER_PENDING_BEFORE_PROJECTION",
        beforeInvocationId: String = BEFORE_INVOCATION_ID,
        beforeMainPid: String = "101",
        afterInvocationId: String = AFTER_INVOCATION_ID,
        afterMainPid: String = "202",
    ): String = """
        chatId=$chatId
        clientMsgId=$clientMsgId
        stage=$stage
        beforeInvocationId=$beforeInvocationId
        beforeMainPid=$beforeMainPid
        afterInvocationId=$afterInvocationId
        afterMainPid=$afterMainPid
    """.trimIndent()

    private companion object {
        val BOUNDARY = Core02ProcessCrashBoundary.ROCKS_COMMITTED_BEFORE_PROJECTION
        const val DEPLOY_PATH = "/opt/teamtalk"
        const val CHAT_ID = "chat-core02"
        const val CLIENT_MSG_ID_SUFFIX = "00000000-0000-0000-0000-000000000001"
        const val CLIENT_MSG_ID = "core02-rocks-$CLIENT_MSG_ID_SUFFIX"
        const val BEFORE_INVOCATION_ID = "00000000000000000000000000000001"
        const val AFTER_INVOCATION_ID = "00000000000000000000000000000002"
    }
}
