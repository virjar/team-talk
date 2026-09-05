package com.virjar.tk.app.client

import com.virjar.tk.shared.client.AuthenticationFailure
import com.virjar.tk.shared.client.AuthenticationFailureKind
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.StoredLogin
import com.virjar.tk.shared.client.TokenStore
import com.virjar.tk.shared.client.TokenStoreOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthControllerProtocolUpgradeFenceTest {
    @Test
    fun `server too old blocks this workspace but leaves restart free to negotiate with retained login`() {
        val store = FenceTokenStore()
        val owner = claim(store)
        val failure = AuthenticationFailure(
            AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED,
            "server must upgrade",
            requiresClientUpgrade = false,
        )

        persistObservedProtocolRefusal(owner, 0, failure)

        assertTrue(requiresForcedProtocolUpgrade(failure))
        val retirement = authControllerRetirementCause(failure)
        assertEquals(AuthControllerRetirementCause.PROTOCOL_UPGRADE, retirement)
        assertEquals(StoredLoginRetirementDisposition.PRESERVE, retirement.storedLoginDisposition)
        assertFalse(retirement.mayContinueOffline(hasLocalSessionOwner = true, persistedIdentityOwned = true))
        val restarted = claim(store)
        assertFalse(restarted.requiresProtocolUpgrade(0))
        assertNotNull(restarted.savedLoginSnapshot())
        assertEquals(0, store.credentialClears)
    }

    @Test
    fun `retired client version remains fenced across owner handoff with credentials retained`() {
        val store = FenceTokenStore()
        persistObservedProtocolRefusal(
            claim(store),
            0,
            AuthenticationFailure(AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED, "client must upgrade"),
        )

        val restarted = claim(store)
        assertTrue(restarted.requiresProtocolUpgrade(0))
        assertFalse(restarted.requiresProtocolUpgrade(1))
        assertNotNull(restarted.savedLoginSnapshot())
        assertEquals(0, store.credentialClears)
    }

    private fun claim(store: FenceTokenStore) = AuthControllerCredentialOwner.claim(
        store, store.deploymentIdentity, "127.0.0.1", 5100,
    )

    private class FenceTokenStore : TokenStore {
        override val deploymentIdentity = DeploymentIdentity.from("127.0.0.1", 5100, "http://127.0.0.1:8080")
        override val rejectedProtocolVersions = MutableStateFlow(emptySet<Int>())
        private var generation = 0L
        private var login: StoredLogin? = StoredLogin(
            "user-a", "refresh-a", generation, deploymentIdentity.fingerprint,
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        )
        var credentialClears = 0
            private set

        override fun claimOwner(): TokenStoreOwner {
            generation += 1
            login = login?.copy(ownerGeneration = generation)
            return TokenStoreOwner(generation, login, rejectedProtocolVersions.value)
        }

        override fun save(ownerGeneration: Long, uid: String, refreshToken: String, datasetId: String): StoredLogin? =
            error("This regression must not authenticate or overwrite credentials")

        override fun compareAndClear(expected: StoredLogin): Boolean {
            if (login != expected) return false
            login = null
            credentialClears += 1
            return true
        }

        override fun isCurrentOwner(ownerGeneration: Long): Boolean = ownerGeneration == generation

        override fun markProtocolVersionRejected(protocolVersion: Int): Boolean {
            rejectedProtocolVersions.value += protocolVersion
            return true
        }
    }
}
