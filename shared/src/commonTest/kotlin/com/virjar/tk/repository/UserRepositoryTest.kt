package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.model.ProfilePatch
import com.virjar.tk.model.ProfilePatchValue
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.rpc.gen.UserRpcContract
import com.virjar.tk.testing.FakeLocalCache
import com.virjar.tk.testing.FakeRpcInvoker
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UserRepositoryTest {
    @Test
    fun `update profile forwards the exact presence-aware patch`() = runBlocking {
        val patch = ProfilePatch(
            name = ProfilePatchValue.Unchanged,
            avatar = ProfilePatchValue.Set(null),
            sex = ProfilePatchValue.Set(2),
            phone = ProfilePatchValue.Set("13800000000"),
        )
        val rpc = FakeRpcInvoker().apply { enqueueOk() }
        val repository = UserRepository(rpc, FakeLocalCache())

        assertIs<Outcome.Success<Unit>>(repository.updateProfile(patch))

        val call = rpc.calls.single()
        assertEquals(UserRpcContract.SERVICE, call.first)
        assertEquals(UserRpcContract.M_UPDATE_PROFILE, call.second)
        assertEquals(patch, ProtoCodec.decode(ProfilePatch, requireNotNull(call.third)))
    }
}
