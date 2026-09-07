package com.virjar.tk.app.client

import com.virjar.tk.shared.client.AccountDataOwner
import com.virjar.tk.shared.client.AuthenticationFailure
import com.virjar.tk.shared.client.AuthenticationFailureKind
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.StoredLogin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BannedAccountDataOwnerTest {
    private val deployment = DeploymentIdentity.from("127.0.0.1", 5100, "http://127.0.0.1:8080")
    private val dataset = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val saved = StoredLogin("user-a", "refresh-a", 1, deployment.fingerprint, dataset)

    @Test
    fun `password refusal uses server-confirmed account without requiring a previous login`() {
        val failure = AuthenticationFailure(
            AuthenticationFailureKind.ACCOUNT_BANNED, "账号已被封禁", accountUid = "user-b", datasetId = dataset,
        )
        assertEquals(
            AccountDataOwner(deployment.fingerprint, dataset, "user-b"),
            bannedAccountDataOwner(deployment, failure, null),
        )
    }

    @Test
    fun `legacy banned refresh response can only use saved identity from the same deployment`() {
        val failure = AuthenticationFailure(AuthenticationFailureKind.ACCOUNT_BANNED, "账号已被封禁")
        assertEquals(
            AccountDataOwner(deployment.fingerprint, dataset, saved.uid),
            bannedAccountDataOwner(deployment, failure, saved),
        )
        assertNull(bannedAccountDataOwner(deployment, failure, saved.copy(deploymentFingerprint = "b".repeat(64))))
        assertNull(bannedAccountDataOwner(deployment, failure, null))
    }

    @Test
    fun `normal authentication failures cannot acquire deletion scope even with account fields`() {
        AuthenticationFailureKind.entries.filter { it != AuthenticationFailureKind.ACCOUNT_BANNED }.forEach { kind ->
            val failure = AuthenticationFailure(kind, "拒绝", accountUid = saved.uid, datasetId = dataset)
            assertNull(bannedAccountDataOwner(deployment, failure, saved), kind.name)
        }
        assertNull(bannedAccountDataOwner(deployment, null, saved))
    }

    @Test
    fun `invalid authoritative identity never falls back to deleting the remembered account`() {
        val failure = AuthenticationFailure(
            AuthenticationFailureKind.ACCOUNT_BANNED, "账号已被封禁", accountUid = "../other", datasetId = dataset,
        )
        assertNull(bannedAccountDataOwner(deployment, failure, saved))
        assertNull(bannedAccountDataOwner(deployment, failure.copy(accountUid = saved.uid, datasetId = "invalid"), saved))
    }

    @Test
    fun `partial authoritative identity cannot borrow missing fields from a saved login`() {
        val failure = AuthenticationFailure(
            AuthenticationFailureKind.ACCOUNT_BANNED, "账号已被封禁", accountUid = saved.uid, datasetId = dataset,
        )
        assertNull(bannedAccountDataOwner(deployment, failure.copy(accountUid = null), saved))
        assertNull(bannedAccountDataOwner(deployment, failure.copy(datasetId = null), saved))
    }

    @Test
    fun `refresh ban must match its fixed user and deployment while server dataset remains authoritative`() {
        val failure = AuthenticationFailure(
            AuthenticationFailureKind.ACCOUNT_BANNED, "账号已被封禁", accountUid = saved.uid, datasetId = dataset,
        )
        assertEquals(
            AccountDataOwner(deployment.fingerprint, dataset, saved.uid),
            bannedAccountDataOwner(deployment, failure, saved),
        )
        assertNull(bannedAccountDataOwner(deployment, failure.copy(accountUid = "user-b"), saved))
        assertNull(bannedAccountDataOwner(deployment, failure, saved.copy(deploymentFingerprint = "b".repeat(64))))

        val replacementDataset = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        assertEquals(
            AccountDataOwner(deployment.fingerprint, replacementDataset, saved.uid),
            bannedAccountDataOwner(deployment, failure.copy(datasetId = replacementDataset), saved),
        )
    }
}
