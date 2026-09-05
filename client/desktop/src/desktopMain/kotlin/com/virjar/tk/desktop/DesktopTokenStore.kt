package com.virjar.tk.desktop

import com.virjar.tk.shared.client.StoredLogin
import com.virjar.tk.shared.client.TokenStoreOwner
import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import com.virjar.tk.shared.client.DeploymentIdentity
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop 登录态持久化（Properties 文件）。
 *
 * 对齐 Android [TokenStore]：存储认证成功后的 uid + refreshToken，
 * 使 Desktop 重启后能自动登录（直达主界面）。token 文件在 [dataDir]/auth.properties。
 *
 * 清除时机：用户主动登出、token 失效（AUTH_FAILED）。
 */
class DesktopTokenStore(
    dataDir: File,
    override val deploymentIdentity: DeploymentIdentity,
) : com.virjar.tk.shared.client.TokenStore {
    override val ownerClaimNamespace: String =
        "desktop:${dataDir.absoluteFile.normalize().path}/auth.properties"
    private val protocolRejectionKey =
        ownerClaimNamespace + '\u0000' + deploymentIdentity.fingerprint
    private val protocolRejectionState = synchronized(PROCESS_LOCK) {
        PROCESS_PROTOCOL_REJECTIONS.entries.removeAll { it.value.get() == null }
        PROCESS_PROTOCOL_REJECTIONS[protocolRejectionKey]?.get()
            ?: MutableStateFlow<Set<Int>>(emptySet()).also { state ->
                PROCESS_PROTOCOL_REJECTIONS[protocolRejectionKey] = WeakReference(state)
            }
    }
    override val rejectedProtocolVersions: StateFlow<Set<Int>> =
        protocolRejectionState.asStateFlow()
    private val store = JvmPrivateDataDirectory.openExisting(dataDir).atomicTextFile(
        fileName = "auth.properties",
    )

    override fun claimOwner(): TokenStoreOwner = synchronized(PROCESS_LOCK) {
        val props = readProps() ?: Properties()
        val generation = nextOwnerGeneration(props.ownerGeneration())
        props.setProperty(KEY_OWNER_GENERATION, generation.toString())
        if (props.getProperty(KEY_DEPLOYMENT_FINGERPRINT) != deploymentIdentity.fingerprint) {
            props.remove(KEY_UID)
            props.remove(KEY_TOKEN)
            props.remove(KEY_DATASET_ID)
            props.remove(KEY_REJECTED_PROTOCOL_VERSIONS)
        }
        props.setProperty(KEY_DEPLOYMENT_FINGERPRINT, deploymentIdentity.fingerprint)
        normalizeCredentials(props)
        normalizeRejectedProtocolVersions(props)
        val mergedRejections = props.rejectedProtocolVersions() + protocolRejectionState.value
        protocolRejectionState.value = mergedRejections
        if (mergedRejections.isEmpty()) {
            props.remove(KEY_REJECTED_PROTOCOL_VERSIONS)
        } else {
            props.setProperty(KEY_REJECTED_PROTOCOL_VERSIONS, mergedRejections.sorted().joinToString(","))
        }
        writeProps(props)
        TokenStoreOwner(
            generation = generation,
            savedLogin = props.toStoredLogin(generation),
            rejectedProtocolVersions = mergedRejections,
        )
    }

    override fun save(
        ownerGeneration: Long,
        uid: String,
        refreshToken: String,
        datasetId: String,
    ): StoredLogin? =
        synchronized(PROCESS_LOCK) {
            require(uid.isNotBlank()) { "uid 不能为空" }
            require(refreshToken.isNotBlank()) { "refreshToken 不能为空" }
            com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
            val props = readProps() ?: Properties()
            if (
                props.ownerGeneration() != ownerGeneration ||
                props.getProperty(KEY_DEPLOYMENT_FINGERPRINT) != deploymentIdentity.fingerprint
            ) return@synchronized null
            if (
                props.getProperty(KEY_UID) == uid &&
                props.getProperty(KEY_TOKEN) == refreshToken &&
                props.getProperty(KEY_DATASET_ID) == datasetId
            ) {
                return@synchronized StoredLogin(
                    uid,
                    refreshToken,
                    ownerGeneration,
                    deploymentIdentity.fingerprint,
                    datasetId,
                )
            }
            props.setProperty(KEY_UID, uid)
            props.setProperty(KEY_TOKEN, refreshToken)
            props.setProperty(KEY_DATASET_ID, datasetId)
            writeProps(props)
            StoredLogin(uid, refreshToken, ownerGeneration, deploymentIdentity.fingerprint, datasetId)
        }

    override fun compareAndClear(expected: StoredLogin): Boolean = synchronized(PROCESS_LOCK) {
        val props = readProps() ?: return@synchronized false
        val matches = props.ownerGeneration() == expected.ownerGeneration &&
            props.getProperty(KEY_DEPLOYMENT_FINGERPRINT) == expected.deploymentFingerprint &&
            expected.deploymentFingerprint == deploymentIdentity.fingerprint &&
            props.getProperty(KEY_UID) == expected.uid &&
            props.getProperty(KEY_TOKEN) == expected.refreshToken &&
            props.getProperty(KEY_DATASET_ID) == expected.datasetId
        if (!matches) return@synchronized false
        props.remove(KEY_UID)
        props.remove(KEY_TOKEN)
        props.remove(KEY_DATASET_ID)
        writeProps(props)
        true
    }

    /** 持久化的 generation 是跨实例、跨进程的持有权依据。 */
    override fun isCurrentOwner(ownerGeneration: Long): Boolean = synchronized(PROCESS_LOCK) {
        val props = readProps() ?: return@synchronized false
        props.ownerGeneration() == ownerGeneration &&
            props.getProperty(KEY_DEPLOYMENT_FINGERPRINT) == deploymentIdentity.fingerprint
    }

    override fun markProtocolVersionRejected(protocolVersion: Int): Boolean = synchronized(PROCESS_LOCK) {
        require(protocolVersion in PROTOCOL_VERSION_RANGE) { "协议版本 ID 必须非负" }
        // 即使持久文件无法提交，或者这个过期的 store 发现所配置的部署已经改变，
        // 服务器事实仍然是进程内可见的。
        protocolRejectionState.value = protocolRejectionState.value + protocolVersion
        val props = readProps() ?: Properties()
        if (props.getProperty(KEY_DEPLOYMENT_FINGERPRINT) != deploymentIdentity.fingerprint) {
            return@synchronized false
        }
        val rejected = props.rejectedProtocolVersions() + protocolVersion
        props.setProperty(KEY_REJECTED_PROTOCOL_VERSIONS, rejected.sorted().joinToString(","))
        writeProps(props)
        true
    }

    /**
     * 临时文件 + fsync + 原子替换只为真正的凭据变更而保留。uid/token 内容完全相同的稳定刷新重试
     * 会在 owner CAS 之后直接返回，而不重写该文件。
     */
    private fun writeProps(props: Properties) {
        val serialized = StringWriter().also { props.store(it, "TeamTalk auth") }.toString()
        store.replaceText(serialized, MAX_AUTH_FILE_BYTES)
    }

    private fun readProps(): Properties? = store.readText(MAX_AUTH_FILE_BYTES)?.let { serialized ->
        Properties().apply { StringReader(serialized).use { reader -> load(reader) } }
    }

    private fun Properties.ownerGeneration(): Long =
        getProperty(KEY_OWNER_GENERATION)?.toLongOrNull() ?: 0L

    private fun normalizeCredentials(props: Properties) {
        val datasetId = props.getProperty(KEY_DATASET_ID)
        val validDatasetId = try {
            com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId.orEmpty())
            true
        } catch (_: IllegalArgumentException) {
            false
        }
        if (
            props.getProperty(KEY_UID).isNullOrBlank() ||
            props.getProperty(KEY_TOKEN).isNullOrBlank() ||
            !validDatasetId
        ) {
            props.remove(KEY_UID)
            props.remove(KEY_TOKEN)
            props.remove(KEY_DATASET_ID)
        }
    }

    private fun normalizeRejectedProtocolVersions(props: Properties) {
        props.remove(LEGACY_REJECTED_PROTOCOL_VERSIONS)
        val rejected = props.rejectedProtocolVersions()
        if (rejected.isEmpty()) {
            props.remove(KEY_REJECTED_PROTOCOL_VERSIONS)
        } else {
            props.setProperty(KEY_REJECTED_PROTOCOL_VERSIONS, rejected.sorted().joinToString(","))
        }
    }

    private fun Properties.rejectedProtocolVersions(): Set<Int> =
        getProperty(KEY_REJECTED_PROTOCOL_VERSIONS)
            ?.split(',')
            ?.mapNotNull { raw -> raw.trim().toIntOrNull()?.takeIf { it in PROTOCOL_VERSION_RANGE } }
            ?.toSet()
            .orEmpty()

    private fun Properties.toStoredLogin(ownerGeneration: Long): StoredLogin? {
        val uid = getProperty(KEY_UID)?.takeIf { it.isNotBlank() } ?: return null
        val token = getProperty(KEY_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        val datasetId = getProperty(KEY_DATASET_ID)?.takeIf { it.isNotBlank() } ?: return null
        val deploymentFingerprint = getProperty(KEY_DEPLOYMENT_FINGERPRINT)
            ?.takeIf { it == deploymentIdentity.fingerprint }
            ?: return null
        return StoredLogin(uid, token, ownerGeneration, deploymentFingerprint, datasetId)
    }

    companion object {
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
        private const val MAX_AUTH_FILE_BYTES = 64L * 1024L
        private val PROTOCOL_VERSION_RANGE = 0..Int.MAX_VALUE

        private fun nextOwnerGeneration(current: Long): Long =
            (current + 1L).takeUnless { it == 0L } ?: 1L
    }
}
