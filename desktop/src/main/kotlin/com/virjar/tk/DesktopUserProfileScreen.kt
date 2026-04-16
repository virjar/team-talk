package com.virjar.tk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.ui.screen.UserProfileScreen
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch

@Composable
internal fun DesktopUserProfileScreen(
    uid: String,
    onNavigateToChat: (ChatTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val appState = LocalDesktopState.current
    val userContext = appState.userContext
    val contactsVm = appState.contactsVm
    val scope = rememberCoroutineScope()

    UserProfileScreen(
        uid = uid,
        userRepo = userContext.userRepo,
        contactRepo = userContext.contactRepo,
        myUid = userContext.uid,
        onBack = onDismiss,
        onStartChat = { channelId, channelType, channelName ->
            scope.launch {
                try {
                    val channel = userContext.channelRepo.ensurePersonalChannel(uid)
                    val name = channel.name.ifEmpty { channelName }
                    onNavigateToChat(ChatTarget(channel.channelId, ChannelType.fromCode(channel.channelType), name, otherUid = uid))
                } catch (e: Exception) {
                    AppLog.e("Desktop", "ensurePersonalChannel failed", e)
                    onNavigateToChat(ChatTarget(channelId, ChannelType.fromCode(channelType), channelName))
                }
            }
        },
        onSendApply = { uid -> scope.launch { contactsVm.applyFriend(uid) } },
        onDeleteFriend = { uid ->
            scope.launch {
                try {
                    contactsVm.deleteFriend(uid)
                    onDismiss()
                } catch (e: Exception) {
                    AppLog.e("Desktop", "deleteFriend failed", e)
                }
            }
        },
        onUpdateRemark = { uid, remark ->
            scope.launch {
                try { contactsVm.updateRemark(uid, remark) } catch (e: Exception) {
                    AppLog.e("Desktop", "updateRemark failed", e)
                }
            }
        },
        onAddBlacklist = { uid ->
            scope.launch {
                try { userContext.contactRepo.addBlacklist(uid) } catch (e: Exception) {
                    AppLog.e("Desktop", "addBlacklist failed", e)
                }
            }
        },
        onRemoveBlacklist = { uid ->
            scope.launch {
                try { userContext.contactRepo.removeBlacklist(uid) } catch (e: Exception) {
                    AppLog.e("Desktop", "removeBlacklist failed", e)
                }
            }
        },
    )
}
