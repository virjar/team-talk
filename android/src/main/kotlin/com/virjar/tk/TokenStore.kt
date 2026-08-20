package com.virjar.tk

import android.content.Context
import android.content.SharedPreferences
import com.virjar.tk.client.StoredLogin
import com.virjar.tk.client.TokenStoreOwner

/**
 * 登录态持久化（SharedPreferences）。
 *
 * 存储认证成功后的 uid + refreshToken，使 app 重启后能自动登录（直达主界面），
 * 而非每次都走登录页。refreshToken 有效期 30 天（服务端 TokenStore）。
 *
 * 清除时机：用户主动登出、token 失效（AUTH_FAILED）、被踢下线。
 */
class TokenStore(context: Context) : com.virjar.tk.client.TokenStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("teamtalk_auth", Context.MODE_PRIVATE)

    override fun claimOwner(): TokenStoreOwner = synchronized(PROCESS_LOCK) {
        val claimed = claimAndroidAuthOwner(readState())
        persist(claimed.state)
        claimed.owner
    }

    override fun save(ownerGeneration: Long, uid: String, refreshToken: String): StoredLogin? =
        synchronized(PROCESS_LOCK) {
            val mutation = saveAndroidAuthLogin(readState(), ownerGeneration, uid, refreshToken)
            if (!mutation.applied) return@synchronized null
            persist(mutation.state)
            mutation.state.toStoredLogin()
        }

    override fun compareAndClear(expected: StoredLogin): Boolean = synchronized(PROCESS_LOCK) {
        val mutation = clearAndroidAuthLogin(readState(), expected)
        if (!mutation.applied) return@synchronized false
        persist(mutation.state)
        true
    }

    override fun isCurrentOwner(ownerGeneration: Long): Boolean = synchronized(PROCESS_LOCK) {
        readState().ownerGeneration == ownerGeneration
    }

    private fun readState(): AndroidAuthPreferenceState = AndroidAuthPreferenceState(
        ownerGeneration = prefs.getLong(KEY_OWNER_GENERATION, 0L),
        uid = prefs.getString(KEY_UID, null)?.takeIf { it.isNotBlank() },
        refreshToken = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() },
    ).normalized()

    /**
     * commit() 是刻意的：服务端会在 refresh 认证时立即作废旧 token。只在内存中
     * apply() 新 token 后遭遇硬杀进程，下次启动将永久落回已作废的旧凭据。
     */
    private fun persist(state: AndroidAuthPreferenceState) {
        val editor = prefs.edit().putLong(KEY_OWNER_GENERATION, state.ownerGeneration)
        if (state.uid != null && state.refreshToken != null) {
            editor.putString(KEY_UID, state.uid).putString(KEY_TOKEN, state.refreshToken)
        } else {
            editor.remove(KEY_UID).remove(KEY_TOKEN)
        }
        check(editor.commit()) { "无法持久化登录状态" }
    }

    companion object {
        /** SharedPreferences 对象可由多个 Activity 各自构造，锁必须是进程级。 */
        private val PROCESS_LOCK = Any()
        private const val KEY_OWNER_GENERATION = "owner_generation"
        private const val KEY_UID = "uid"
        private const val KEY_TOKEN = "refresh_token"
    }
}

/** SharedPreferences 的可纯测试状态；所有 owner CAS 规则在真实存储路径中复用。 */
internal data class AndroidAuthPreferenceState(
    val ownerGeneration: Long = 0L,
    val uid: String? = null,
    val refreshToken: String? = null,
) {
    fun normalized(): AndroidAuthPreferenceState =
        if (uid.isNullOrBlank() || refreshToken.isNullOrBlank()) copy(uid = null, refreshToken = null)
        else this

    fun toStoredLogin(): StoredLogin? {
        val storedUid = uid ?: return null
        val storedToken = refreshToken ?: return null
        return StoredLogin(storedUid, storedToken, ownerGeneration)
    }
}

internal data class AndroidAuthOwnerClaim(
    val state: AndroidAuthPreferenceState,
    val owner: TokenStoreOwner,
)

internal data class AndroidAuthMutation(
    val state: AndroidAuthPreferenceState,
    val applied: Boolean,
)

internal fun claimAndroidAuthOwner(current: AndroidAuthPreferenceState): AndroidAuthOwnerClaim {
    val normalized = current.normalized()
    val nextGeneration = nextAuthOwnerGeneration(normalized.ownerGeneration)
    val claimedState = normalized.copy(ownerGeneration = nextGeneration)
    return AndroidAuthOwnerClaim(
        state = claimedState,
        owner = TokenStoreOwner(nextGeneration, claimedState.toStoredLogin()),
    )
}

internal fun saveAndroidAuthLogin(
    current: AndroidAuthPreferenceState,
    ownerGeneration: Long,
    uid: String,
    refreshToken: String,
): AndroidAuthMutation {
    require(uid.isNotBlank()) { "uid 不能为空" }
    require(refreshToken.isNotBlank()) { "refreshToken 不能为空" }
    val normalized = current.normalized()
    if (normalized.ownerGeneration != ownerGeneration) return AndroidAuthMutation(normalized, false)
    return AndroidAuthMutation(
        normalized.copy(uid = uid, refreshToken = refreshToken),
        true,
    )
}

internal fun clearAndroidAuthLogin(
    current: AndroidAuthPreferenceState,
    expected: StoredLogin,
): AndroidAuthMutation {
    val normalized = current.normalized()
    val matches = normalized.ownerGeneration == expected.ownerGeneration &&
        normalized.uid == expected.uid &&
        normalized.refreshToken == expected.refreshToken
    return if (matches) {
        AndroidAuthMutation(normalized.copy(uid = null, refreshToken = null), true)
    } else {
        AndroidAuthMutation(normalized, false)
    }
}

internal fun nextAuthOwnerGeneration(current: Long): Long =
    (current + 1L).takeUnless { it == 0L } ?: 1L
