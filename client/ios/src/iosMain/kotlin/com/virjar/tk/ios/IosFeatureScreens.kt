@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.ios

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.virjar.tk.app.navigation.ScreenDataKey
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.ui.bridge.EmbeddedAssetLocalSelection
import com.virjar.tk.app.ui.screen.*
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.protocol.model.*
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.platform.*
import com.virjar.tk.shared.repository.ResolvedContentSearchHit
import com.virjar.tk.shared.repository.asUploadSource
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import org.jetbrains.skia.Image
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.ImageIO.*
import platform.UIKit.*

/** Native picker results belong to this route, while accepted commands retain the session owner. */
private class IosFeatureActions(val ui: IosSessionUi, private val route: IosRoute) : AutoCloseable {
    private var active = true
    private val mediaJobs = mutableSetOf<Job>()
    val admission = UiActionAdmission { action ->
        active && ui.navigation.current == route && ui.data.uiActionAdmission.runIfOpen(action)
    }
    val back = admission.guard(ui.navigation::back)

    suspend fun <T> run(onClosed: () -> T, action: suspend () -> T): T =
        ui.data.runAdmittedUiAction(admission, onClosed, action)

    fun launch(action: suspend () -> Unit) { ui.data.launchAdmittedUiAction(admission, action) }

    fun chat(chatId: String, sequence: Long = 0, replaceCurrent: Boolean = false): Boolean {
        var opened = false
        admission.runIfOpen {
            if (ui.data.chat.prepareChat(chatId)) {
                if (replaceCurrent) ui.navigation.back()
                ui.navigation.chat(chatId, sequence)
                opened = true
            }
        }
        return opened
    }

    fun selected(selection: EmbeddedAssetLocalSelection, action: suspend () -> Unit) {
        mediaJobs.removeAll { it.isCompleted }
        val job = ui.data.launchCancellableAdmittedUiAction(admission, action)
        if (job == null) releaseIosEmbeddedAssetSelection(selection)
        else {
            mediaJobs += job
            job.invokeOnCompletion { releaseIosEmbeddedAssetSelection(selection) }
        }
    }

    override fun close() {
        active = false
        mediaJobs.forEach { it.cancel() }
        mediaJobs.clear()
    }
}

@Composable
internal fun IosFeatureScreen(route: IosRoute, ui: IosSessionUi) = key(ui, route) {
    val actions = remember(ui, route) { IosFeatureActions(ui, route) }
    DisposableEffect(actions) { onDispose(actions::close) }
    val data = ui.data
    val admission = actions.admission
    val key = when (route.page) {
        IosPage.FRIEND_APPLIES -> ScreenDataKey.FriendApplies
        IosPage.USER_PROFILE -> ScreenDataKey.UserProfile(route.id)
        IosPage.DEVICES -> ScreenDataKey.Devices
        IosPage.BLACKLIST -> ScreenDataKey.Blacklist
        IosPage.GROUP_DETAIL -> ScreenDataKey.GroupDetail(route.id)
        IosPage.GROUP_FILES -> ScreenDataKey.GroupFiles(route.id)
        IosPage.GROUP_BOTS -> ScreenDataKey.GroupBots(route.id)
        IosPage.INVITE_MEMBERS -> ScreenDataKey.InviteMembers(route.id)
        IosPage.INVITE_LINKS -> ScreenDataKey.InviteLinks(route.id)
        else -> null
    }
    LaunchedEffect(key) { key?.let { actions.run({}) { data.loadScreenDataByKey(it) } } }
    when (route.page) {
        IosPage.CHAT_TOOLS -> {
            val conversations by data.conversationViewModel.conversations.collectAsState()
            val peers by data.conversationViewModel.peerUsers.collectAsState()
            val contacts by data.contactViewModel.contacts.collectAsState()
            val conversation = conversations.firstOrNull { it.chatId == route.id }
            val remarks = remember(contacts) { contactRemarks(contacts) }
            val name = conversation?.let {
                conversationIdentityPresentation(it, it.peerUid?.let(peers::get), it.peerUid?.let(remarks::get)).name
            } ?: "当前会话"
            ChatToolsScreen(
                chatName = name, isGroup = conversation?.chatType == ChatType.GROUP.code,
                onCreateGroup = admission.guard {
                    ui.navigation.open(IosRoute(IosPage.CREATE_GROUP, conversation?.peerUid.orEmpty()))
                },
                onClearHistory = { actions.run({ "会话已关闭" }) { data.chat.clearChatHistory(route.id) } },
                onFinished = actions.back, onBack = actions.back,
            )
        }
        IosPage.SEARCH -> IosSearchScreen(actions)
        IosPage.SEARCH_USERS -> SearchUsersScreen(
            searchUsers = { query -> actions.run({ emptyList() }) { data.discovery.searchUsers(query) } },
            onUserClick = admission.guard(ui.navigation::profile), onBack = actions.back,
        )
        IosPage.CREATE_GROUP -> {
            val contacts by data.contactViewModel.contacts.collectAsState()
            CreateGroupScreen(
                contacts, data.groups.pendingGroupCreation, data.groups.groupCreationDraftLoaded,
                data.groups.groupCreationDraftError,
                onCreateGroup = { name, uids ->
                    actions.run({ Result.failure(IllegalStateException("登录会话已结束")) }) {
                        val chatId = data.groups.create(name, uids)
                        if (chatId != null && actions.chat(chatId, replaceCurrent = true)) Result.success(chatId)
                        else Result.failure(IllegalStateException("创建群聊未完成，请重试"))
                    }
                },
                onDiscardPendingGroupCreation = { actions.run({ false }) { data.groups.discardPendingCreation() } },
                onSearchUsers = { query -> actions.run({ emptyList() }) { data.discovery.searchUsers(query) } },
                initialSelectedUids = setOfNotNull(route.id.takeIf(String::isNotBlank)), onBack = actions.back,
            )
        }
        IosPage.FRIEND_APPLIES -> FriendAppliesScreen(
            data.account.friendApplyRecords, data.account.friendApplyRecordsLoading, data.account.friendApplyRecordsHasMore,
            onLoadMore = admission.guard(data.account::loadMoreFriendApplies),
            onAccept = { token -> actions.run({}) { data.account.acceptFriendApply(token) } },
            onReject = { token -> actions.run({}) { data.account.rejectFriendApply(token) } }, onBack = actions.back,
        )
        IosPage.USER_PROFILE -> UserProfileScreen(
            user = data.account.profileUser?.takeIf { it.uid == route.id }, myUid = data.userSession.uid,
            isFriend = data.account.isFriend, remark = data.account.profileRemark,
            organization = data.account.profileOrganization?.takeIf { it.uid == route.id },
            onSaveRemark = { remark -> actions.run({ Outcome.Failure(AppError.AuthExpired) }) { data.account.setFriendRemark(route.id, remark) } },
            hasPendingApply = data.account.hasOutgoingFriendApply(route.id),
            hasIncomingApply = data.account.hasIncomingFriendApply(route.id), isApplyingFriend = data.account.isApplyingFriend(route.id),
            onAddFriend = admission.guard { data.account.applyFriend(route.id) },
            onViewFriendApplies = admission.guard { ui.navigation.open(IosRoute(IosPage.FRIEND_APPLIES)) },
            onSendMessage = { actions.launch { data.discovery.startPersonalChat(route.id)?.let { actions.chat(it) } } },
            onCreateGroup = if (data.account.isFriend) admission.guard { ui.navigation.open(IosRoute(IosPage.CREATE_GROUP, route.id)) } else null,
            onBlockUser = if (route.id != data.userSession.uid) admission.guard { data.account.blockContact(route.id, actions.back) } else null,
            onDeleteFriend = admission.guard { data.contactViewModel.deleteFriend(route.id); ui.navigation.back() },
            onBack = actions.back,
        )
        IosPage.EDIT_PROFILE -> IosEditProfileScreen(actions)
        IosPage.CHANGE_PASSWORD -> ChangePasswordScreen(
            onChangePassword = { old, new -> actions.run({ false }) { data.account.changePassword(old, new) } }, onBack = actions.back,
        )
        IosPage.DEVICES -> DeviceManagementScreen(
            devices = data.account.devices.map { DeviceInfo(it.deviceId, it.deviceName.orEmpty(), it.deviceModel.orEmpty(), it.lastLogin) },
            currentDeviceId = data.account.currentDeviceId, onKick = admission.guard(data.account::kickDevice), onBack = actions.back,
        )
        IosPage.BLACKLIST -> BlacklistScreen(
            blockedUsers = data.account.blockedContacts.map { BlockedUser(it.friendUid, it.user?.name ?: it.friendUid) },
            onUnblock = admission.guard(data.account::unblockContact), onBack = actions.back,
        )
        IosPage.GROUP_DETAIL -> IosGroupDetailScreen(route.id, actions)
        IosPage.GROUP_FILES -> IosGroupFilesScreen(route.id, actions)
        IosPage.GROUP_BOTS -> IosGroupBotsScreen(route.id, actions)
        IosPage.INVITE_MEMBERS -> {
            val contacts by data.contactViewModel.contacts.collectAsState()
            InviteMembersScreen(
                friendUids = contacts.map { it.friendUid }, friendNames = contacts.associate { it.friendUid to (it.remark ?: it.user?.name ?: it.friendUid) },
                memberUids = data.groups.members.takeIf { data.groups.detailTargetChatId == route.id }?.mapTo(mutableSetOf()) { it.uid }.orEmpty(),
                onInvite = { uids -> actions.run({ false }) { data.groups.inviteMembers(route.id, uids) } }, onBack = actions.back,
            )
        }
        IosPage.INVITE_LINKS -> InviteLinksScreen(
            links = data.groups.inviteLinks.takeIf { data.groups.inviteLinksTargetChatId == route.id }.orEmpty(),
            serverBaseUrl = data.deploymentIdentity.httpBaseUrl,
            onCreateLink = { actions.run({ null }) { data.groups.createInviteLink(route.id) } },
            onRevokeLink = admission.guard { token: String -> data.groups.revokeInviteLink(route.id, token) }, onBack = actions.back,
        )
        IosPage.JOIN_BY_INVITE -> JoinByInviteScreen(
            onPreview = { input -> actions.run({ throw CancellationException("会话已关闭") }) { data.discovery.previewInvite(input) } },
            onJoin = { input -> actions.run({}) { actions.chat(data.discovery.joinByInvite(input), replaceCurrent = true); Unit } },
            initialInput = route.id, onBack = actions.back,
        )
        IosPage.FORWARD -> {
            val conversations by data.conversationViewModel.conversations.collectAsState()
            val peers by data.conversationViewModel.peerUsers.collectAsState()
            val contacts by data.contactViewModel.contacts.collectAsState()
            ForwardScreen(conversations = conversations, peerUsers = peers, peerRemarks = remember(contacts) { contactRemarks(contacts) },
                onForward = { target -> actions.run({ false }) { data.discovery.forwardMessage(route.id, route.sequence, target) } }, onBack = actions.back)
        }
        IosPage.HOME, IosPage.CHAT, IosPage.LOCAL_STORAGE -> error("Route is owned by IosMainContent")
    }
}

@Composable
private fun IosSearchScreen(actions: IosFeatureActions) {
    val ui = actions.ui
    val data = ui.data
    val admission = actions.admission
    val conversations by data.conversationViewModel.conversations.collectAsState()
    val peers by data.conversationViewModel.peerUsers.collectAsState()
    val members by data.conversationViewModel.groupAvatarMembers.collectAsState()
    val users by data.globalSearchUserViewModel.users.collectAsState()
    val contacts by data.contactViewModel.contacts.collectAsState()
    val changes by data.discovery.contentSearchChanges.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    GlobalSearchScreen(query = query, onQueryChange = { query = it }, conversations = conversations, contacts = contacts,
        conversationPeerUsers = peers, groupMemberUsers = members, canonicalSearchUsers = users, contentSearchChanges = changes,
        onDisplayedSearchUserUidsChange = data.globalSearchUserViewModel::bindDisplayedUserUids,
        searchMessages = { text -> actions.run({ emptyList() }) { data.discovery.searchMessages(text) } },
        searchUsers = { text -> actions.run({ emptyList() }) { data.discovery.searchUsers(text) } },
        searchContent = { request -> actions.run({ throw CancellationException("搜索已关闭") }) { data.discovery.searchContent(request) } },
        onContentClick = { hit -> actions.run({}) {
            when (val resolved = data.discovery.resolveContent(hit)) {
                is ResolvedContentSearchHit.Document -> admission.runIfOpen {
                    val document = resolved.document
                    ui.navigation.document(OfficeRefBody(OfficeRefBody.REF_TYPE_DOCUMENT, document.spaceId, document.documentId, document.title))
                }
                is ResolvedContentSearchHit.GroupFile -> admission.runIfOpen { ui.files.openOrDownload(requireNotNull(resolved.entry.attachment)) }
                is ResolvedContentSearchHit.ChatMessage -> actions.chat(resolved.message.chatId, resolved.message.serverSeq)
            }
            Unit
        } },
        onConversationClick = admission.guard { conversation: Conversation -> actions.chat(conversation.chatId) },
        onMessageClick = admission.guard { message: Message -> actions.chat(message.chatId, message.serverSeq) },
        onUserClick = admission.guard { user: User -> ui.navigation.profile(user.uid) }, excludedUserUid = data.userSession.uid, onBack = actions.back)
}

@Composable
private fun IosGroupBotsScreen(chatId: String, actions: IosFeatureActions) {
    val data = actions.ui.data
    val groups = data.groups
    val admission = actions.admission
    val ready = groups.groupBotsTargetChatId == chatId
    val presentation = groups.groupBotCredentialPresentation(chatId)
    val refresh = { actions.launch { data.loadScreenDataByKey(ScreenDataKey.GroupBots(chatId)) } }
    GroupBotsScreen(chatId = chatId, serverUrl = data.deploymentIdentity.httpBaseUrl,
        bots = groups.groupBots.takeIf { ready }.orEmpty(), loading = !ready || groups.groupBotsLoading,
        error = groups.groupBotsError.takeIf { ready },
        canCreate = ready && groups.groupBotsError == null && !groups.hasUnacknowledgedGroupBotCredential,
        creating = groups.creatingGroupBot, operationBotId = groups.groupBotOperationId,
        credentials = presentation.credentials?.credentials, credentialsChatId = presentation.credentials?.chatId,
        pendingRecovery = presentation.pendingRecovery, credentialCommandBlocked = groups.hasUnacknowledgedGroupBotCredential,
        onRefresh = refresh, onCreate = admission.guard { name: String -> groups.createGroupBot(chatId, name) },
        onRotate = admission.guard { bot: String -> groups.rotateGroupBotToken(chatId, bot) },
        onRemove = admission.guard { bot: String -> groups.removeGroupBot(chatId, bot) },
        onDismissCredentials = admission.guard { presentation.credentials?.chatId?.let(groups::dismissGroupBotCredentials) },
        onRetryPendingCredential = refresh, onAbandonPendingCredential = admission.guard { groups.abandonPendingGroupBotCredentialRecovery() },
        onBack = actions.back)
}

@Composable
private fun IosGroupFilesScreen(chatId: String, actions: IosFeatureActions) {
    val ui = actions.ui
    val files = ui.data.groupFiles
    val admission = actions.admission
    var uploading by remember { mutableStateOf(false) }
    fun pick(version: GroupFileEntry?) {
        if (uploading || files.chatId != chatId) return
        val target = files.captureUploadTarget(chatId, version) ?: return
        ui.native.select(EmbeddedAssetPresentation.FILE) { selection ->
            actions.selected(selection) {
                uploading = true
                try {
                    val attachment = ui.media.repository.uploadWithMeta(PlatformFile(selection.localReference).asUploadSource(),
                        selection.displayName, selection.contentType).getOrThrow().file
                    currentCoroutineContext().ensureActive()
                    admission.runIfOpen {}.also { check(it) { "页面已关闭" } }
                    files.completeUpload(target, selection.displayName, attachment)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { admission.runIfOpen { files.reportUploadError(failure) } }
                finally { admission.runIfOpen { uploading = false } }
            }
        }
    }
    val ready = files.chatId == chatId
    GroupFilesScreen(entries = files.entries.takeIf { ready }.orEmpty(), path = files.path.takeIf { ready }.orEmpty(),
        selectedFile = files.selectedFile.takeIf { ready }, versions = files.versions.takeIf { ready }.orEmpty(),
        loading = !ready || files.loading, uploading = uploading, stale = ready && files.stale,
        onRefresh = { actions.launch { files.refresh() } }, onEnter = admission.guard(files::enter), onUp = admission.guard(files::up),
        onCreateFolder = admission.guard(files::createFolder), onUpload = admission.guard { pick(null) },
        onUploadVersion = admission.guard { entry: GroupFileEntry -> pick(entry) }, onOpenFile = admission.guard(ui.files::openOrDownload),
        onShowVersions = admission.guard(files::showVersions), onRename = admission.guard(files::rename), onDelete = admission.guard(files::delete),
        canMove = files.supportsMove, onMove = admission.guard(files::move),
        onListFolders = { parent -> actions.run({ emptyList() }) { files.listFoldersForPicker(parent) } }, onBack = actions.back)
}

@Composable
private fun IosGroupDetailScreen(chatId: String, actions: IosFeatureActions) {
    val ui = actions.ui
    val data = ui.data
    val groups = data.groups
    val admission = actions.admission
    val ready = groups.detailTargetChatId == chatId
    val chat = groups.detailChat?.takeIf { ready && it.chatId == chatId }
    val members = groups.members.takeIf { ready }.orEmpty()
    val role = members.firstOrNull { it.uid == data.userSession.uid }?.role ?: -1
    val avatars by data.chat.chatAvatars.collectAsState(emptyMap())
    var avatarBusy by remember { mutableStateOf(false) }
    fun open(page: IosPage) { ui.navigation.open(IosRoute(page, chatId)) }
    GroupDetailScreen(chat = chat, members = members, isOwner = role == 2, myUid = data.userSession.uid,
        groupAvatar = avatars[chatId], onEditGroupAvatar = if (role >= 1) admission.guard {
            if (!avatarBusy) ui.native.selectImage(false) { selection -> actions.selected(selection) {
                avatarBusy = true
                var prepared: IosPreparedAvatar? = null
                try {
                    prepared = prepareIosAvatar(selection, ui.media.stagingDirectory)
                    val attachment = ui.media.repository.uploadWithMeta(prepared.file.asUploadSource(), prepared.file.name, "image/png").getOrThrow().file
                    currentCoroutineContext().ensureActive()
                    admission.runIfOpen { data.chat.setGroupAvatar(chatId, attachment) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { admission.runIfOpen { showIosError("群头像更新失败，请重试") } }
                finally { prepared?.close(); admission.runIfOpen { avatarBusy = false } }
            } }
        } else null,
        onMemberClick = admission.guard(ui.navigation::profile),
        onInviteMembers = admission.guard { open(IosPage.INVITE_MEMBERS) }, onViewInviteLinks = admission.guard { open(IosPage.INVITE_LINKS) },
        onGroupFiles = admission.guard { open(IosPage.GROUP_FILES) }, onGroupBots = admission.guard { open(IosPage.GROUP_BOTS) },
        onLeaveGroup = admission.guard { groups.exit(chatId, dissolve = role == 2) { admission.runIfOpen { ui.navigation.home() } } },
        onEditNotice = admission.guard { notice: String -> groups.updateNotice(chatId, notice) },
        onSetAdmin = admission.guard { uid: String -> groups.setMemberRole(chatId, uid, 1) },
        onRemoveAdmin = admission.guard { uid: String -> groups.setMemberRole(chatId, uid, 0) },
        onMuteMember = admission.guard { uid: String -> groups.muteMember(chatId, uid) },
        onUnmuteMember = admission.guard { uid: String -> groups.unmuteMember(chatId, uid) },
        onRemoveMember = admission.guard { uid: String -> groups.removeMember(chatId, uid) }, onBack = actions.back)
}

@Composable
private fun IosEditProfileScreen(actions: IosFeatureActions) {
    val ui = actions.ui
    val admission = actions.admission
    var avatar by remember { mutableStateOf<IosPreparedAvatar?>(null) }
    var remove by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    DisposableEffect(actions) { onDispose { avatar?.close(); avatar = null } }
    fun select(camera: Boolean) {
        if (processing || uploading) return
        ui.native.selectImage(camera) { selection -> actions.selected(selection) {
            processing = true
            failure = null
            var prepared: IosPreparedAvatar? = null
            try {
                prepared = prepareIosAvatar(selection, ui.media.stagingDirectory)
                if (admission.runIfOpen { avatar?.close(); avatar = prepared; remove = false }) prepared = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { admission.runIfOpen { failure = "无法处理头像，请选择其他图片" } }
            finally { prepared?.close(); admission.runIfOpen { processing = false } }
        } }
    }
    EditProfileScreen(currentUser = ui.data.account.currentUser,
        avatarEditState = ProfileAvatarEditState(preview = avatar?.preview, hasReplacement = avatar != null,
            removeRequested = remove, processing = processing, uploadProgress = if (uploading) 0f else null, errorMessage = failure),
        onChooseAvatar = admission.guard { select(false) }, onCaptureAvatar = admission.guard { select(true) },
        onRemoveAvatar = admission.guard { if (!processing && !uploading) { avatar?.close(); avatar = null; remove = true; failure = null } },
        onSave = { name, phone -> actions.run({ false }) {
            uploading = true
            failure = null
            try {
                val selected = avatar
                val patch = when {
                    remove -> ProfilePatchValue.Set(null)
                    selected != null -> ProfilePatchValue.Set(selected.upload(ui))
                    else -> ProfilePatchValue.Unchanged
                }
                currentCoroutineContext().ensureActive()
                ui.data.account.saveProfile(name, phone, patch)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { admission.runIfOpen { failure = "保存失败，请重试" }; false }
            finally { admission.runIfOpen { uploading = false } }
        } }, onBack = actions.back)
}

/** A save already admitted by the session can outlive the route that displayed its preview. */
private class IosPreparedAvatar(val file: PlatformFile, val preview: ImageBitmap) : AutoCloseable {
    private var uploaded: Attachment? = null
    private var saving = false
    private var closed = false

    suspend fun upload(ui: IosSessionUi): Attachment {
        check(!closed && !saving)
        saving = true
        return try {
            uploaded ?: ui.media.repository.uploadWithMeta(file.asUploadSource(), file.name, "image/png")
                .getOrThrow().file.also { uploaded = it }
        } finally { saving = false; if (closed) file.delete() }
    }

    override fun close() { closed = true; if (!saving) file.delete() }
}

/** ImageIO applies EXIF orientation while decoding a bounded thumbnail, before square rendering. */
private suspend fun prepareIosAvatar(selection: EmbeddedAssetLocalSelection, directory: PlatformFile): IosPreparedAvatar {
    val output = directory.resolve("avatar-${platformRandomUuid()}.png")
    try {
        return withContext(Dispatchers.IO) {
            val sourceFile = PlatformFile(selection.localReference)
            require(sourceFile.length() in 1..PROFILE_AVATAR_MAX_SOURCE_BYTES) { "头像图片过大" }
            val sourceUrl = CFBridgingRetain(NSURL.fileURLWithPath(sourceFile.path))
            val source = try { CGImageSourceCreateWithURL(sourceUrl?.reinterpret(), null) } finally { CFRelease(sourceUrl) }
                ?: error("无法读取头像图片")
            try {
                val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null) ?: error("无法读取图片尺寸")
                try {
                    memScoped {
                        fun dimension(key: CFStringRef?): Long {
                            val number = CFDictionaryGetValue(properties, key) ?: error("无法读取图片尺寸")
                            require(CFGetTypeID(number) == CFNumberGetTypeID())
                            val value = alloc<LongVar>()
                            check(CFNumberGetValue(number.reinterpret(), kCFNumberSInt64Type, value.ptr))
                            return value.value
                        }
                        val width = dimension(kCGImagePropertyPixelWidth)
                        val height = dimension(kCGImagePropertyPixelHeight)
                        require(width > 0 && height > 0 && width <= PROFILE_AVATAR_MAX_SOURCE_PIXELS / height) { "头像图片像素过大" }
                    }
                } finally { CFRelease(properties) }
                val thumbnail = memScoped {
                    val maximum = alloc<IntVar> { value = PROFILE_AVATAR_OUTPUT_SIZE * 2 }
                    val size = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, maximum.ptr)
                    val options = checkNotNull(CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null))
                    try {
                        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
                        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
                        CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, size)
                        CFDictionarySetValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
                        CGImageSourceCreateThumbnailAtIndex(source, 0u, options)
                    } finally { CFRelease(options); CFRelease(size) }
                } ?: error("无法解码头像图片")
                try {
                    val width = CGImageGetWidth(thumbnail).toDouble()
                    val height = CGImageGetHeight(thumbnail).toDouble()
                    val side = minOf(width, height, PROFILE_AVATAR_OUTPUT_SIZE.toDouble())
                    require(side > 0)
                    val scale = side / minOf(width, height)
                    val drawWidth = width * scale
                    val drawHeight = height * scale
                    UIGraphicsBeginImageContextWithOptions(CGSizeMake(side, side), false, 1.0)
                    try {
                        UIImage.imageWithCGImage(thumbnail).drawInRect(CGRectMake((side - drawWidth) / 2, (side - drawHeight) / 2, drawWidth, drawHeight))
                        val png = UIImagePNGRepresentation(checkNotNull(UIGraphicsGetImageFromCurrentImageContext())) ?: error("无法编码头像")
                        require(png.length.toLong() in 1..UserAvatarPolicy.MAX_BYTES) { "头像图片过大" }
                        check(png.writeToFile(output.path, atomically = true)) { "无法保存头像" }
                        check(NSFileManager.defaultManager.setAttributes(mapOf(
                            NSFilePosixPermissions to 384,
                            NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication,
                        ), output.path, null)) { "无法保护头像文件" }
                        currentCoroutineContext().ensureActive()
                        IosPreparedAvatar(output, Image.makeFromEncoded(output.readBytes()).toComposeImageBitmap())
                    } finally { UIGraphicsEndImageContext() }
                } finally { CGImageRelease(thumbnail) }
            } finally { CFRelease(source) }
        }
    } catch (failure: Throwable) { output.delete(); throw failure }
}
