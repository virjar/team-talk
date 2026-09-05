package deployment

import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResetDeploymentSafetyTest {
    @Test
    fun `reset confirmation must exactly equal configured host and canonical path`() {
        val expected = "im.virjar.com:/opt/teamtalk"
        assertEquals(expected, resetDeploymentConfirmation("im.virjar.com", "/opt/teamtalk"))
        requireResetDeploymentConfirmation(expected, "im.virjar.com", "/opt/teamtalk")

        listOf(
            null,
            "",
            " $expected",
            "$expected ",
            "root@$expected",
            "im.virjar.com:22:/opt/teamtalk",
            "other.example:/opt/teamtalk",
            "im.virjar.com:/opt/teamtalk/",
            "im.virjar.com:/opt/other",
        ).forEach { supplied ->
            val failure = assertFailsWith<GradleException>(supplied ?: "null") {
                requireResetDeploymentConfirmation(supplied, "im.virjar.com", "/opt/teamtalk")
            }
            assertTrue(failure.message.orEmpty().contains("-P$RESET_DEPLOY_CONFIRM_PROPERTY=$expected"))
        }

        assertFailsWith<IllegalArgumentException> {
            resetDeploymentConfirmation("host;shutdown", "/opt/teamtalk")
        }
        assertFailsWith<IllegalArgumentException> {
            resetDeploymentConfirmation("im.virjar.com", "/opt/../teamtalk")
        }
    }

    @Test
    fun `reset mode refuses first deploy and accepts only complete upgrade classification`() {
        requireCompleteInstallationForReset(DeploymentMode.UPGRADE)
        assertFailsWith<GradleException> {
            requireCompleteInstallationForReset(DeploymentMode.FIRST_DEPLOY)
        }

        assertFailsWith<GradleException> {
            RemoteDeploymentState(
                distributionPresent = true,
                environmentPresent = true,
                composePresent = true,
                systemdUnitPresent = true,
                dataEpochPresent = false,
                datasetIdPresent = false,
                deployPathPopulated = true,
            ).requireDeploymentMode()
        }
    }

    @Test
    fun `target identity command pins both deploy root and data to physical canonical paths`() {
        val command = resetTargetIdentityCommand("/opt/teamtalk")
        assertTrue(command.contains("test -d /opt/teamtalk"))
        assertTrue(command.contains("test ! -L /opt/teamtalk"))
        assertTrue(command.contains("readlink -f -- /opt/teamtalk"))
        assertTrue(command.contains("test -d /opt/teamtalk/data"))
        assertTrue(command.contains("test ! -L /opt/teamtalk/data"))
        assertTrue(command.contains("readlink -f -- /opt/teamtalk/data"))
        assertTrue(command.contains("/opt/teamtalk/data/data-epoch"))
        assertTrue(command.contains("/opt/teamtalk/data/dataset-id"))
        assertFalse(command.contains("rm "))
    }

    @Test
    fun `data reset command has one exact deletion and recreates every owned store`() {
        val command = clearAndRecreateDeploymentDataCommand("/opt/teamtalk")
        assertEquals(1, Regex("rm -rf -- ").findAll(command).count())
        assertTrue(command.contains("rm -rf -- /opt/teamtalk/data &&"))
        assertFalse(command.contains("rm -rf -- /opt/teamtalk &&"))
        assertFalse(command.contains("rm -rf -- / &&"))
        assertFalse(command.contains('*'))
        assertFalse(command.contains('{'))
        assertFalse(command.contains('}'))
        assertTrue(command.indexOf("readlink -f -- /opt/teamtalk") < command.indexOf("rm -rf"))

        deploymentDataDirectories("/opt/teamtalk").forEach { directory ->
            assertTrue(command.contains(directory), directory)
        }
        assertTrue(command.contains("/opt/teamtalk/data/client-telemetry-index"))
        assertTrue(
            command.endsWith(
                "test \"\$(readlink -f -- /opt/teamtalk/data)\" = /opt/teamtalk/data",
            ),
        )
    }

    @Test
    fun `compose shutdown and fresh compose remain exact to configured bind mount`() {
        val down = dockerComposeDownCommand("/srv/team-talk")
        assertTrue(down.startsWith("cd /srv/team-talk &&"))
        assertTrue(down.contains(". conf/env.sh"))
        assertTrue(down.contains("down --remove-orphans"))
        assertFalse(down.contains("down -v"))

        val compose = dockerComposeContent("/srv/team-talk")
        assertTrue(compose.contains("/srv/team-talk/data/pgdata:/var/lib/postgresql/data"))
        assertFalse(compose.contains("/opt/teamtalk"))
    }

    @Test
    fun `fresh postgres readiness treats every pg isready startup status as retryable`() {
        assertEquals(setOf(0, 1, 2, 3), POSTGRES_READINESS_EXIT_CODES)
    }

    @Test
    fun `staging snapshot and publish commands stay inside transaction children`() {
        val rollback = "/opt/teamtalk/.rollback-00000000-0000-0000-0000-000000000001"
        val staged = "/opt/teamtalk/.release-00000000-0000-0000-0000-000000000001"
        val snapshot = snapshotResetRollbackCommand("/opt/teamtalk", rollback)
        val publish = publishResetReleaseCommand("/opt/teamtalk", staged)

        assertTrue(snapshot.contains("--exclude='/data/'"))
        assertTrue(snapshot.contains("cp -a /etc/systemd/system/teamtalk.service"))
        assertTrue(snapshot.contains("$rollback/root"))
        assertTrue(publish.endsWith("$staged/ /opt/teamtalk/"))
        listOf("/data/", "/docker-compose.yml", "/conf/ssl/", "/conf/env.sh", "/static/downloads/")
            .forEach { excluded -> assertTrue(publish.contains(excluded), excluded) }

        assertFailsWith<IllegalArgumentException> {
            snapshotResetRollbackCommand("/opt/teamtalk", "/opt/other/.rollback-id")
        }
        assertFailsWith<IllegalArgumentException> {
            publishResetReleaseCommand("/opt/teamtalk", "/opt/teamtalk/release-id")
        }
        assertFailsWith<IllegalArgumentException> {
            snapshotResetRollbackCommand("/opt/teamtalk", "/opt/teamtalk/.rollback-")
        }
        assertFailsWith<IllegalArgumentException> {
            publishResetReleaseCommand("/opt/teamtalk", "/opt/teamtalk/.release-id/child")
        }
    }

    @Test
    fun `post-reset recovery message never claims destroyed data was restored`() {
        val destructive = resetDeploymentRecoveredFailureMessage(dataResetStarted = true)
        assertTrue(destructive.contains("newly empty data"))
        assertTrue(destructive.contains("was not restored"))
        assertFalse(destructive.contains("data was restored"))

        val nonDestructive = resetDeploymentRecoveredFailureMessage(dataResetStarted = false)
        assertTrue(nonDestructive.contains("before data deletion"))
        assertFalse(nonDestructive.contains("destroyed"))
    }

    @Test
    fun `first deployment directory command uses explicit paths without shell brace expansion`() {
        val command = createDeploymentDirectoriesCommand("/opt/teamtalk")
        assertTrue(command.startsWith("mkdir -p -- "))
        assertFalse(command.contains('{'))
        assertFalse(command.contains('}'))
        assertTrue(command.contains("/opt/teamtalk/data/pgdata"))
        assertTrue(command.contains("/opt/teamtalk/data/client-telemetry-index"))
        assertTrue(command.contains("/opt/teamtalk/conf/ssl"))
        assertTrue(command.contains("/opt/teamtalk/static/downloads"))
    }

    @Test
    fun `every destructive command builder rejects unsafe deployment paths`() {
        listOf("/", "/opt/../teamtalk", "/opt/teamtalk/", "/opt/team talk").forEach { unsafe ->
            assertFailsWith<IllegalArgumentException>(unsafe) {
                resetTargetIdentityCommand(unsafe)
            }
            assertFailsWith<IllegalArgumentException>(unsafe) {
                dockerComposeDownCommand(unsafe)
            }
            assertFailsWith<IllegalArgumentException>(unsafe) {
                clearAndRecreateDeploymentDataCommand(unsafe)
            }
        }
    }
}
