package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.telemetry.ClientTelemetryDeviceProfile
import com.virjar.tk.server.domain.telemetry.ClientTelemetryPolicy
import com.virjar.tk.server.domain.telemetry.ClientTelemetryControlRepository
import com.virjar.tk.server.domain.telemetry.TelemetryAdminAuditEntry
import com.virjar.tk.server.domain.telemetry.TelemetryCollectionMode
import com.virjar.tk.server.domain.telemetry.TelemetryDeviceAuthority
import com.virjar.tk.server.domain.telemetry.TelemetryDeviceFilter
import com.virjar.tk.server.domain.telemetry.TelemetryDeviceIdentity
import com.virjar.tk.server.domain.telemetry.TelemetryPage
import com.virjar.tk.server.domain.telemetry.TelemetryRuntimeSnapshot
import com.virjar.tk.server.domain.telemetry.TelemetryStoragePolicy
import com.virjar.tk.server.infra.db.ClientTelemetryAdminAudits
import com.virjar.tk.server.infra.db.ClientTelemetryDevices
import com.virjar.tk.server.infra.db.ClientTelemetryPolicies
import com.virjar.tk.server.infra.db.ClientTelemetryPolicyAudits
import com.virjar.tk.server.infra.db.Devices
import com.virjar.tk.server.infra.db.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.math.max

/** 低频设备与采集策略控制事实的 PostgreSQL 权威源。 */
class ExposedClientTelemetryControlRepository(
    private val database: Database,
) : ClientTelemetryControlRepository {

    override fun refreshDevice(
        authority: TelemetryDeviceAuthority,
        runtime: TelemetryRuntimeSnapshot,
        receivedAt: Long,
        acceptedEventAt: Long?,
        runtimeObservedAt: Long,
    ): Boolean = transaction(database) {
        require(authority.uid.isNotBlank()) { "uid must not be blank" }
        require(authority.deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(authority.userCredentialEpoch > 0L && authority.deviceCredentialEpoch > 0L) {
            "telemetry credential epochs must be positive"
        }
        require(receivedAt > 0L) { "receivedAt must be positive" }
        require(acceptedEventAt == null || acceptedEventAt > 0L) { "acceptedEventAt must be positive" }
        require(runtimeObservedAt in 1L..receivedAt) { "runtimeObservedAt must not follow receivedAt" }

        // 每次凭据变更都会先锁定此不可驱逐的行。在同一把锁下重新校验两个 epoch，
        // 可以防止在途上传复活一个被回收的档案，
        // 或把旧运行时事实附着到复用了同一设备 id 的新安装上。
        val user = Users.selectAll().where { Users.uid eq authority.uid }.forUpdate().singleOrNull()
            ?: return@transaction false
        if (user[Users.status] != STATUS_ACTIVE ||
            user[Users.credentialEpoch] != authority.userCredentialEpoch
        ) {
            return@transaction false
        }
        val device = Devices.selectAll().where {
            (Devices.uid eq authority.uid) and (Devices.deviceId eq authority.deviceId)
        }.forUpdate().singleOrNull() ?: return@transaction false
        if (device[Devices.status] != STATUS_ACTIVE ||
            device[Devices.credentialEpoch] != authority.deviceCredentialEpoch
        ) {
            return@transaction false
        }
        upsertDevice(
            authority.uid,
            authority.deviceId,
            runtime,
            receivedAt,
            acceptedEventAt,
            runtimeObservedAt,
        )
        true
    }

    override fun effectivePolicy(uid: String, deviceId: String, now: Long): ClientTelemetryPolicy =
        transaction(database) {
            val rows = ClientTelemetryPolicies.selectAll().where {
                (ClientTelemetryPolicies.targetUid eq uid) and
                    (
                        (ClientTelemetryPolicies.targetDeviceKey eq "") or
                            (ClientTelemetryPolicies.targetDeviceKey eq deviceId)
                        )
            }.toList()
            resolvePolicy(uid, deviceId, rows, now)
        }

    override fun effectivePolicies(
        devices: Set<TelemetryDeviceIdentity>,
        now: Long,
    ): Map<TelemetryDeviceIdentity, ClientTelemetryPolicy> {
        if (devices.isEmpty()) return emptyMap()
        return transaction(database) {
            val uids = devices.mapTo(linkedSetOf(), TelemetryDeviceIdentity::uid)
            val deviceIds = devices.mapTo(linkedSetOf(), TelemetryDeviceIdentity::deviceId)
            val rowsByUid = ClientTelemetryPolicies.selectAll().where {
                (ClientTelemetryPolicies.targetUid inList uids.toList()) and
                    (
                        (ClientTelemetryPolicies.targetDeviceKey eq "") or
                            (ClientTelemetryPolicies.targetDeviceKey inList deviceIds.toList())
                        )
            }.groupBy { it[ClientTelemetryPolicies.targetUid] }
            devices.associateWith { device ->
                val relevant = rowsByUid[device.uid].orEmpty().filter { row ->
                    val key = row[ClientTelemetryPolicies.targetDeviceKey]
                    key.isEmpty() || key == device.deviceId
                }
                resolvePolicy(device.uid, device.deviceId, relevant, now)
            }
        }
    }

    override fun pageDevices(
        filter: TelemetryDeviceFilter,
        offset: Long,
        limit: Int,
    ): TelemetryPage<ClientTelemetryDeviceProfile> = transaction(database) {
        require(offset >= 0L) { "offset must not be negative" }
        require(limit in 1..TelemetryStoragePolicy.MAX_ADMIN_PAGE) { "invalid telemetry device page" }
        val condition = deviceQuery(filter)
        val filtered = ClientTelemetryDevices.selectAll().where { condition }
        TelemetryPage(
            total = filtered.count(),
            items = filtered.orderBy(
                ClientTelemetryDevices.lastSeenAt to SortOrder.DESC,
                ClientTelemetryDevices.uid to SortOrder.ASC,
                ClientTelemetryDevices.deviceId to SortOrder.ASC,
            ).limit(limit).offset(offset).map { it.toDeviceProfile() },
        )
    }

    override fun findDevice(uid: String, deviceId: String): ClientTelemetryDeviceProfile? = transaction(database) {
        ClientTelemetryDevices.selectAll().where {
            (ClientTelemetryDevices.uid eq uid) and (ClientTelemetryDevices.deviceId eq deviceId)
        }.singleOrNull()?.toDeviceProfile()
    }

    override fun pagePolicies(offset: Long, limit: Int): TelemetryPage<ClientTelemetryPolicy> =
        transaction(database) {
            require(offset >= 0L) { "offset must not be negative" }
            require(limit in 1..TelemetryStoragePolicy.MAX_ADMIN_PAGE) { "invalid telemetry policy page" }
            val query = ClientTelemetryPolicies.selectAll()
            TelemetryPage(
                total = query.count(),
                items = query.orderBy(
                    ClientTelemetryPolicies.updatedAt to SortOrder.DESC,
                    ClientTelemetryPolicies.id to SortOrder.DESC,
                ).limit(limit).offset(offset).map { it.toPolicy() },
            )
        }

    override fun enableDiagnosticPolicy(
        uid: String,
        deviceId: String?,
        reason: String,
        expiresAt: Long,
        actor: String,
        now: Long,
        successAudit: TelemetryAdminAuditEntry?,
    ): ClientTelemetryPolicy = transaction(database) {
        require(reason.isNotBlank() && reason.length <= TelemetryStoragePolicy.MAX_POLICY_REASON_CHARS) {
            "diagnostic reason is required"
        }
        require(expiresAt > now && expiresAt - now <= TelemetryStoragePolicy.MAX_POLICY_DURATION_MILLIS) {
            "diagnostic policy duration must be within 24 hours"
        }
        require(actor.isNotBlank() && actor.length <= MAX_ACTOR_CHARS) { "invalid telemetry policy actor" }
        require(Users.selectAll().where { Users.uid eq uid }.forUpdate().singleOrNull() != null) {
            "telemetry policy account does not exist"
        }
        val deviceKey = deviceId.orEmpty()
        if (deviceId != null) {
            require(
                ClientTelemetryDevices.selectAll().where {
                    (ClientTelemetryDevices.uid eq uid) and
                        (ClientTelemetryDevices.deviceId eq deviceId)
                }.singleOrNull() != null,
            ) { "telemetry device does not exist" }
        }
        val existing = ClientTelemetryPolicies.selectAll().where {
            (ClientTelemetryPolicies.targetUid eq uid) and
                (ClientTelemetryPolicies.targetDeviceKey eq deviceKey)
        }.forUpdate().singleOrNull()
        val revision = nextUidRevision(uid, now)
        val policyId = if (existing == null) {
            ClientTelemetryPolicies.insert {
                it[targetUid] = uid
                it[targetDeviceKey] = deviceKey
                it[mode] = MODE_DIAGNOSTIC
                it[ClientTelemetryPolicies.revision] = revision
                it[ClientTelemetryPolicies.reason] = reason.trim()
                it[ClientTelemetryPolicies.expiresAt] = expiresAt
                it[updatedAt] = now
                it[updatedBy] = actor
            }[ClientTelemetryPolicies.id].value
        } else {
            val id = existing[ClientTelemetryPolicies.id]
            check(ClientTelemetryPolicies.update({ ClientTelemetryPolicies.id eq id }) {
                it[mode] = MODE_DIAGNOSTIC
                it[ClientTelemetryPolicies.revision] = revision
                it[ClientTelemetryPolicies.reason] = reason.trim()
                it[ClientTelemetryPolicies.expiresAt] = expiresAt
                it[updatedAt] = now
                it[updatedBy] = actor
            } == 1)
            id.value
        }
        appendAudit(policyId, uid, deviceKey, "ENABLE", MODE_DIAGNOSTIC, revision, reason, expiresAt, actor, now)
        successAudit?.let(::appendAdminAudit)
        ClientTelemetryPolicies.selectAll().where { ClientTelemetryPolicies.id eq policyId }
            .single().toPolicy()
    }

    override fun disablePolicy(
        policyId: String,
        actor: String,
        now: Long,
        successAudit: TelemetryAdminAuditEntry?,
    ): ClientTelemetryPolicy? =
        transaction(database) {
            val numericId = policyId.toLongOrNull() ?: return@transaction null
            require(actor.isNotBlank() && actor.length <= MAX_ACTOR_CHARS) { "invalid telemetry policy actor" }
            val candidate = ClientTelemetryPolicies.selectAll().where {
                ClientTelemetryPolicies.id eq numericId
            }.singleOrNull() ?: return@transaction null
            val uid = candidate[ClientTelemetryPolicies.targetUid]
            check(Users.selectAll().where { Users.uid eq uid }.forUpdate().singleOrNull() != null) {
                "telemetry policy account does not exist"
            }
            val existing = ClientTelemetryPolicies.selectAll().where {
                ClientTelemetryPolicies.id eq numericId
            }.forUpdate().singleOrNull() ?: return@transaction null
            val revision = nextUidRevision(uid, now)
            check(ClientTelemetryPolicies.update({ ClientTelemetryPolicies.id eq numericId }) {
                it[mode] = MODE_BASELINE
                it[ClientTelemetryPolicies.revision] = revision
                it[expiresAt] = null
                it[updatedAt] = now
                it[updatedBy] = actor
            } == 1)
            appendAudit(
                policyId = numericId,
                uid = existing[ClientTelemetryPolicies.targetUid],
                deviceKey = existing[ClientTelemetryPolicies.targetDeviceKey],
                action = "DISABLE",
                mode = MODE_BASELINE,
                revision = revision,
                reason = existing[ClientTelemetryPolicies.reason],
                expiresAt = null,
                actor = actor,
                now = now,
            )
            successAudit?.let(::appendAdminAudit)
            ClientTelemetryPolicies.selectAll().where { ClientTelemetryPolicies.id eq numericId }
                .single().toPolicy()
        }

    override fun expirePolicies(now: Long, limit: Int): Int {
        require(limit in 1..TelemetryStoragePolicy.MAX_RETENTION_DELETE_BATCHES) {
            "invalid telemetry policy expiry batch size"
        }
        return transaction(database) {
            val candidates = ClientTelemetryPolicies.selectAll().where {
                (ClientTelemetryPolicies.mode eq MODE_DIAGNOSTIC) and
                    (ClientTelemetryPolicies.expiresAt lessEq now)
            }.orderBy(ClientTelemetryPolicies.expiresAt, SortOrder.ASC).limit(limit).toList()
            if (candidates.isEmpty()) return@transaction 0
            val uids = candidates.map { it[ClientTelemetryPolicies.targetUid] }.distinct().sorted()
            val lockedUsers = Users.selectAll().where { Users.uid inList uids }
                .orderBy(Users.uid, SortOrder.ASC)
                .forUpdate()
                .map { it[Users.uid] }
            check(lockedUsers == uids) { "telemetry policy account does not exist" }
            val candidateIds = candidates.map { it[ClientTelemetryPolicies.id] }
            val rows = ClientTelemetryPolicies.selectAll().where {
                (ClientTelemetryPolicies.id inList candidateIds) and
                    (ClientTelemetryPolicies.mode eq MODE_DIAGNOSTIC) and
                    (ClientTelemetryPolicies.expiresAt lessEq now)
            }.orderBy(ClientTelemetryPolicies.expiresAt, SortOrder.ASC).forUpdate().toList()
            val revisionByUid = ClientTelemetryPolicies.selectAll().where {
                ClientTelemetryPolicies.targetUid inList uids
            }.groupBy { it[ClientTelemetryPolicies.targetUid] }
                .mapValuesTo(mutableMapOf()) { (_, policies) ->
                    policies.maxOf { it[ClientTelemetryPolicies.revision] }
                }
            rows.forEach { row ->
                val policyId = row[ClientTelemetryPolicies.id].value
                val uid = row[ClientTelemetryPolicies.targetUid]
                val revision = nextRevision(revisionByUid[uid], now)
                revisionByUid[uid] = revision
                check(ClientTelemetryPolicies.update({ ClientTelemetryPolicies.id eq policyId }) {
                    it[mode] = MODE_BASELINE
                    it[ClientTelemetryPolicies.revision] = revision
                    it[expiresAt] = null
                    it[updatedAt] = now
                    it[updatedBy] = SYSTEM_ACTOR
                } == 1)
                appendAudit(
                    policyId,
                    row[ClientTelemetryPolicies.targetUid],
                    row[ClientTelemetryPolicies.targetDeviceKey],
                    "EXPIRE",
                    MODE_BASELINE,
                    revision,
                    row[ClientTelemetryPolicies.reason],
                    null,
                    SYSTEM_ACTOR,
                    now,
                )
            }
            rows.size
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.upsertDevice(
        uid: String,
        deviceId: String,
        runtime: TelemetryRuntimeSnapshot,
        receivedAt: Long,
        acceptedEventAt: Long?,
        runtimeObservedAt: Long,
    ) {
        val existing = ClientTelemetryDevices.selectAll().where {
            (ClientTelemetryDevices.uid eq uid) and (ClientTelemetryDevices.deviceId eq deviceId)
        }.singleOrNull()
        if (existing == null) {
            ClientTelemetryDevices.insert {
                it[ClientTelemetryDevices.uid] = uid
                it[ClientTelemetryDevices.deviceId] = deviceId
                it[platform] = runtime.platform
                it[osName] = runtime.osName
                it[osVersion] = runtime.osVersion
                it[architecture] = runtime.architecture
                it[deviceModel] = runtime.deviceModel
                it[appVersion] = runtime.appVersion
                it[buildNumber] = runtime.buildNumber
                it[gitCommit] = runtime.gitCommit
                it[buildIdentity] = runtime.buildIdentity
                it[buildTime] = runtime.buildTime
                it[protocolVersion] = runtime.protocolVersion
                it[distribution] = runtime.distribution
                it[firstSeenAt] = minOf(receivedAt, runtimeObservedAt)
                it[lastSeenAt] = receivedAt
                it[ClientTelemetryDevices.runtimeObservedAt] = runtimeObservedAt
                it[lastEventAt] = acceptedEventAt
            }
        } else {
            val latestSeenAt = existing[ClientTelemetryDevices.lastSeenAt]
            val latestRuntimeObservedAt = existing[ClientTelemetryDevices.runtimeObservedAt]
            val latestEventAt = maxOf(
                existing[ClientTelemetryDevices.lastEventAt] ?: Long.MIN_VALUE,
                acceptedEventAt ?: Long.MIN_VALUE,
            ).takeUnless { it == Long.MIN_VALUE }
            ClientTelemetryDevices.update({
                (ClientTelemetryDevices.uid eq uid) and (ClientTelemetryDevices.deviceId eq deviceId)
            }) {
                // 相同的观测时间刻意采用 first-writer-wins。已提交的批次
                // 重试会复用其原始毫秒；若允许相等，就会让一个更旧的
                // 运行时覆盖在同一毫秒内已被接受的另一个批次。
                if (runtimeObservedAt > latestRuntimeObservedAt) {
                    it[platform] = runtime.platform
                    it[osName] = runtime.osName
                    it[osVersion] = runtime.osVersion
                    it[architecture] = runtime.architecture
                    it[deviceModel] = runtime.deviceModel
                    it[appVersion] = runtime.appVersion
                    it[buildNumber] = runtime.buildNumber
                    it[gitCommit] = runtime.gitCommit
                    it[buildIdentity] = runtime.buildIdentity
                    it[buildTime] = runtime.buildTime
                    it[protocolVersion] = runtime.protocolVersion
                    it[distribution] = runtime.distribution
                    it[ClientTelemetryDevices.runtimeObservedAt] = runtimeObservedAt
                }
                it[firstSeenAt] = minOf(
                    existing[ClientTelemetryDevices.firstSeenAt],
                    receivedAt,
                    runtimeObservedAt,
                )
                it[lastSeenAt] = maxOf(latestSeenAt, receivedAt)
                it[lastEventAt] = latestEventAt
            }
        }
    }

    private fun deviceQuery(filter: TelemetryDeviceFilter): Op<Boolean> {
        val identity = filter.exactUid?.let { ClientTelemetryDevices.uid eq it } ?: Op.TRUE
        val term = filter.text?.trim()?.takeIf(String::isNotEmpty) ?: return identity
        val pattern = "%${escapePostgresLikeLiteral(term)}%"
        val fuzzy = (ClientTelemetryDevices.uid like pattern) or
            (ClientTelemetryDevices.deviceId like pattern) or
            (ClientTelemetryDevices.platform like pattern) or
            (ClientTelemetryDevices.osName like pattern) or
            (ClientTelemetryDevices.osVersion like pattern) or
            (ClientTelemetryDevices.architecture like pattern) or
            (ClientTelemetryDevices.deviceModel like pattern) or
            (ClientTelemetryDevices.appVersion like pattern) or
            (ClientTelemetryDevices.gitCommit like pattern) or
            (ClientTelemetryDevices.buildIdentity like pattern) or
            (ClientTelemetryDevices.buildTime like pattern)
        return identity and fuzzy
    }

    private fun ResultRow.toDeviceProfile() = ClientTelemetryDeviceProfile(
        uid = this[ClientTelemetryDevices.uid],
        deviceId = this[ClientTelemetryDevices.deviceId],
        runtime = TelemetryRuntimeSnapshot(
            platform = this[ClientTelemetryDevices.platform],
            osName = this[ClientTelemetryDevices.osName],
            osVersion = this[ClientTelemetryDevices.osVersion],
            architecture = this[ClientTelemetryDevices.architecture],
            deviceModel = this[ClientTelemetryDevices.deviceModel],
            appVersion = this[ClientTelemetryDevices.appVersion],
            buildNumber = this[ClientTelemetryDevices.buildNumber],
            gitCommit = this[ClientTelemetryDevices.gitCommit],
            buildIdentity = this[ClientTelemetryDevices.buildIdentity],
            buildTime = this[ClientTelemetryDevices.buildTime],
            protocolVersion = this[ClientTelemetryDevices.protocolVersion],
            distribution = this[ClientTelemetryDevices.distribution],
        ),
        firstSeenAt = this[ClientTelemetryDevices.firstSeenAt],
        lastSeenAt = this[ClientTelemetryDevices.lastSeenAt],
        lastEventAt = this[ClientTelemetryDevices.lastEventAt],
    )

    private fun ResultRow.toPolicy(revisionOverride: Long? = null) = ClientTelemetryPolicy(
        policyId = this[ClientTelemetryPolicies.id].value.toString(),
        uid = this[ClientTelemetryPolicies.targetUid],
        deviceId = this[ClientTelemetryPolicies.targetDeviceKey].takeIf(String::isNotEmpty),
        mode = if (this[ClientTelemetryPolicies.mode] == MODE_DIAGNOSTIC) {
            TelemetryCollectionMode.DIAGNOSTIC
        } else {
            TelemetryCollectionMode.BASELINE
        },
        revision = revisionOverride ?: this[ClientTelemetryPolicies.revision],
        reason = this[ClientTelemetryPolicies.reason],
        expiresAt = this[ClientTelemetryPolicies.expiresAt],
        updatedAt = this[ClientTelemetryPolicies.updatedAt],
        updatedBy = this[ClientTelemetryPolicies.updatedBy],
    )

    private fun resolvePolicy(
        uid: String,
        deviceId: String,
        rows: List<ResultRow>,
        now: Long,
    ): ClientTelemetryPolicy {
        val revision = rows.maxOfOrNull { it[ClientTelemetryPolicies.revision] } ?: 0L
        // 客户端按 issuedAt 对策略排序。取 uid 级与精确设备行中的最大逻辑 revision，
        // 使较旧的精确终结策略在之后的宽泛策略变更后仍然保持权威，
        // 包括同一墙钟毫秒内的多次变更。
        val effectiveUpdatedAt = maxOf(
            revision,
            rows.maxOfOrNull { it[ClientTelemetryPolicies.updatedAt] } ?: now,
        )
        val exact = rows.singleOrNull {
            it[ClientTelemetryPolicies.targetDeviceKey] == deviceId
        }
        if (exact != null) {
            val policy = exact.toPolicy(revisionOverride = revision).copy(updatedAt = effectiveUpdatedAt)
            return if (policy.mode == TelemetryCollectionMode.DIAGNOSTIC &&
                policy.expiresAt?.let { it > now } == true
            ) {
                policy
            } else {
                policy.copy(mode = TelemetryCollectionMode.BASELINE)
            }
        }

        val uidWide = rows.singleOrNull { it[ClientTelemetryPolicies.targetDeviceKey].isEmpty() }
            ?: return baselinePolicy(uid, deviceId, revision, now)
        val policy = uidWide.toPolicy(revisionOverride = revision).copy(updatedAt = effectiveUpdatedAt)
        return if (policy.mode == TelemetryCollectionMode.DIAGNOSTIC &&
            policy.expiresAt?.let { it > now } == true
        ) {
            policy
        } else {
            policy.copy(mode = TelemetryCollectionMode.BASELINE)
        }
    }

    private fun baselinePolicy(uid: String, deviceId: String, revision: Long, now: Long) =
        ClientTelemetryPolicy(
            policyId = null,
            uid = uid,
            deviceId = deviceId,
            mode = TelemetryCollectionMode.BASELINE,
            revision = revision,
            reason = null,
            expiresAt = null,
            updatedAt = now,
            updatedBy = SYSTEM_ACTOR,
        )

    private fun appendAudit(
        policyId: Long,
        uid: String,
        deviceKey: String,
        action: String,
        mode: Int,
        revision: Long,
        reason: String?,
        expiresAt: Long?,
        actor: String,
        now: Long,
    ) {
        ClientTelemetryPolicyAudits.insert {
            it[ClientTelemetryPolicyAudits.policyId] = policyId
            it[targetUid] = uid
            it[targetDeviceKey] = deviceKey
            it[ClientTelemetryPolicyAudits.action] = action
            it[ClientTelemetryPolicyAudits.mode] = mode
            it[ClientTelemetryPolicyAudits.revision] = revision
            it[ClientTelemetryPolicyAudits.reason] = reason?.trim()
            it[ClientTelemetryPolicyAudits.expiresAt] = expiresAt
            it[ClientTelemetryPolicyAudits.actor] = actor
            it[createdAt] = now
        }
    }

    /** 必须从策略事务中调用，使强制的管理员回执不会在之后失败。 */
    private fun appendAdminAudit(entry: TelemetryAdminAuditEntry) {
        require(entry.actor.length in 1..MAX_ACTOR_CHARS && entry.actor.none(Char::isISOControl)) {
            "invalid telemetry audit actor"
        }
        require(entry.target.length in 1..MAX_ADMIN_AUDIT_TARGET_CHARS && entry.target.none(Char::isISOControl)) {
            "invalid telemetry audit target"
        }
        require(entry.occurredAt > 0L) { "invalid telemetry audit time" }
        ClientTelemetryAdminAudits.insert {
            it[actor] = entry.actor
            it[action] = entry.action.name
            it[target] = entry.target
            it[result] = entry.result.name
            it[createdAt] = entry.occurredAt
        }
    }

    private fun nextRevision(previous: Long?, now: Long): Long = max(previous?.plus(1L) ?: 1L, now)

    private fun org.jetbrains.exposed.sql.Transaction.nextUidRevision(uid: String, now: Long): Long {
        val previous = ClientTelemetryPolicies.selectAll().where {
            ClientTelemetryPolicies.targetUid eq uid
        }.maxOfOrNull { it[ClientTelemetryPolicies.revision] }
        return nextRevision(previous, now)
    }

    private companion object {
        const val STATUS_ACTIVE = 1
        const val MODE_BASELINE = 0
        const val MODE_DIAGNOSTIC = 1
        const val SYSTEM_ACTOR = "system"
        const val MAX_ACTOR_CHARS = 100
        const val MAX_ADMIN_AUDIT_TARGET_CHARS = 180
    }
}
