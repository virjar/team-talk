package com.virjar.tk.server.domain.user

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.server.domain.auth.PasswordHasher
import com.virjar.tk.server.domain.attachment.AttachmentCatalog
import com.virjar.tk.server.domain.attachment.AttachmentLifecycleGate
import com.virjar.tk.server.domain.contact.ContactPolicy
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.ProfilePatch
import com.virjar.tk.protocol.model.ProfilePatchValue
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.model.UserAvatarPolicy
import com.virjar.tk.protocol.model.UserRole
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID

private const val MIN_SEARCH_CHARS = 3
private const val MIN_CJK_CHARS = 2
private const val MAX_SEARCH_CHARS = 100
private const val MAX_SEARCH_RESULTS = 20
private val CJK_RANGE = 0x4E00..0x9FFF

private data class ProfileMutationOutcome(
    val committed: CommittedUserProfileChange?,
    val avatarPublicationFailure: Throwable? = null,
)

class UserService(
    private val users: UserRepository,
    private val unitOfWork: PgUnitOfWork,
    private val passwordHasher: PasswordHasher,
    private val profileAudience: UserProfileAudience,
    private val attachmentCatalog: AttachmentCatalog,
    private val attachmentLifecycle: AttachmentLifecycleGate,
    private val profileChanges: UserProfileChangePublisher,
) {
    private val logger = LoggerFactory.getLogger(UserService::class.java)
    class CredentialLoginProof(
        val user: User,
        val userCredentialEpoch: Long,
        val passwordHashSnapshot: String,
    ) {
        override fun toString(): String =
            "CredentialLoginProof(uid=${user.uid}, userCredentialEpoch=$userCredentialEpoch, " +
                "passwordHashSnapshot=<redacted>)"
    }
    class PasswordChangeProof(
        val expectedPasswordHash: String,
        val newPasswordHash: String,
    ) {
        override fun toString(): String = "PasswordChangeProof(<redacted>)"
    }

    suspend fun login(username: String, password: String): User =
        authenticateForCredentialIssue(username, password).user

    suspend fun authenticateForCredentialIssue(username: String, password: String): CredentialLoginProof {
        AuthRules.validateLogin(username, password)
        val internal = users.findInternalByUsername(username)
        val eligibleHuman = internal?.user?.let { user ->
            user.status == STATUS_ACTIVE && user.role == UserRole.HUMAN
        } == true

        // 策略在 BCrypt 之前选择：缺失、被封禁与服务身份绝不会暴露或测试其存储的校验器，
        // 但仍消耗一个等效的假 BCrypt 操作。
        val passwordMatches = passwordHasher.verify(
            rawPassword = password,
            encodedHash = internal?.passwordHash?.takeIf { eligibleHuman },
        )
        if (internal == null) throw invalidCredentials()
        if (internal.user.status != STATUS_ACTIVE) {
            throw IllegalArgumentException("账号已被封禁")
        }
        if (internal.user.role != UserRole.HUMAN) {
            throw IllegalArgumentException("服务账户不能使用客户端密码登录")
        }
        if (!passwordMatches) throw invalidCredentials()
        return CredentialLoginProof(internal.user, internal.credentialEpoch, internal.passwordHash)
    }

    /** 创建没有客户端登录能力的服务账户。外部调用只能使用其独立应用凭据。 */
    fun createServiceAccount(
        transaction: PgWriteTransactionContext,
        name: String,
        role: Int = UserRole.BOT,
    ): User {
        require(role == UserRole.BOT || role == UserRole.SYSTEM) { "非法服务账户类型" }
        require(name.isNotBlank()) { "服务账户名称不能为空" }
        // UUID 熵使预读变得不必要。仓储在现有机器人聚合事务内写入一个随机的非 BCrypt
        // 凭证标记；没有密码工作或嵌套事务能进入该锁序列。
        val uid = UUID.randomUUID().toString()
        val username = "bot-$uid"
        return users.createServiceAccount(
            transaction = transaction,
            uid = uid,
            username = username,
            name = name.trim().take(100),
            role = role,
        )
    }

    fun getProfile(uid: String): User {
        return users.findByUid(uid) ?: throw IllegalArgumentException("用户不存在")
    }

    suspend fun updateProfile(uid: String, patch: ProfilePatch) {
        validateProfilePatch(patch)
        val outcome = if (patch.avatar.isPresent) {
            updateProfileWithAvatar(uid, patch, patch.avatar.valueOrNull)
        } else {
            ProfileMutationOutcome(persistProfilePatch(uid, patch))
        }
        publishCommittedProfileChange(outcome.committed)
        outcome.avatarPublicationFailure?.let { throw it }
    }

    private suspend fun updateProfileWithAvatar(
        uid: String,
        patch: ProfilePatch,
        requestedAvatar: Attachment?,
    ): ProfileMutationOutcome {
        while (true) {
            val observed = users.findByUid(uid) ?: throw IllegalArgumentException("用户不存在")
            val lockedPaths = buildSet {
                // 在保留实际路径围栏的同时，串行化一个用户的全部头像变更。
                add("$USER_AVATAR_MUTATION_KEY_PREFIX$uid")
                observed.avatar?.path?.let(::add)
                requestedAvatar?.path?.let(::add)
            }
            var retryWithCurrentPath = false
            var outcome: ProfileMutationOutcome? = null
            attachmentLifecycle.withReferenceMutation(lockedPaths) {
                val current = users.findByUid(uid) ?: throw IllegalArgumentException("用户不存在")
                val currentAvatarPath = current.avatar?.path
                if (currentAvatarPath != null && currentAvatarPath !in lockedPaths) {
                    retryWithCurrentPath = true
                    return@withReferenceMutation
                }
                outcome = persistAvatarMutation(uid, patch, requestedAvatar, current)
            }
            if (!retryWithCurrentPath) return requireNotNull(outcome)
        }
    }

    private suspend fun persistAvatarMutation(
        uid: String,
        patch: ProfilePatch,
        requestedAvatar: Attachment?,
        current: User,
    ): ProfileMutationOutcome {
        if (requestedAvatar == null) {
            publishCurrentAvatarBeforeReplacement(uid, current.avatar)
            return ProfileMutationOutcome(persistProfilePatch(uid, patch))
        }

        val authoritative = attachmentCatalog.getAttachment(requestedAvatar.path)
        require(authoritative == requestedAvatar) { "头像附件与 FileStore 权威元数据不一致" }
        require(attachmentCatalog.getOwnerUid(requestedAvatar.path) == uid) { "只能使用本人上传的头像" }

        if (current.avatar == requestedAvatar) {
            // PostgreSQL 可能已提交，而 FileStore 发布或响应失败了。先修复发布，再剥离头像，
            // 使本次重试无法在并发清除/替换之后恢复一个旧引用。
            if (attachmentCatalog.isStaging(requestedAvatar.path)) {
                attachmentCatalog.markBusinessBound(listOf(requestedAvatar.path))
            }
            return ProfileMutationOutcome(
                persistProfilePatch(uid, patch.copy(avatar = ProfilePatchValue.Unchanged)),
            )
        }

        require(attachmentCatalog.isStaging(requestedAvatar.path)) { "头像附件已绑定到其他业务引用" }
        publishCurrentAvatarBeforeReplacement(uid, current.avatar)

        // PostgreSQL 引用是可恢复的事实。在这个微小的暂存窗口期内，当前头像仍是已认证
        // 读取。失败的发布由精确重试或在任何后续替换/清除之前修复。
        val committed = persistProfilePatch(uid, patch)
        val publicationFailure = try {
            attachmentCatalog.markBusinessBound(listOf(requestedAvatar.path))
            null
        } catch (failure: Exception) {
            failure
        }
        return ProfileMutationOutcome(committed, publicationFailure)
    }

    private fun publishCurrentAvatarBeforeReplacement(uid: String, currentAvatar: Attachment?) {
        if (currentAvatar == null || !attachmentCatalog.isStaging(currentAvatar.path)) return
        require(attachmentCatalog.getAttachment(currentAvatar.path) == currentAvatar) {
            "当前头像与 FileStore 权威元数据不一致"
        }
        require(attachmentCatalog.getOwnerUid(currentAvatar.path) == uid) { "当前头像上传者不一致" }
        attachmentCatalog.markBusinessBound(listOf(currentAvatar.path))
    }

    private suspend fun persistProfilePatch(
        uid: String,
        patch: ProfilePatch,
    ): CommittedUserProfileChange? = unitOfWork.write {
        val mutation = users.updateProfile(transaction, uid, patch)
        if (!mutation.changed) return@write null
        val friendUids = profileAudience.getFriendUids(transaction, uid)
        check(friendUids.size <= ContactPolicy.MAX_FRIENDS_PER_USER && uid !in friendUids) {
            "Profile audience adapter violated the active-friend boundary"
        }
        val recipients = linkedSetOf(uid).apply {
            addAll(friendUids.sorted())
        }
        recipients.forEach { recipientUid ->
            appendEvent(recipientUid, NotifyType.USER_UPDATED, mutation.user)
        }
        CommittedUserProfileChange(mutation.user, recipients.toSet())
    }

    private suspend fun publishCommittedProfileChange(change: CommittedUserProfileChange?) {
        if (change == null) return
        withContext(NonCancellable) {
            try {
                profileChanges.publish(change.user, change.durableRecipientUids)
            } catch (failure: Exception) {
                // 资料事实与持久化的本人/好友事件已提交。头像发布可能仍是这个尽力而为
                // 提示之后返回的原始失败。
                logger.warn(
                    "Failed transient profile broadcast uid={}; reconnect/RPC refresh remains authoritative",
                    change.user.uid,
                    failure,
                )
            }
        }
    }

    fun search(keyword: String, limit: Int = 20): List<User> {
        // 防注册用户枚举：短关键词几乎等价于全表遍历（26 字母 + 数千常用汉字
        // 即可穷举召回所有注册用户）。只计算字母/数字等字面搜索字符，
        // SQL LIKE 元字符不能用来伪造最小搜索熕。
        val trimmed = keyword.trim()
        require(trimmed.length <= MAX_SEARCH_CHARS) { "搜索关键词不能超过 $MAX_SEARCH_CHARS 个字符" }
        require(limit in 1..MAX_SEARCH_RESULTS) { "搜索结果数量必须在 1..$MAX_SEARCH_RESULTS 之间" }
        val literalCharacters = trimmed.filter(Char::isLetterOrDigit)
        val cjkCount = literalCharacters.count { it.code in CJK_RANGE }
        val minAllowed = if (cjkCount == literalCharacters.length && cjkCount > 0) {
            MIN_CJK_CHARS
        } else {
            MIN_SEARCH_CHARS
        }
        require(literalCharacters.length >= minAllowed) {
            "搜索关键词太短：汉字至少 ${MIN_CJK_CHARS} 个字，字母/数字至少 ${MIN_SEARCH_CHARS} 个字符"
        }
        val results = users.searchPublicDirectory(trimmed, limit)
        // 在这里过滤会让一个无效适配器在被排除行移除之前就消耗掉有界结果槽位。
        // 改为默认拒绝（fail closed）；持久化端口承诺在排序与 LIMIT 之前应用可见性谓词。
        check(results.size <= limit && results.all { isPublicDirectoryUser(it) }) {
            "Public user directory adapter violated its visibility or capacity contract"
        }
        val orderedResults = results.sortedWith(PUBLIC_DIRECTORY_ORDER)
        logger.info("user search completed: queryChars={} results={}", trimmed.length, orderedResults.size)
        return orderedResults
    }

    fun findByUid(uid: String): User? = users.findByUid(uid)

    private fun validateProfilePatch(patch: ProfilePatch) {
        patch.name.valueOrNull?.let { name ->
            require(name.isNotBlank()) { "显示名不能为空" }
            require(name.length <= 100) { "显示名不能超过 100 个字符" }
        }
        patch.avatar.valueOrNull?.let(UserAvatarPolicy::requireCanonical)
        patch.phone.valueOrNull?.let { phone ->
            require(phone.length <= 20) { "手机号不能超过 20 个字符" }
        }
    }

    /** 仓储 IO 与 CPU 哈希是分开的挂起边界；提交时重新校验快照。 */
    suspend fun preparePasswordChange(
        uid: String,
        oldPassword: String,
        newPassword: String,
    ): PasswordChangeProof {
        AuthRules.validatePassword(newPassword)?.let { throw IllegalArgumentException(it) }
        if (AuthRules.validatePassword(oldPassword) != null) throw IllegalArgumentException("旧密码错误")
        val internal = users.findInternalByUid(uid) ?: throw IllegalArgumentException("用户不存在")
        val eligibleHuman = internal.user.status == STATUS_ACTIVE && internal.user.role == UserRole.HUMAN
        val oldPasswordMatches = passwordHasher.verify(
            rawPassword = oldPassword,
            encodedHash = internal.passwordHash.takeIf { eligibleHuman },
        )
        if (!eligibleHuman || !oldPasswordMatches) {
            throw IllegalArgumentException("旧密码错误")
        }
        val newHash = passwordHasher.hash(newPassword)
        return PasswordChangeProof(internal.passwordHash, newHash)
    }

    private fun invalidCredentials() = IllegalArgumentException("用户名或密码错误")

    private companion object {
        const val USER_AVATAR_MUTATION_KEY_PREFIX = "user-avatar-mutation:"
        const val STATUS_ACTIVE = 1
        val PUBLIC_DIRECTORY_ORDER = compareBy<User>(User::name, User::username, User::uid)

        fun isPublicDirectoryUser(user: User): Boolean =
            user.status == STATUS_ACTIVE && user.role == UserRole.HUMAN
    }
}
