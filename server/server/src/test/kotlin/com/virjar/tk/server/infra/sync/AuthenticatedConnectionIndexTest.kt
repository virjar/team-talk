package com.virjar.tk.server.infra.sync

import com.virjar.tk.server.domain.auth.AuthenticatedDevicePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class AuthenticatedConnectionIndexTest {
    @Test
    fun `distinct devices are bounded while users own independent capacity`() {
        val index = index(capacity = 2)
        val first = Session("user-a", "device-a", "session-a")
        val second = Session("user-a", "device-b", "session-b")

        assertIs<IndexedConnectionAdmission.Admitted<Session>>(index.admit(first))
        assertIs<IndexedConnectionAdmission.Admitted<Session>>(index.admit(second))
        assertSame(
            IndexedConnectionAdmission.LimitReached,
            index.admit(Session("user-a", "device-c", "session-c")),
        )
        assertEquals(2, index.sizeFor("user-a"))

        assertIs<IndexedConnectionAdmission.Admitted<Session>>(
            index.admit(Session("user-b", "device-c", "session-d")),
        )
        assertEquals(1, index.sizeFor("user-b"))
    }

    @Test
    fun `same-device replacement is atomic at capacity and release opens one slot`() {
        val index = index(capacity = AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER)
        val original = Session("user", "device-0", "old")
        assertIs<IndexedConnectionAdmission.Admitted<Session>>(index.admit(original))
        repeat(AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER - 1) { offset ->
            assertIs<IndexedConnectionAdmission.Admitted<Session>>(
                index.admit(Session("user", "device-${offset + 1}", "session-${offset + 1}")),
            )
        }

        val replacement = Session("user", "device-0", "new")
        val admitted = assertIs<IndexedConnectionAdmission.Admitted<Session>>(index.admit(replacement))
        assertEquals(listOf(original), admitted.replaced)
        assertEquals(AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER, index.sizeFor("user"))
        assertEquals(listOf(replacement), index.forDevice("user", "device-0"))

        assertSame(
            IndexedConnectionAdmission.LimitReached,
            index.admit(Session("user", "overflow", "overflow")),
        )
        index.remove(replacement)
        assertIs<IndexedConnectionAdmission.Admitted<Session>>(
            index.admit(Session("user", "overflow", "accepted-after-release")),
        )
        assertEquals(AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER, index.sizeFor("user"))
    }

    private fun index(capacity: Int) = AuthenticatedConnectionIndex<Session>(
        capacity = capacity,
        uidOf = Session::uid,
        deviceIdOf = Session::deviceId,
    )

    private data class Session(
        val uid: String,
        val deviceId: String,
        val sessionId: String,
    )
}
