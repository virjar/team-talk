package com.virjar.tk.server.infra.sync

import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.OrganizationChangedPayload
import com.virjar.tk.protocol.ProtoCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientRegistryOrganizationChangeTest {
    @Test
    fun `organization change uses one transient bounded binary notification`() {
        val notify = organizationChangedNotify(Long.MAX_VALUE)

        assertEquals(0L, notify.eventId)
        assertEquals(NotifyType.ORGANIZATION_CHANGED.code, notify.notifyType)
        assertEquals(
            OrganizationChangedPayload(Long.MAX_VALUE),
            ProtoCodec.decode(OrganizationChangedPayload, requireNotNull(notify.payload)),
        )
    }

    @Test
    fun `broadcast skips inactive sessions and isolates each failed connection`() {
        val targets = sequenceOf(
            Target("first", active = true),
            Target("inactive", active = false),
            Target("broken", active = true),
            Target("last", active = true),
        )
        val attempted = mutableListOf<String>()
        val failed = mutableListOf<String>()

        deliverTransientBroadcast(
            targets = targets,
            isActive = Target::active,
            deliver = { target ->
                attempted += target.id
                if (target.id == "broken") throw InjectedDeliveryFailure()
            },
            onFailure = { target, _ -> failed += target.id },
        )

        assertEquals(listOf("first", "broken", "last"), attempted)
        assertEquals(listOf("broken"), failed)
    }

    private data class Target(val id: String, val active: Boolean)
    private class InjectedDeliveryFailure : RuntimeException("injected connection failure")
}
