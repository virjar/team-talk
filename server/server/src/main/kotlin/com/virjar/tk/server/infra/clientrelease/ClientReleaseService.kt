package com.virjar.tk.server.infra.clientrelease

import com.virjar.tk.protocol.http.AndroidReleaseManifest
import com.virjar.tk.protocol.http.ClientInstallerInfo
import com.virjar.tk.protocol.http.ClientPayloadFile
import com.virjar.tk.protocol.http.ClientReleaseInfo
import com.virjar.tk.protocol.http.ClientReleaseManifest
import com.virjar.tk.protocol.http.ClientUpdateCheckResponse
import com.virjar.tk.protocol.http.ClientUpdateContracts
import com.virjar.tk.server.infra.db.ClientChannels
import com.virjar.tk.server.infra.db.ClientReleaseFiles
import com.virjar.tk.server.infra.db.ClientReleases
import com.virjar.tk.server.infra.storage.ReleaseStore
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.File
import java.security.MessageDigest

/** 发布管理动作的审计出口；由 Koin 用 AdminSecurityStore 的 beginAudit/completeAudit 适配。 */
fun interface ClientReleaseAudit {
    fun record(actor: String, action: String, target: String, status: Int)
}

class ClientReleaseConflictException(message: String) : IllegalStateException(message)
class ClientReleaseValidationException(message: String) : IllegalArgumentException(message)

/**
 * 客户端发布注册中心：发布行/制品索引/通道指针的唯一写入方与解析方。
 *
 * 所有方法为阻塞调用（沿用现有路由直接调用阻塞 service 的边界约定）。
 * 发布物内容只进 ReleaseStore；各源码构建的发布行不可变，通道只切换指针。
 * 同一上传包的重试返回原发布，不覆盖文件列表或运维状态。
 */
internal class ClientReleaseService(
    private val database: Database,
    private val store: ReleaseStore,
    private val audit: ClientReleaseAudit,
    /** CI 发布令牌的 SHA-256 十六进制；null 表示未配置，CI 上传通道关闭。 */
    private val publishTokenSha256: String?,
) {

    data class CheckQuery(
        val clientType: String,
        val platform: String,
        val arch: String,
        val channel: String,
        val version: String?,
        val build: Long?,
        val shellAbi: Int?,
        val buildIdentity: String? = null,
    )

    // ── 解析（公开只读） ──────────────────────────────────────────────

    fun check(query: CheckQuery): ClientUpdateCheckResponse {
        val release = currentRelease(query.clientType, query.platform, query.arch, query.channel)
            ?: return ClientUpdateCheckResponse(ClientUpdateContracts.STATUS_CHANNEL_DISABLED, null)
        val info = releaseInfo(release.id)?.copy(channel = query.channel)
            ?: return ClientUpdateCheckResponse(ClientUpdateContracts.STATUS_CHANNEL_DISABLED, null)
        val upToDate = query.version != null && query.build != null &&
            query.version == release.version && query.build == release.build &&
            (query.buildIdentity == null || info.buildIdentity == null || query.buildIdentity == info.buildIdentity)
        if (upToDate) {
            return ClientUpdateCheckResponse(ClientUpdateContracts.STATUS_UP_TO_DATE, info)
        }
        val shellTooOld = release.minShellAbi != null && query.shellAbi != null &&
            query.shellAbi < release.minShellAbi
        val status = if (shellTooOld) {
            ClientUpdateContracts.STATUS_SHELL_UPDATE_REQUIRED
        } else {
            ClientUpdateContracts.STATUS_UPDATE_AVAILABLE
        }
        return ClientUpdateCheckResponse(status, info)
    }

    fun manifest(releaseId: Long): ClientReleaseManifest? = tx {
        val row = releaseById(releaseId) ?: return@tx null
        val files = artifactRows(releaseId, ClientUpdateContracts.KIND_PAYLOAD)
            .sortedBy { it[ClientReleaseFiles.path] }
            .map {
                ClientPayloadFile(
                    path = it[ClientReleaseFiles.path],
                    sha256 = it[ClientReleaseFiles.sha256],
                    size = it[ClientReleaseFiles.size],
                    mode = it[ClientReleaseFiles.mode],
                    url = fileUrl(it[ClientReleaseFiles.sha256]),
                )
            }
        ClientReleaseManifest(
            releaseId = releaseId,
            clientType = row[ClientReleases.clientType],
            platform = row[ClientReleases.platform],
            arch = row[ClientReleases.arch],
            version = row[ClientReleases.version],
            build = row[ClientReleases.build],
            minShellAbi = row[ClientReleases.minShellAbi],
            files = files,
            buildIdentity = row[ClientReleases.buildIdentity].ifBlank { null },
        )
    }

    /** 内容寻址制品的文件句柄（路由下载用）。 */
    fun artifactFile(sha256: String): File? = store.resolve(sha256)

    /**
     * 安装器制品的发布文件名（如 `TeamTalk-0.0.4-android.apk`）。
     * `/files/{sha256}` 据此设置下载 Content-Disposition；payload/bundle 文件
     * 没有面向用户的文件名（应用内更新器按哈希消费），返回 null。
     */
    fun installerFilename(sha256: String): String? = tx {
        ClientReleaseFiles.selectAll()
            .where {
                (ClientReleaseFiles.sha256 eq sha256) and
                    (ClientReleaseFiles.kind eq ClientUpdateContracts.KIND_INSTALLER)
            }
            .limit(1)
            .firstOrNull()
            ?.get(ClientReleaseFiles.path)
    }

    fun releaseInfo(releaseId: Long): ClientReleaseInfo? = tx {
        val row = releaseById(releaseId) ?: return@tx null
        val artifacts = artifactRows(releaseId, null)
        val bundle = artifacts.firstOrNull { it[ClientReleaseFiles.kind] == ClientUpdateContracts.KIND_BUNDLE }
        ClientReleaseInfo(
            id = releaseId,
            buildIdentity = row[ClientReleases.buildIdentity].ifBlank { null },
            clientType = row[ClientReleases.clientType],
            platform = row[ClientReleases.platform],
            arch = row[ClientReleases.arch],
            version = row[ClientReleases.version],
            build = row[ClientReleases.build],
            channel = row[ClientReleases.channel],
            notes = row[ClientReleases.notesZh],
            forced = row[ClientReleases.forced],
            minShellAbi = row[ClientReleases.minShellAbi],
            manifestUrl = if (artifacts.any { it[ClientReleaseFiles.kind] == ClientUpdateContracts.KIND_PAYLOAD }) {
                "/api/v1/client/releases/$releaseId/manifest.json"
            } else {
                null
            },
            bundleUrl = bundle?.let { fileUrl(it[ClientReleaseFiles.sha256]) },
            fileCount = row[ClientReleases.fileCount],
            totalBytes = row[ClientReleases.totalBytes],
            installers = artifacts.filter { it[ClientReleaseFiles.kind] == ClientUpdateContracts.KIND_INSTALLER }
                .map {
                    ClientInstallerInfo(
                        label = it[ClientReleaseFiles.label] ?: it[ClientReleaseFiles.path],
                        filename = it[ClientReleaseFiles.path],
                        url = fileUrl(it[ClientReleaseFiles.sha256]),
                        size = it[ClientReleaseFiles.size],
                        sha256 = it[ClientReleaseFiles.sha256],
                    )
                },
        )
    }

    /** /downloads/{filename} 的 APK 兼容入口：null = 别名（取当前安装包），否则按文件名精确匹配。 */
    fun findAndroidInstaller(filename: String?): ClientReleaseService.AndroidInstaller? {
        val release = latestAndroidChannelRelease() ?: return null
        val artifact = tx {
            val rows = artifactRows(release.id, ClientUpdateContracts.KIND_INSTALLER)
            when (filename) {
                null -> rows.firstOrNull()
                else -> rows.firstOrNull { it[ClientReleaseFiles.path] == filename }
            }
        } ?: return null
        val file = store.resolve(artifact[ClientReleaseFiles.sha256]) ?: return null
        return AndroidInstaller(
            file = file,
            size = artifact[ClientReleaseFiles.size],
            filename = artifact[ClientReleaseFiles.path],
        )
    }

    /** /downloads/android.json：最近一次被激活的 android 通道发布（对齐旧收据语义——它就是运维最后推的包）。 */
    fun androidLegacyManifest(): AndroidReleaseManifest? {
        val release = latestAndroidChannelRelease() ?: return null
        val artifact = tx {
            artifactRows(release.id, ClientUpdateContracts.KIND_INSTALLER).firstOrNull()
        } ?: return null
        return AndroidReleaseManifest(
            displayName = "TeamTalk Android",
            version = release.version,
            channel = release.channelKind,
            filename = artifact[ClientReleaseFiles.path],
            url = "/downloads/${artifact[ClientReleaseFiles.path]}",
        )
    }

    class AndroidInstaller(val file: File, val size: Long, val filename: String)

    private data class ResolvedRelease(
        val id: Long,
        val version: String,
        val build: Long,
        val channelKind: String?,
        val minShellAbi: Int?,
    )

    private fun latestAndroidChannelRelease(): ResolvedRelease? = tx {
        val pointers = ClientChannels.selectAll()
            .where {
                (ClientChannels.clientType eq ClientUpdateContracts.CLIENT_ANDROID) and
                    (ClientChannels.enabled eq true) and ClientChannels.currentReleaseId.isNotNull()
            }
            .toList()
        pointers.sortedByDescending { it[ClientChannels.updatedAt] }.firstNotNullOfOrNull { pointer ->
            val row = releaseById(pointer[ClientChannels.currentReleaseId]!!)
                ?.takeIf { it[ClientReleases.status] == STATUS_ACTIVE } ?: return@firstNotNullOfOrNull null
            ResolvedRelease(row[ClientReleases.id], row[ClientReleases.version], row[ClientReleases.build],
                pointer[ClientChannels.channel], null)
        }
    }

    /** A retained channel row records registry ownership even after its pointer is cleared. */
    fun managesAndroidDownloads(): Boolean = tx {
        ClientChannels.selectAll().where { ClientChannels.clientType eq ClientUpdateContracts.CLIENT_ANDROID }.count() > 0
    }

    private fun currentRelease(clientType: String, platform: String, arch: String, channel: String): ResolvedRelease? = tx {
        val pointer = ClientChannels.selectAll()
            .where {
                (ClientChannels.clientType eq clientType) and
                    (ClientChannels.platform eq platform) and
                    (ClientChannels.arch eq arch) and
                    (ClientChannels.channel eq channel)
            }
            .singleOrNull() ?: return@tx null
        if (!pointer[ClientChannels.enabled]) return@tx null
        val releaseId = pointer[ClientChannels.currentReleaseId] ?: return@tx null
        val row = releaseById(releaseId)
            ?.takeIf { it[ClientReleases.status] == STATUS_ACTIVE }
            ?: return@tx null
        ResolvedRelease(
            id = releaseId,
            version = row[ClientReleases.version],
            build = row[ClientReleases.build],
            channelKind = row[ClientReleases.channel],
            minShellAbi = row[ClientReleases.minShellAbi],
        )
    }

    // ── 导入（CI / 管理台上传） ──────────────────────────────────────

    /**
     * 导入一个发布上传包（zip：release.json、payload 目录、bundle 与 installers 目录）。
     * 返回发布 id。相同原字节可重试；同身份不同字节拒绝，新的 snapshot 源码身份另建发布行。
     */
    @Synchronized
    fun importRelease(actor: String, uploadZip: File): Long {
        val parsed = ClientReleaseUploadReader(store).read(uploadZip)
        val uploadSha = parsed.sha256
        val identity = parsed.buildIdentity
        try {
            val id = tx {
                val existing = releaseByIdentity(parsed.metadata, identity)
                val releaseId: Long
                if (existing == null) {
                    releaseId = ClientReleases.insert {
                        it[clientType] = parsed.metadata.clientType
                        it[platform] = parsed.metadata.platform
                        it[arch] = parsed.metadata.arch
                        it[version] = parsed.metadata.version
                        it[build] = parsed.metadata.build
                        it[buildIdentity] = identity
                        it[uploadSha256] = uploadSha
                        it[channel] = parsed.metadata.channel
                        it[notesZh] = parsed.metadata.notes
                        it[forced] = parsed.metadata.forced
                        it[minShellAbi] = parsed.metadata.minShellAbi
                        it[shellDigest] = parsed.metadata.shellDigest
                        it[status] = STATUS_ACTIVE
                        it[fileCount] = parsed.payloadCount
                        it[totalBytes] = parsed.totalBytes
                        it[createdAt] = System.currentTimeMillis()
                        it[createdBy] = actor.take(64)
                    } get ClientReleases.id
                } else {
                    if (existing[ClientReleases.uploadSha256] == uploadSha) {
                        // Retry acknowledges the existing bytes without changing operator channel/disable decisions.
                        return@tx existing[ClientReleases.id]
                    }
                    throw ClientReleaseConflictException("release identity already exists with different bytes")
                }

                for (file in parsed.files) {
                    ClientReleaseFiles.insert {
                        it[ClientReleaseFiles.releaseId] = releaseId
                        it[kind] = file.kind
                        it[path] = file.path
                        it[sha256] = file.sha256
                        it[size] = file.size
                        it[mode] = file.mode
                        it[label] = file.label
                    }
                }
                if (parsed.metadata.activate) {
                    upsertChannelPointerLocked(parsed.metadata, releaseId, actor)
                }
                releaseId
            }
            audit.record(actor, "client_release.import", releaseIdentity(parsed.metadata), 200)
            return id
        } catch (conflict: ClientReleaseConflictException) {
            audit.record(actor, "client_release.import", releaseIdentity(parsed.metadata), 409)
            throw conflict
        } catch (validation: ClientReleaseValidationException) {
            audit.record(actor, "client_release.import", releaseIdentity(parsed.metadata), 400)
            throw validation
        }
    }

    // ── 管理（通道/停用/回滚/删除） ─────────────────────────────────

    fun setChannel(actor: String, clientType: String, platform: String, arch: String, channel: String, releaseId: Long?) {
        if (channel !in setOf(
                AndroidReleaseManifest.CHANNEL_STABLE, AndroidReleaseManifest.CHANNEL_PREVIEW,
                AndroidReleaseManifest.CHANNEL_SNAPSHOT,
            )
        ) {
            throw ClientReleaseValidationException("unknown channel $channel")
        }
        tx {
            if (releaseId != null) {
                val row = releaseById(releaseId)
                    ?: throw ClientReleaseValidationException("release $releaseId not found")
                if (row[ClientReleases.status] != STATUS_ACTIVE) {
                    throw ClientReleaseConflictException("release $releaseId is not ACTIVE")
                }
                if (row[ClientReleases.clientType] != clientType || row[ClientReleases.platform] != platform ||
                    row[ClientReleases.arch] != arch
                ) {
                    throw ClientReleaseValidationException("release $releaseId does not match channel target")
                }
            }
            upsertChannelPointerRow(clientType, platform, arch, channel, releaseId, actor)
        }
        audit.record(actor, "client_release.set_channel", "$clientType/$platform/$arch/$channel->$releaseId", 200)
    }

    fun setChannelEnabled(actor: String, clientType: String, platform: String, arch: String, channel: String, enabled: Boolean) {
        tx {
            val updated = ClientChannels.update({
                (ClientChannels.clientType eq clientType) and
                    (ClientChannels.platform eq platform) and
                    (ClientChannels.arch eq arch) and
                    (ClientChannels.channel eq channel)
            }) {
                it[ClientChannels.enabled] = enabled
                it[updatedBy] = actor.take(64)
                it[updatedAt] = System.currentTimeMillis()
            }
            if (updated == 0 && enabled) {
                // kill-switch 只能作用在已存在的指针上；开启一个从未发布的通道没有意义。
                throw ClientReleaseValidationException("channel pointer does not exist")
            }
        }
        audit.record(actor, "client_release.channel_switch", "$clientType/$platform/$arch/$channel enabled=$enabled", 200)
    }

    /** 停用发布；引用它的通道指针可显式改指 fallbackReleaseId，否则解析为 CHANNEL_DISABLED。 */
    fun disableRelease(actor: String, releaseId: Long, fallbackReleaseId: Long?) {
        tx {
            val row = releaseById(releaseId)
                ?: throw ClientReleaseValidationException("release $releaseId not found")
            if (row[ClientReleases.status] == STATUS_SUPERSEDED) {
                throw ClientReleaseConflictException("release $releaseId is already superseded")
            }
            if (fallbackReleaseId != null) {
                val fallback = releaseById(fallbackReleaseId)
                    ?: throw ClientReleaseValidationException("fallback release $fallbackReleaseId not found")
                if (fallback[ClientReleases.status] != STATUS_ACTIVE ||
                    fallback[ClientReleases.clientType] != row[ClientReleases.clientType] ||
                    fallback[ClientReleases.platform] != row[ClientReleases.platform] ||
                    fallback[ClientReleases.arch] != row[ClientReleases.arch]
                ) {
                    throw ClientReleaseValidationException("fallback release does not match target or is not ACTIVE")
                }
            }
            ClientReleases.update({ ClientReleases.id eq releaseId }) {
                it[status] = STATUS_DISABLED
            }
            if (fallbackReleaseId != null) {
                ClientChannels.update({ ClientChannels.currentReleaseId eq releaseId }) {
                    it[currentReleaseId] = fallbackReleaseId
                    it[updatedBy] = actor.take(64)
                    it[updatedAt] = System.currentTimeMillis()
                }
            }
        }
        audit.record(actor, "client_release.disable", "release=$releaseId fallback=$fallbackReleaseId", 200)
    }

    fun enableRelease(actor: String, releaseId: Long) {
        tx {
            val row = releaseById(releaseId)
                ?: throw ClientReleaseValidationException("release $releaseId not found")
            if (row[ClientReleases.status] != STATUS_DISABLED) {
                throw ClientReleaseConflictException("release $releaseId is not DISABLED")
            }
            ClientReleases.update({ ClientReleases.id eq releaseId }) {
                it[status] = STATUS_ACTIVE
            }
        }
        audit.record(actor, "client_release.enable", "release=$releaseId", 200)
    }

    /** 删除已停用且无通道引用的发布记录，保留内容对象供并发导入及恢复。 */
    fun deleteRelease(actor: String, releaseId: Long) {
        tx {
            val row = releaseById(releaseId)
                ?: throw ClientReleaseValidationException("release $releaseId not found")
            if (row[ClientReleases.status] != STATUS_DISABLED) {
                throw ClientReleaseConflictException("only DISABLED releases can be deleted")
            }
            val referenced = ClientChannels.selectAll()
                .where { ClientChannels.currentReleaseId eq releaseId }
                .count()
            if (referenced > 0) {
                throw ClientReleaseConflictException("release $releaseId is still referenced by a channel")
            }
            ClientReleaseFiles.deleteWhere { ClientReleaseFiles.releaseId eq releaseId }
            ClientReleases.deleteWhere { ClientReleases.id eq releaseId }
        }
        // Content-addressed objects are retained: deletion after a DB reference check races with import.
        audit.record(actor, "client_release.delete", "release=$releaseId", 200)
    }

    // ── 查询（管理台/公开下载页） ───────────────────────────────────

    @Serializable
    data class ReleaseRow(
        val id: Long,
        val clientType: String,
        val platform: String,
        val arch: String,
        val version: String,
        val build: Long,
        val channel: String,
        val status: String,
        val forced: Boolean,
        val fileCount: Int,
        val totalBytes: Long,
        val createdAt: Long,
        val createdBy: String,
        val buildIdentity: String? = null,
    )

    fun listReleases(clientType: String?, status: String?, limit: Int, offset: Int): List<ReleaseRow> = tx {
        ClientReleases.selectAll()
            .where {
                val clientClause = clientType?.let { ClientReleases.clientType eq it }
                val statusClause = status?.let { ClientReleases.status eq it }
                when {
                    clientClause != null && statusClause != null -> clientClause and statusClause
                    clientClause != null -> clientClause
                    statusClause != null -> statusClause
                    else -> org.jetbrains.exposed.sql.Op.TRUE
                }
            }
            .orderBy(ClientReleases.createdAt, SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map {
                ReleaseRow(
                    id = it[ClientReleases.id],
                    clientType = it[ClientReleases.clientType],
                    platform = it[ClientReleases.platform],
                    arch = it[ClientReleases.arch],
                    version = it[ClientReleases.version],
                    build = it[ClientReleases.build],
                    channel = it[ClientReleases.channel],
                    status = it[ClientReleases.status],
                    forced = it[ClientReleases.forced],
                    fileCount = it[ClientReleases.fileCount],
                    totalBytes = it[ClientReleases.totalBytes],
                    createdAt = it[ClientReleases.createdAt],
                    createdBy = it[ClientReleases.createdBy],
                    buildIdentity = it[ClientReleases.buildIdentity].ifBlank { null },
                )
            }
    }

    @Serializable
    data class ChannelRow(
        val clientType: String,
        val platform: String,
        val arch: String,
        val channel: String,
        val enabled: Boolean,
        val currentReleaseId: Long?,
        val updatedBy: String,
        val updatedAt: Long,
    )

    fun listChannels(clientType: String?): List<ChannelRow> = tx {
        ClientChannels.selectAll()
            .where { clientType?.let { ClientChannels.clientType eq it } ?: org.jetbrains.exposed.sql.Op.TRUE }
            .map {
                ChannelRow(
                    clientType = it[ClientChannels.clientType],
                    platform = it[ClientChannels.platform],
                    arch = it[ClientChannels.arch],
                    channel = it[ClientChannels.channel],
                    enabled = it[ClientChannels.enabled],
                    currentReleaseId = it[ClientChannels.currentReleaseId],
                    updatedBy = it[ClientChannels.updatedBy],
                    updatedAt = it[ClientChannels.updatedAt],
                )
            }
    }

    /** 公开下载页数据：各目标 × 各通道的当前发布 + 最近历史。 */
    fun publicDownloads(historyLimit: Int = 50): PublicDownloads {
        val channels = listChannels(null)
        val targets = channels.groupBy { Triple(it.clientType, it.platform, it.arch) }
            .map { (target, rows) ->
                PublicDownloadTarget(
                    clientType = target.first,
                    platform = target.second,
                    arch = target.third,
                    channels = rows.sortedBy { it.channel }.map { row ->
                        PublicChannelState(
                            channel = row.channel,
                            enabled = row.enabled,
                            updatedAt = row.updatedAt,
                            release = if (row.enabled) row.currentReleaseId?.let { id ->
                                releaseInfo(id)?.takeIf { tx { releaseById(id)?.get(ClientReleases.status) == STATUS_ACTIVE } }
                            } else null,
                        )
                    },
                )
            }
            .sortedWith(compareBy({ it.clientType }, { it.platform }, { it.arch }))
        val history = listReleases(null, null, historyLimit, 0).map {
            PublicReleaseHistoryEntry(
                clientType = it.clientType,
                platform = it.platform,
                arch = it.arch,
                version = it.version,
                build = it.build,
                channel = it.channel,
                status = it.status,
                createdAt = it.createdAt,
                buildIdentity = it.buildIdentity,
            )
        }
        return PublicDownloads(targets, history)
    }

    @Serializable
    data class PublicDownloads(
        val targets: List<PublicDownloadTarget>,
        val history: List<PublicReleaseHistoryEntry>,
    )

    @Serializable
    data class PublicDownloadTarget(
        val clientType: String,
        val platform: String,
        val arch: String,
        val channels: List<PublicChannelState>,
    )

    @Serializable
    data class PublicChannelState(
        val channel: String,
        val enabled: Boolean,
        val updatedAt: Long,
        val release: ClientReleaseInfo?,
    )

    @Serializable
    data class PublicReleaseHistoryEntry(
        val clientType: String,
        val platform: String,
        val arch: String,
        val version: String,
        val build: Long,
        val channel: String,
        val status: String,
        val createdAt: Long,
        val buildIdentity: String? = null,
    )

    // ── CI 发布令牌 ─────────────────────────────────────────────────

    fun verifyPublishToken(token: String?): Boolean {
        val expected = publishTokenSha256 ?: return false
        if (token.isNullOrBlank() || token.length > 256) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        val actual = buildString {
            for (b in digest) append(String.format("%02x", b))
        }
        return MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private fun Transaction.releaseById(releaseId: Long) =
        ClientReleases.selectAll().where { ClientReleases.id eq releaseId }.singleOrNull()

    private fun Transaction.releaseByIdentity(metadata: ClientReleaseUploadMetadata, identity: String) =
        ClientReleases.selectAll().where {
            val sameVersion = (ClientReleases.clientType eq metadata.clientType) and
                (ClientReleases.platform eq metadata.platform) and
                (ClientReleases.arch eq metadata.arch) and
                (ClientReleases.version eq metadata.version) and
                (ClientReleases.build eq metadata.build)
            // 精确身份与数据库唯一键一致，不能按通道过滤掉已存在的同一构建。
            // 非 snapshot 还冻结 version/build；不同源码不能覆盖已分发的正式安装序号。
            val frozenVersion = if (metadata.channel != AndroidReleaseManifest.CHANNEL_SNAPSHOT) {
                ClientReleases.channel neq AndroidReleaseManifest.CHANNEL_SNAPSHOT
            } else org.jetbrains.exposed.sql.Op.FALSE
            sameVersion and ((ClientReleases.buildIdentity eq identity) or frozenVersion)
        }.orderBy(ClientReleases.createdAt).firstOrNull()

    private fun Transaction.artifactRows(releaseId: Long, kind: String?) =
        ClientReleaseFiles.selectAll()
            .where {
                val base = ClientReleaseFiles.releaseId eq releaseId
                if (kind != null) base and (ClientReleaseFiles.kind eq kind) else base
            }
            .toList()

    private fun Transaction.upsertChannelPointerLocked(
        metadata: ClientReleaseUploadMetadata,
        releaseId: Long,
        actor: String,
    ) = upsertChannelPointerRow(metadata.clientType, metadata.platform, metadata.arch, metadata.channel, releaseId, actor)

    private fun Transaction.upsertChannelPointerRow(
        clientType: String,
        platform: String,
        arch: String,
        channel: String,
        releaseId: Long?,
        actor: String,
    ) {
        val existing = ClientChannels.selectAll()
            .where {
                (ClientChannels.clientType eq clientType) and
                    (ClientChannels.platform eq platform) and
                    (ClientChannels.arch eq arch) and
                    (ClientChannels.channel eq channel)
            }
            .singleOrNull()
        if (existing == null) {
            ClientChannels.insert {
                it[this.clientType] = clientType
                it[this.platform] = platform
                it[this.arch] = arch
                it[this.channel] = channel
                it[currentReleaseId] = releaseId
                it[enabled] = true
                it[updatedBy] = actor.take(64)
                it[updatedAt] = System.currentTimeMillis()
            }
        } else {
            ClientChannels.update({
                (ClientChannels.clientType eq clientType) and
                    (ClientChannels.platform eq platform) and
                    (ClientChannels.arch eq arch) and
                    (ClientChannels.channel eq channel)
            }) {
                it[currentReleaseId] = releaseId
                it[enabled] = true
                it[updatedBy] = actor.take(64)
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    private fun fileUrl(sha256: String): String = "/api/v1/client/files/$sha256"

    private fun releaseIdentity(metadata: ClientReleaseUploadMetadata) =
        "${metadata.clientType}/${metadata.platform}/${metadata.arch} ${metadata.version}+${metadata.build}"

    private fun <T> tx(block: Transaction.() -> T): T = transaction(database) { block() }

    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_DISABLED = "DISABLED"
        const val STATUS_SUPERSEDED = "SUPERSEDED"


        fun publishTokenSha256FromEnvironment(): String? {
            val token = System.getenv("CLIENT_RELEASE_PUBLISH_TOKEN") ?: return null
            if (token.length < 16) {
                throw IllegalStateException("CLIENT_RELEASE_PUBLISH_TOKEN must be at least 16 characters")
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            return buildString {
                for (b in digest) append(String.format("%02x", b))
            }
        }

    }
}
