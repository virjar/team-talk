package com.virjar.tk.android

import android.content.Context
import android.content.SharedPreferences
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.StoredLogin
import com.virjar.tk.shared.client.TokenStoreOwner
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 登录态持久化（SharedPreferences）。
 *
 * 存储认证成功后的 uid + refreshToken，使 app 重启后能自动登录（直达主界面），
 * 而非每次都走登录页。refreshToken 当前有效期 90 天，由服务端 PostgreSQL 凭据表管理。
 *
 * 清除时机：用户主动登出、token 失效（AUTH_FAILED）、被踢下线。
 */
class TokenStore(
    context: Context,
    override val deploymentIdentity: DeploymentIdentity,
) : com.virjar.tk.shared.client.TokenStore {
    override val ownerClaimNamespace: String = ANDROID_AUTH_OWNER_NAMESPACE
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("teamtalk_auth", Context.MODE_PRIVATE)
    private val protocolRejectionState = synchronized(PROCESS_LOCK) {
        PROCESS_PROTOCOL_REJECTIONS.entries.removeAll { it.value.get() == null }
        PROCESS_PROTOCOL_REJECTIONS[deploymentIdentity.fingerprint]?.get()
            ?: MutableStateFlow<Set<Int>>(emptySet()).also { state ->
                PROCESS_PROTOCOL_REJECTIONS[deploymentIdentity.fingerprint] = WeakReference(state)
            }
    }
    override val rejectedProtocolVersions: StateFlow<Set<Int>> =
        protocolRejectionState.asStateFlow()

    override fun claimOwner(): TokenStoreOwner = synchronized(PROCESS_LOCK) {
        val claimed = claimAndroidAuthOwner(readState(), deploymentIdentity.fingerprint)
        val mergedRejections = claimed.state.rejectedProtocolVersions + protocolRejectionState.value
        protocolRejectionState.value = mergedRejections
        persist(claimed.state.copy(rejectedProtocolVersions = mergedRejections))
        claimed.owner.copy(rejectedProtocolVersions = mergedRejections)
    }

    override fun save(
        ownerGeneration: Long,
        uid: String,
        refreshToken: String,
        datasetId: String,
    ): StoredLogin? =
        synchronized(PROCESS_LOCK) {
            val mutation = saveAndroidAuthLogin(
                readState(),
                deploymentIdentity.fingerprint,
                ownerGeneration,
                uid,
                refreshToken,
                datasetId,
            )
            if (!mutation.applied) return@synchronized null
            if (mutation.persistenceRequired) persist(mutation.state)
            mutation.state.toStoredLogin()
        }

    override fun compareAndClear(expected: StoredLogin): Boolean = synchronized(PROCESS_LOCK) {
        if (expected.deploymentFingerprint != deploymentIdentity.fingerprint) return@synchronized false
        val mutation = clearAndroidAuthLogin(readState(), expected)
        if (!mutation.applied) return@synchronized false
        persist(mutation.state)
        true
    }

    override fun isCurrentOwner(ownerGeneration: Long): Boolean = synchronized(PROCESS_LOCK) {
        readState().let { state ->
            state.ownerGeneration == ownerGeneration &&
                state.deploymentFingerprint == deploymentIdentity.fingerprint
        }
    }

    override fun markProtocolVersionRejected(protocolVersion: Int): Boolean = synchronized(PROCESS_LOCK) {
        require(protocolVersion in PROTOCOL_VERSION_RANGE) { "协议版本 ID 必须非负" }
        // 在存储之前先发布权威的服务器事实。即使 commit 失败，也必须设防隔离一个
        // 已经在本进程内认领了同一部署的后继 Activity。
        protocolRejectionState.value = protocolRejectionState.value + protocolVersion
        val mutation = rejectAndroidAuthProtocolVersion(
            current = readState(),
            deploymentFingerprint = deploymentIdentity.fingerprint,
            protocolVersion = protocolVersion,
        )
        if (!mutation.applied) return@synchronized false
        persist(mutation.state)
        true
    }

    private fun readState(): AndroidAuthPreferenceState = AndroidAuthPreferenceState(
        ownerGeneration = prefs.getLong(KEY_OWNER_GENERATION, 0L),
        deploymentFingerprint = prefs.getString(KEY_DEPLOYMENT_FINGERPRINT, null),
        uid = prefs.getString(KEY_UID, null)?.takeIf { it.isNotBlank() },
        refreshToken = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() },
        datasetId = prefs.getString(KEY_DATASET_ID, null)?.takeIf { it.isNotBlank() },
        rejectedProtocolVersions = prefs.getStringSet(KEY_REJECTED_PROTOCOL_VERSIONS, emptySet())
            .orEmpty()
            .mapNotNull { raw -> raw.toIntOrNull()?.takeIf { it in PROTOCOL_VERSION_RANGE } }
            .toSet(),
    ).normalized()

    /**
     * 凭据内容确实变化时使用 commit()：显式登录可能替换设备 refresh root，
     * 返回前必须已持久。后台 refresh 返回同一稳定 token 时 save() 会在 CAS 后直接成功，
     * 不重复阻塞写盘。
     */
    private fun persist(state: AndroidAuthPreferenceState) {
        val editor = prefs.edit().putLong(KEY_OWNER_GENERATION, state.ownerGeneration)
            .remove(LEGACY_REJECTED_PROTOCOL_VERSIONS)
        state.deploymentFingerprint?.let { editor.putString(KEY_DEPLOYMENT_FINGERPRINT, it) }
            ?: editor.remove(KEY_DEPLOYMENT_FINGERPRINT)
        if (state.uid != null && state.refreshToken != null && state.datasetId != null) {
            editor.putString(KEY_UID, state.uid)
                .putString(KEY_TOKEN, state.refreshToken)
                .putString(KEY_DATASET_ID, state.datasetId)
        } else {
            editor.remove(KEY_UID).remove(KEY_TOKEN).remove(KEY_DATASET_ID)
        }
        if (state.rejectedProtocolVersions.isEmpty()) {
            editor.remove(KEY_REJECTED_PROTOCOL_VERSIONS)
        } else {
            editor.putStringSet(
                KEY_REJECTED_PROTOCOL_VERSIONS,
                state.rejectedProtocolVersions.map(Int::toString).toSet(),
            )
        }
        check(editor.commit()) { "无法持久化登录状态" }
    }

    companion object {
        /** SharedPreferences 对象可由多个 Activity 各自构造，锁必须是进程级。 */
        private val PROCESS_LOCK = Any()
        private val PROCESS_PROTOCOL_REJECTIONS =
            mutableMapOf<String, WeakReference<MutableStateFlow<Set<Int>>>>()
        private const val KEY_OWNER_GENERATION = "owner_generation"
        private const val KEY_DEPLOYMENT_FINGERPRINT = "deployment_fingerprint"
        private const val KEY_UID = "uid"
        private const val KEY_TOKEN = "refresh_token"
        private const val KEY_DATASET_ID = "dataset_id"
        // 旧单字节拒绝记录不属于新的 major/minor ID 空间；只迁移标识，不移除登录凭据。
        private const val LEGACY_REJECTED_PROTOCOL_VERSIONS = "rejected_protocol_versions"
        private const val KEY_REJECTED_PROTOCOL_VERSIONS = "rejected_protocol_version_ids"
        private const val ANDROID_AUTH_OWNER_NAMESPACE = "android:teamtalk_auth"
    }
}

/** SharedPreferences 的可纯测试状态；所有 owner CAS 规则在真实存储路径中复用。 */
internal data class AndroidAuthPreferenceState(
    val ownerGeneration: Long = 0L,
    val deploymentFingerprint: String? = null,
    val uid: String? = null,
    val refreshToken: String? = null,
    val datasetId: String? = null,
    val rejectedProtocolVersions: Set<Int> = emptySet(),
) {
    fun normalized(): AndroidAuthPreferenceState {
        val validDatasetId = try {
            com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId.orEmpty())
            true
        } catch (_: IllegalArgumentException) {
            false
        }
        val credentials = if (uid.isNullOrBlank() || refreshToken.isNullOrBlank() || !validDatasetId) {
            copy(uid = null, refreshToken = null, datasetId = null)
        } else {
            this
        }
        return credentials.copy(
            rejectedProtocolVersions = rejectedProtocolVersions.filterTo(mutableSetOf()) {
                it in PROTOCOL_VERSION_RANGE
            },
        )
    }

    fun toStoredLogin(): StoredLogin? {
        val storedUid = uid ?: return null
        val storedToken = refreshToken ?: return null
        val storedDatasetId = datasetId ?: return null
        val storedDeployment = deploymentFingerprint ?: return null
        return StoredLogin(storedUid, storedToken, ownerGeneration, storedDeployment, storedDatasetId)
    }
}

internal data class AndroidAuthOwnerClaim(
    val state: AndroidAuthPreferenceState,
    val owner: TokenStoreOwner,
)

internal data class AndroidAuthMutation(
    val state: AndroidAuthPreferenceState,
    val applied: Boolean,
    /** 为 false 表示 CAS 已被准入，但持久内容已经与目标一致。 */
    val persistenceRequired: Boolean = applied,
)

internal fun claimAndroidAuthOwner(
    current: AndroidAuthPreferenceState,
    deploymentFingerprint: String,
): AndroidAuthOwnerClaim {
    require(deploymentFingerprint.isNotBlank()) { "deployment fingerprint 不能为空" }
    val normalized = current.normalized().let { state ->
        if (state.deploymentFingerprint == deploymentFingerprint) state
        else state.copy(
            deploymentFingerprint = deploymentFingerprint,
            uid = null,
            refreshToken = null,
            datasetId = null,
            rejectedProtocolVersions = emptySet(),
        )
    }
    val nextGeneration = nextAuthOwnerGeneration(normalized.ownerGeneration)
    val claimedState = normalized.copy(ownerGeneration = nextGeneration)
    return AndroidAuthOwnerClaim(
        state = claimedState,
        owner = TokenStoreOwner(
            generation = nextGeneration,
            savedLogin = claimedState.toStoredLogin(),
            rejectedProtocolVersions = claimedState.rejectedProtocolVersions,
        ),
    )
}

internal fun saveAndroidAuthLogin(
    current: AndroidAuthPreferenceState,
    deploymentFingerprint: String,
    ownerGeneration: Long,
    uid: String,
    refreshToken: String,
    datasetId: String,
): AndroidAuthMutation {
    require(uid.isNotBlank()) { "uid 不能为空" }
    require(refreshToken.isNotBlank()) { "refreshToken 不能为空" }
    com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
    val normalized = current.normalized()
    if (
        normalized.deploymentFingerprint != deploymentFingerprint ||
        normalized.ownerGeneration != ownerGeneration
    ) return AndroidAuthMutation(normalized, false)
    val next = normalized.copy(uid = uid, refreshToken = refreshToken, datasetId = datasetId)
    return AndroidAuthMutation(
        state = next,
        applied = true,
        persistenceRequired = next != current,
    )
}

internal fun clearAndroidAuthLogin(
    current: AndroidAuthPreferenceState,
    expected: StoredLogin,
): AndroidAuthMutation {
    val normalized = current.normalized()
    val matches = normalized.ownerGeneration == expected.ownerGeneration &&
        normalized.deploymentFingerprint == expected.deploymentFingerprint &&
        normalized.uid == expected.uid &&
        normalized.refreshToken == expected.refreshToken &&
        normalized.datasetId == expected.datasetId
    return if (matches) {
        AndroidAuthMutation(normalized.copy(uid = null, refreshToken = null, datasetId = null), true)
    } else {
        AndroidAuthMutation(normalized, false)
    }
}

internal fun rejectAndroidAuthProtocolVersion(
    current: AndroidAuthPreferenceState,
    deploymentFingerprint: String,
    protocolVersion: Int,
): AndroidAuthMutation {
    require(protocolVersion in PROTOCOL_VERSION_RANGE) { "协议版本 ID 必须非负" }
    val normalized = current.normalized()
    if (
        normalized.deploymentFingerprint != deploymentFingerprint
    ) return AndroidAuthMutation(normalized, false)
    return AndroidAuthMutation(
        state = normalized.copy(
            rejectedProtocolVersions = normalized.rejectedProtocolVersions + protocolVersion,
        ),
        applied = true,
    )
}

internal fun nextAuthOwnerGeneration(current: Long): Long =
    (current + 1L).takeUnless { it == 0L } ?: 1L

private val PROTOCOL_VERSION_RANGE = 0..Int.MAX_VALUE
