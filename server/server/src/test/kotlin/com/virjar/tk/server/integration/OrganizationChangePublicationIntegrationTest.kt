package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.organization.OrganizationChangePublisher
import com.virjar.tk.server.domain.organization.OrganizationService
import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.server.infra.db.PgUnitOfWorkStage
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.OrganizationUnitPageRequest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrganizationChangePublicationIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `successful organization command publishes its committed revision exactly once`() = runTest {
        val root = organizationRoot()
        val published = mutableListOf<Long>()
        val service = service(ctx.pgUnitOfWork, OrganizationChangePublisher { published += it })
        val beforeRevision = currentRevision()

        val created = service.createUnit(root.unitId, uniqueName("published"), null)
        val committedRevision = currentRevision()

        assertEquals(beforeRevision + 1L, committedRevision)
        assertEquals(listOf(committedRevision), published)
        assertNotNull(ctx.organizationRepo.findUnit(created.unitId))
    }

    @Test
    fun `rolled back organization command does not publish`() = runTest {
        val root = organizationRoot()
        val name = uniqueName("rolled-back")
        val published = mutableListOf<Long>()
        val beforeRevision = currentRevision()
        val failingUnitOfWork = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { stage ->
                if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                    throw InjectedOrganizationCommandRollback()
                }
            },
        )

        assertFailsWith<InjectedOrganizationCommandRollback> {
            service(failingUnitOfWork, OrganizationChangePublisher { published += it })
                .createUnit(root.unitId, name, null)
        }

        assertEquals(beforeRevision, currentRevision())
        assertTrue(ctx.organizationService.listUnits().none { it.name == name })
        assertTrue(published.isEmpty())
    }

    @Test
    fun `notification failure cannot turn a committed command into an apparent failure`() = runTest {
        val root = organizationRoot()
        var attempts = 0
        val service = service(
            ctx.pgUnitOfWork,
            OrganizationChangePublisher {
                attempts += 1
                throw InjectedOrganizationNotificationFailure()
            },
        )

        val created = service.createUnit(root.unitId, uniqueName("notification-failure"), null)

        assertEquals(1, attempts)
        assertNotNull(ctx.organizationRepo.findUnit(created.unitId))
    }

    private fun service(
        unitOfWork: com.virjar.tk.server.domain.transaction.PgUnitOfWork,
        publisher: OrganizationChangePublisher,
    ): OrganizationService = OrganizationService(
        repository = ctx.organizationRepo,
        users = ctx.userRepo,
        unitOfWork = unitOfWork,
        projector = ctx.organizationProjector,
        changes = publisher,
    )

    private suspend fun organizationRoot(): OrganizationUnit =
        ctx.organizationService.listUnits().singleOrNull { it.parentId == null }
            ?: ctx.organizationService.createUnit(null, uniqueName("root"), null)

    private fun currentRevision(): Long =
        ctx.organizationService.listUnitPage(OrganizationUnitPageRequest(cursor = null)).revision

    private fun uniqueName(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    private class InjectedOrganizationCommandRollback : RuntimeException("injected rollback")
    private class InjectedOrganizationNotificationFailure : RuntimeException("injected notify failure")
}
