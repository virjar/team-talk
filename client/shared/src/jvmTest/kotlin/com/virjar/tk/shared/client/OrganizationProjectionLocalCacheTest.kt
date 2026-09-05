package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.User
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrganizationProjectionLocalCacheTest {
    @Test
    fun `revision zero authoritative empty unit snapshot is distinct from cold unknown`() {
        val cache = newMemoryCache()
        assertEquals(OrganizationUnitProjection.Unfetched, cache.getOrganizationUnitProjection())

        val lease = cache.beginOrganizationUnitSnapshot()
        assertTrue(cache.applyOrganizationUnitSnapshot(lease, emptyList(), revision = 0L))

        assertEquals(
            OrganizationUnitProjection(snapshotKnown = true, revision = 0L, units = emptyList()),
            cache.getOrganizationUnitProjection(),
        )
        cache.close()
    }

    @Test
    fun `units and members survive reopen without eagerly resident member lists`() {
        val directory = createTempDirectory("teamtalk-organization-cache").toFile()
        val databaseFile = directory.resolve("cache.db")
        val root = unit("root", null, "公司")
        val child = unit("child", root.unitId, "研发")
        val rootMember = member(root.unitId, "root-user", "根成员")
        val childMember = member(child.unitId, "child-user", "子成员")
        try {
            openCache(databaseFile.absolutePath).let { cache ->
                val unitsLease = cache.beginOrganizationUnitSnapshot()
                assertTrue(cache.applyOrganizationUnitSnapshot(unitsLease, listOf(root, child), 1L))
                val rootLease = cache.beginOrganizationMemberSnapshot(root.unitId)
                assertTrue(cache.applyOrganizationMemberSnapshot(rootLease, listOf(rootMember), 1L))
                val childLease = cache.beginOrganizationMemberSnapshot(child.unitId)
                assertTrue(cache.applyOrganizationMemberSnapshot(childLease, listOf(childMember), 1L))
                assertEquals(0, cache.residentOrganizationMemberProjectionCountForTest())
                cache.close()
            }

            openCache(databaseFile.absolutePath).let { cache ->
                assertEquals(
                    listOf(
                        root.copy(directMemberCount = 1),
                        child.copy(directMemberCount = 1),
                    ),
                    cache.getOrganizationUnitProjection().units,
                )
                assertEquals(0, cache.residentOrganizationMemberProjectionCountForTest())
                assertEquals(
                    listOf(childMember),
                    cache.getOrganizationMembersForUnits(setOf(child.unitId)),
                )
                assertEquals(
                    0,
                    cache.residentOrganizationMemberProjectionCountForTest(),
                    "recursive ACL fallback must not promote member rows to resident flows",
                )
                assertEquals(
                    OrganizationMemberProjection(true, listOf(rootMember), revision = 1L),
                    cache.getOrganizationMemberProjection(root.unitId),
                )
                assertEquals(0, cache.residentOrganizationMemberProjectionCountForTest())
                runBlocking {
                    cache.observeOrganizationMemberProjection(root.unitId).test {
                        assertEquals(
                            OrganizationMemberProjection(
                                snapshotKnown = true,
                                members = listOf(rootMember),
                                revision = 1L,
                            ),
                            awaitItem(),
                        )
                        assertEquals(1, cache.residentOrganizationMemberProjectionCountForTest())
                        cancelAndIgnoreRemainingEvents()
                    }
                }
                assertEquals(0, cache.residentOrganizationMemberProjectionCountForTest())
                cache.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `authoritative empty member snapshot remains known after reopen`() {
        val directory = createTempDirectory("teamtalk-organization-empty-cache").toFile()
        val databaseFile = directory.resolve("cache.db")
        val root = unit("root", null, "公司")
        try {
            openCache(databaseFile.absolutePath).let { cache ->
                val unitsLease = cache.beginOrganizationUnitSnapshot()
                assertTrue(cache.applyOrganizationUnitSnapshot(unitsLease, listOf(root), 1L))
                assertEquals(
                    OrganizationMemberProjection.Unfetched,
                    cache.getOrganizationMemberProjection(root.unitId),
                )

                runBlocking {
                    cache.observeOrganizationMemberProjection(root.unitId).test {
                        assertEquals(OrganizationMemberProjection.Unfetched, awaitItem())
                        val memberLease = cache.beginOrganizationMemberSnapshot(root.unitId)
                        assertTrue(cache.applyOrganizationMemberSnapshot(memberLease, emptyList(), 1L))
                        assertEquals(
                            OrganizationMemberProjection(
                                snapshotKnown = true,
                                members = emptyList(),
                                revision = 1L,
                            ),
                            awaitItem(),
                        )
                        cancelAndIgnoreRemainingEvents()
                    }
                }
                assertEquals(
                    OrganizationMemberProjection(true, emptyList(), revision = 1L),
                    cache.getOrganizationMemberProjection(root.unitId),
                )
                cache.close()
            }

            openCache(databaseFile.absolutePath).let { cache ->
                val projection = cache.getOrganizationMemberProjection(root.unitId)
                assertTrue(projection.snapshotKnown)
                assertTrue(projection.members.isEmpty())
                runBlocking {
                    cache.observeOrganizationMemberProjection(root.unitId).test {
                        assertEquals(projection, awaitItem())
                        cancelAndIgnoreRemainingEvents()
                    }
                }
                cache.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `member snapshot may arrive before its unit projection without losing authority`() {
        val cache = newMemoryCache()
        val member = member("late-unit", "u1", "先到成员")

        val memberLease = cache.beginOrganizationMemberSnapshot(member.unitId)
        assertTrue(cache.applyOrganizationMemberSnapshot(memberLease, listOf(member), 1L))
        assertEquals(
            OrganizationMemberProjection(true, listOf(member), revision = 1L),
            cache.getOrganizationMemberProjection(member.unitId),
        )

        val unitLease = cache.beginOrganizationUnitSnapshot()
        assertTrue(
            cache.applyOrganizationUnitSnapshot(
                unitLease,
                listOf(unit(member.unitId, null, "迟到部门").copy(directMemberCount = 1)),
                1L,
            ),
        )
        assertEquals(
            OrganizationMemberProjection(true, listOf(member), revision = 1L),
            cache.getOrganizationMemberProjection(member.unitId),
        )
        cache.close()
    }

    @Test
    fun `cache close completes active organization unit and member collectors`() {
        val cache = newMemoryCache()
        val root = unit("root", null, "公司")
        val unitsLease = cache.beginOrganizationUnitSnapshot()
        assertTrue(cache.applyOrganizationUnitSnapshot(unitsLease, listOf(root), 1L))
        val rootMember = member(root.unitId, "root-user", "根成员")
        val memberLease = cache.beginOrganizationMemberSnapshot(root.unitId)
        assertTrue(cache.applyOrganizationMemberSnapshot(memberLease, listOf(rootMember), 1L))

        runBlocking {
            cache.observeOrganizationUnitProjection().map { it.units }.test {
                assertEquals(listOf(root.copy(directMemberCount = 1)), awaitItem())
                cache.observeOrganizationMemberProjection(root.unitId).test {
                    assertEquals(
                        OrganizationMemberProjection(
                            snapshotKnown = true,
                            members = listOf(rootMember),
                            revision = 1L,
                        ),
                        awaitItem(),
                    )
                    cache.close()
                    awaitComplete()
                }
                awaitComplete()
            }
        }
    }

    @Test
    fun `latest request and local mutations fence unit and direct member snapshots`() {
        val cache = newMemoryCache()
        val oldUnit = unit("root", null, "旧名称")
        val currentUnit = oldUnit.copy(name = "当前名称")
        val oldUnitsLease = cache.beginOrganizationUnitSnapshot()
        val currentUnitsLease = cache.beginOrganizationUnitSnapshot()

        assertFalse(cache.applyOrganizationUnitSnapshot(oldUnitsLease, listOf(oldUnit), 1L))
        assertTrue(cache.applyOrganizationUnitSnapshot(currentUnitsLease, listOf(currentUnit), 1L))

        val staleUnitsLease = cache.beginOrganizationUnitSnapshot()
        val locallyUpdated = currentUnit.copy(name = "本地事件名称")
        cache.upsertOrganizationUnit(locallyUpdated)
        assertFalse(cache.applyOrganizationUnitSnapshot(staleUnitsLease, emptyList(), 1L))
        assertEquals(listOf(locallyUpdated), cache.getOrganizationUnitProjection().units)

        val staleMember = member(currentUnit.unitId, "u1", "旧成员")
        val freshMember = staleMember.copy(title = "本地事件职务")
        val staleMemberLease = cache.beginOrganizationMemberSnapshot(currentUnit.unitId)
        cache.upsertOrganizationMember(freshMember)
        assertFalse(cache.applyOrganizationMemberSnapshot(staleMemberLease, listOf(staleMember), 1L))
        assertEquals(
            listOf(freshMember),
            cache.getOrganizationMemberProjection(currentUnit.unitId).members,
        )
        assertFalse(cache.getOrganizationMemberProjection(currentUnit.unitId).snapshotKnown)
        cache.close()
    }

    @Test
    fun `USER_UPDATED revision wins inside an older member snapshot without dropping membership`() {
        val cache = newMemoryCache()
        val embeddedA = member("root", "u1", "A")
        cache.beginOrganizationMemberSnapshot(embeddedA.unitId).also { lease ->
            assertTrue(cache.applyOrganizationMemberSnapshot(lease, listOf(embeddedA), revision = 1L))
        }

        val oldResponseLease = cache.beginOrganizationMemberSnapshot(embeddedA.unitId)
        val eventUserB = requireNotNull(embeddedA.user).copy(name = "B", revision = 2L)
        assertTrue(cache.upsertTransientUserIfRelevant(eventUserB))

        assertTrue(
            cache.applyOrganizationMemberSnapshot(oldResponseLease, listOf(embeddedA), revision = 1L),
        )
        assertEquals(
            eventUserB,
            cache.getOrganizationMemberProjection(embeddedA.unitId).members.single().user,
        )

        val laterSnapshotUser = eventUserB.copy(name = "C", revision = 3L)
        cache.beginOrganizationMemberSnapshot(embeddedA.unitId).also { lease ->
            assertTrue(
                cache.applyOrganizationMemberSnapshot(
                    lease,
                    listOf(embeddedA.copy(user = laterSnapshotUser)),
                    revision = 1L,
                ),
            )
        }
        assertEquals(
            laterSnapshotUser,
            cache.getOrganizationMemberProjection(embeddedA.unitId).members.single().user,
            "a member snapshot started after the event remains authoritative for organization data",
        )
        assertEquals(
            laterSnapshotUser,
            cache.getUser(laterSnapshotUser.uid),
            "a newer organization snapshot must advance the normalized user projection",
        )
        cache.close()
    }

    @Test
    fun `profile snapshot revision canonicalizes an overlapping organization response`() {
        val cache = newMemoryCache()
        val embeddedA = member("root", "u1", "A")
        cache.beginOrganizationMemberSnapshot(embeddedA.unitId).also { lease ->
            assertTrue(cache.applyOrganizationMemberSnapshot(lease, listOf(embeddedA), revision = 1L))
        }

        val oldOrganizationLease = cache.beginOrganizationMemberSnapshot(embeddedA.unitId)
        val profileB = requireNotNull(embeddedA.user).copy(name = "B", revision = 2L)
        cache.beginUserSnapshot(profileB.uid).also { userLease ->
            assertTrue(cache.applyUserSnapshot(userLease, profileB))
        }

        assertTrue(
            cache.applyOrganizationMemberSnapshot(
                oldOrganizationLease,
                listOf(embeddedA),
                revision = 1L,
            ),
        )
        assertEquals(
            profileB,
            cache.getOrganizationMemberProjection(embeddedA.unitId).members.single().user,
        )
        cache.close()
    }

    @Test
    fun `retained unit snapshot fences an older in-flight member response`() {
        val cache = newMemoryCache()
        val initial = unit("root", null, "旧组织").copy(directMemberCount = 0)
        val initialUnitsLease = cache.beginOrganizationUnitSnapshot()
        assertTrue(cache.applyOrganizationUnitSnapshot(initialUnitsLease, listOf(initial), 1L))

        val staleMember = member(initial.unitId, "stale-user", "旧成员")
        val olderMemberLease = cache.beginOrganizationMemberSnapshot(initial.unitId)
        val newerUnitLease = cache.beginOrganizationUnitSnapshot()
        val current = initial.copy(name = "新组织", directMemberCount = 7)

        assertTrue(cache.applyOrganizationUnitSnapshot(newerUnitLease, listOf(current), 1L))
        assertFalse(cache.applyOrganizationMemberSnapshot(olderMemberLease, listOf(staleMember), 1L))
        assertEquals(listOf(current), cache.getOrganizationUnitProjection().units)
        assertEquals(
            OrganizationMemberProjection.Unfetched,
            cache.getOrganizationMemberProjection(initial.unitId),
        )
        cache.close()
    }

    @Test
    fun `authoritative empty snapshots prune removed units and members atomically`() {
        val cache = newMemoryCache()
        val root = unit("root", null, "公司")
        val child = unit("child", root.unitId, "研发")
        val unitsLease = cache.beginOrganizationUnitSnapshot()
        assertTrue(cache.applyOrganizationUnitSnapshot(unitsLease, listOf(root, child), 1L))
        val childMember = member(child.unitId, "u1", "成员")
        val childLease = cache.beginOrganizationMemberSnapshot(child.unitId)
        assertTrue(cache.applyOrganizationMemberSnapshot(childLease, listOf(childMember), 1L))

        val responseInFlight = cache.beginOrganizationMemberSnapshot(child.unitId)
        val pruneLease = cache.beginOrganizationUnitSnapshot()
        assertTrue(cache.applyOrganizationUnitSnapshot(pruneLease, listOf(root), 1L))

        assertFalse(cache.applyOrganizationMemberSnapshot(responseInFlight, listOf(childMember), 1L))
        assertEquals(
            OrganizationMemberProjection.Unfetched,
            cache.getOrganizationMemberProjection(child.unitId),
        )
        assertEquals(listOf(root), cache.getOrganizationUnitProjection().units)

        val rootEmptyLease = cache.beginOrganizationMemberSnapshot(root.unitId)
        assertTrue(cache.applyOrganizationMemberSnapshot(rootEmptyLease, emptyList(), 1L))
        assertTrue(cache.getOrganizationMemberProjection(root.unitId).snapshotKnown)

        val emptyLease = cache.beginOrganizationUnitSnapshot()
        assertTrue(cache.applyOrganizationUnitSnapshot(emptyLease, emptyList(), 1L))
        assertTrue(cache.getOrganizationUnitProjection().units.isEmpty())
        assertEquals(
            OrganizationMemberProjection.Unfetched,
            cache.getOrganizationMemberProjection(root.unitId),
        )

        val unknownMember = member("unknown", "late-user", "迟到成员")
        val unknownMemberLease = cache.beginOrganizationMemberSnapshot(unknownMember.unitId)
        val stillEmptyLease = cache.beginOrganizationUnitSnapshot()
        assertTrue(cache.applyOrganizationUnitSnapshot(stillEmptyLease, emptyList(), 1L))
        assertFalse(cache.applyOrganizationMemberSnapshot(unknownMemberLease, listOf(unknownMember), 1L))
        assertEquals(
            OrganizationMemberProjection.Unfetched,
            cache.getOrganizationMemberProjection(unknownMember.unitId),
        )
        cache.close()
    }

    @Test
    fun `cold unknown authoritative empty and stale rows remain distinct across reopen`() {
        val directory = createTempDirectory("teamtalk-organization-revision-cache").toFile()
        val databaseFile = directory.resolve("cache.db")
        val root = unit("root", null, "公司")
        val rootMember = member(root.unitId, "u1", "成员")
        try {
            openCache(databaseFile.absolutePath).let { cache ->
                assertEquals(OrganizationUnitProjection.Unfetched, cache.getOrganizationUnitProjection())
                cache.close()
            }
            openCache(databaseFile.absolutePath).let { cache ->
                assertEquals(
                    OrganizationUnitProjection.Unfetched,
                    cache.getOrganizationUnitProjection(),
                    "a reopened never-fetched empty directory must remain unknown",
                )
                cache.beginOrganizationUnitSnapshot().also { lease ->
                    assertTrue(cache.applyOrganizationUnitSnapshot(lease, listOf(root), revision = 5L))
                }
                cache.beginOrganizationMemberSnapshot(root.unitId).also { lease ->
                    assertTrue(
                        cache.applyOrganizationMemberSnapshot(
                            lease,
                            listOf(rootMember),
                            revision = 5L,
                        ),
                    )
                }
                assertEquals(6L, cache.advanceOrganizationRequiredRevision(6L))
                cache.close()
            }
            openCache(databaseFile.absolutePath).let { cache ->
                val staleUnits = cache.getOrganizationUnitProjection()
                assertFalse(staleUnits.snapshotKnown)
                assertEquals(5L, staleUnits.revision)
                assertEquals(listOf(root.copy(directMemberCount = 1)), staleUnits.units)
                val staleMembers = cache.getOrganizationMemberProjection(root.unitId)
                assertFalse(staleMembers.snapshotKnown)
                assertEquals(5L, staleMembers.revision)
                assertEquals(listOf(rootMember), staleMembers.members)

                cache.beginOrganizationUnitSnapshot().also { lease ->
                    assertTrue(cache.applyOrganizationUnitSnapshot(lease, emptyList(), revision = 6L))
                }
                cache.close()
            }
            openCache(databaseFile.absolutePath).let { cache ->
                assertEquals(
                    OrganizationUnitProjection(snapshotKnown = true, revision = 6L, units = emptyList()),
                    cache.getOrganizationUnitProjection(),
                    "an authoritative empty directory must survive reopen as known",
                )
                cache.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `required revision is monotonic and stale snapshot responses cannot regain authority`() {
        val cache = newMemoryCache()
        val root = unit("root", null, "公司")
        val rootMember = member(root.unitId, "u1", "成员")
        cache.beginOrganizationUnitSnapshot().also { lease ->
            assertTrue(cache.applyOrganizationUnitSnapshot(lease, listOf(root), revision = 5L))
        }
        cache.beginOrganizationMemberSnapshot(root.unitId).also { lease ->
            assertTrue(cache.applyOrganizationMemberSnapshot(lease, listOf(rootMember), revision = 5L))
        }

        assertEquals(7L, cache.advanceOrganizationRequiredRevision(7L))
        assertEquals(7L, cache.advanceOrganizationRequiredRevision(6L))
        assertFalse(cache.getOrganizationUnitProjection().snapshotKnown)
        assertFalse(cache.getOrganizationMemberProjection(root.unitId).snapshotKnown)

        cache.beginOrganizationUnitSnapshot().also { lease ->
            assertFalse(cache.applyOrganizationUnitSnapshot(lease, listOf(root), revision = 6L))
        }
        cache.beginOrganizationMemberSnapshot(root.unitId).also { lease ->
            assertFalse(cache.applyOrganizationMemberSnapshot(lease, listOf(rootMember), revision = 6L))
        }
        cache.beginOrganizationUnitSnapshot().also { lease ->
            assertTrue(cache.applyOrganizationUnitSnapshot(lease, listOf(root), revision = 7L))
        }
        assertTrue(cache.getOrganizationUnitProjection().snapshotKnown)
        assertFalse(cache.getOrganizationMemberProjection(root.unitId).snapshotKnown)
        cache.beginOrganizationMemberSnapshot(root.unitId).also { lease ->
            assertTrue(cache.applyOrganizationMemberSnapshot(lease, listOf(rootMember), revision = 7L))
        }
        assertTrue(cache.getOrganizationMemberProjection(root.unitId).snapshotKnown)
        cache.close()
    }

    @Test
    fun `member completion cannot fence a concurrent newer unit snapshot`() {
        val cache = newMemoryCache()
        val root = unit("root", null, "旧公司")
        cache.beginOrganizationUnitSnapshot().also { lease ->
            assertTrue(cache.applyOrganizationUnitSnapshot(lease, listOf(root), revision = 1L))
        }

        val newerUnitLease = cache.beginOrganizationUnitSnapshot()
        val member = member(root.unitId, "u1", "成员")
        cache.beginOrganizationMemberSnapshot(root.unitId).also { lease ->
            assertTrue(cache.applyOrganizationMemberSnapshot(lease, listOf(member), revision = 2L))
        }
        val newerRoot = root.copy(name = "新公司", directMemberCount = 1)

        assertTrue(cache.applyOrganizationUnitSnapshot(newerUnitLease, listOf(newerRoot), revision = 3L))
        assertEquals(
            OrganizationUnitProjection(snapshotKnown = true, revision = 3L, units = listOf(newerRoot)),
            cache.getOrganizationUnitProjection(),
        )
        val staleMembers = cache.getOrganizationMemberProjection(root.unitId)
        assertFalse(staleMembers.snapshotKnown)
        assertEquals(2L, staleMembers.revision)
        assertEquals(listOf(member), staleMembers.members)
        cache.close()
    }

    @Test
    fun `dataset reset clears organization rows and required revision across reopen`() {
        val directory = createTempDirectory("teamtalk-organization-reset-cache").toFile()
        val databaseFile = directory.resolve("cache.db")
        val root = unit("root", null, "公司")
        try {
            openCache(databaseFile.absolutePath).let { cache ->
                cache.beginOrganizationUnitSnapshot().also { lease ->
                    assertTrue(cache.applyOrganizationUnitSnapshot(lease, listOf(root), revision = 5L))
                }
                cache.advanceOrganizationRequiredRevision(7L)
                cache.resetServerProjection(TEST_SYNC_DATASET_ID)
                assertEquals(OrganizationUnitProjection.Unfetched, cache.getOrganizationUnitProjection())
                cache.close()
            }
            openCache(databaseFile.absolutePath).let { cache ->
                assertEquals(OrganizationUnitProjection.Unfetched, cache.getOrganizationUnitProjection())
                cache.beginOrganizationUnitSnapshot().also { lease ->
                    assertTrue(cache.applyOrganizationUnitSnapshot(lease, emptyList(), revision = 1L))
                }
                assertEquals(
                    OrganizationUnitProjection(snapshotKnown = true, revision = 1L, units = emptyList()),
                    cache.getOrganizationUnitProjection(),
                )
                cache.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun newMemoryCache(): LocalCacheImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private fun openCache(path: String): LocalCacheImpl {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private fun unit(unitId: String, parentId: String?, name: String) = OrganizationUnit(
        unitId = unitId,
        parentId = parentId,
        name = name,
    )

    private fun member(unitId: String, uid: String, name: String) = OrganizationMember(
        unitId = unitId,
        uid = uid,
        title = "工程师",
        primary = true,
        joinedAt = 10,
        user = User(uid = uid, username = uid, name = name),
    )
}
