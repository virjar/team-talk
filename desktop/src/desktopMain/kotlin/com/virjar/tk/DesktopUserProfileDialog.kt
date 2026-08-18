package com.virjar.tk

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.virjar.tk.navigation.ScreenDataKey
import com.virjar.tk.ui.screen.UserProfileContent
import com.virjar.tk.ui.screen.UserProfilePresentation
import kotlinx.coroutines.launch

/**
 * Desktop 的用户资料是对象预览弹窗，不占用聊天内容栏，也不进入页面返回栈。
 * 业务内容复用 commonMain，Desktop 这里只负责弹窗容器与导航结果。
 */
@Composable
internal fun DesktopUserProfileDialog(
    uid: String,
    nav: DesktopNav,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var hasPendingApply by remember(uid) { mutableStateOf(false) }

    LaunchedEffect(uid) {
        hasPendingApply = false
        nav.loadScreenDataByKey(ScreenDataKey.UserProfile(uid))
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(400.dp).testTag("profile.dialog"),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 16.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "用户资料",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp).testTag("profile.close"),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                UserProfileContent(
                    user = nav.account.profileUser?.takeIf { it.uid == uid },
                    isFriend = nav.account.isFriend,
                    hasPendingApply = hasPendingApply,
                    onAddFriend = {
                        nav.contactViewModel.apply(uid)
                        hasPendingApply = true
                    },
                    onSendMessage = {
                        scope.launch {
                            val chatId = nav.discovery.startPersonalChat(uid) ?: return@launch
                            nav.openChat(chatId, nav.account.profileUser?.name ?: uid.take(12), 1)
                        }
                    },
                    onCreateGroup = if (nav.account.isFriend) {
                        {
                            onDismiss()
                            nav.openScreen(SubScreen.CreateGroup(setOf(uid)))
                        }
                    } else null,
                    onDeleteFriend = {
                        nav.contactViewModel.deleteFriend(uid)
                        onDismiss()
                    },
                    presentation = UserProfilePresentation.CompactDialog,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
