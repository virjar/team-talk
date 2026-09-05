package com.virjar.tk.server.infra.sync

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientRegistryUserProfileChangeTest {
    @Test
    fun `profile change uses one transient full User notification`() {
        val user = User(
            uid = "changed-user",
            username = "changed",
            name = "Changed User",
            avatar = Attachment("changed-user/avatar.png", "avatar.png", "image/png", 123),
        )

        val notify = userProfileChangedNotify(user)

        assertEquals(0L, notify.eventId)
        assertEquals(NotifyType.USER_UPDATED.code, notify.notifyType)
        assertEquals(user, ProtoCodec.decode(User, requireNotNull(notify.payload)))
    }

    @Test
    fun `profile broadcast excludes durable users skips inactive and isolates failed sessions`() {
        val targets = sequenceOf(
            Target("owner-device", "owner", active = true),
            Target("friend-device", "friend", active = true),
            Target("inactive-device", "outsider-inactive", active = false),
            Target("broken-device", "outsider-broken", active = true),
            Target("first-device", "outsider-live", active = true),
            Target("second-device", "outsider-live", active = true),
        )
        val attempted = mutableListOf<String>()
        val failed = mutableListOf<String>()

        deliverUserProfileBroadcast(
            targets = targets,
            excludedUids = setOf("owner", "friend"),
            uidOf = Target::uid,
            isActive = Target::active,
            deliver = { target ->
                attempted += target.deviceId
                if (target.deviceId == "broken-device") throw InjectedDeliveryFailure()
            },
            onFailure = { target, _ -> failed += target.deviceId },
        )

        assertEquals(listOf("broken-device", "first-device", "second-device"), attempted)
        assertEquals(listOf("broken-device"), failed)
    }

    private data class Target(val deviceId: String, val uid: String, val active: Boolean)
    private class InjectedDeliveryFailure : RuntimeException("injected connection failure")
}
