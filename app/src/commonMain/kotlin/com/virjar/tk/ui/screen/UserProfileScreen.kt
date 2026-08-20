package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.User
import com.virjar.tk.model.UserRole
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.ui.component.ScreenHeader

enum class UserProfilePresentation {
    FullPage,
    CompactDialog,
}

internal enum class UserProfileDestructiveAction {
    DeleteFriend,
    BlockUser,
}

internal data class UserProfileActionUiState(
    val pendingConfirmation: UserProfileDestructiveAction? = null,
) {
    fun request(
        action: UserProfileDestructiveAction,
        availableActions: Collection<UserProfileDestructiveAction>,
    ): UserProfileActionUiState = if (action in availableActions) {
        copy(pendingConfirmation = action)
    } else {
        this
    }

    fun dismissConfirmation(): UserProfileActionUiState = copy(pendingConfirmation = null)
}

/**
 * 资料页危险动作的纯展示策略。调用方通过是否提供回调决定能否拉黑当前用户，因而可以在
 * 自己的资料页传 null，避免 UI 层猜测当前登录身份。
 */
internal fun availableUserProfileDestructiveActions(
    isFriend: Boolean,
    hasDeleteFriendAction: Boolean,
    hasBlockUserAction: Boolean,
): List<UserProfileDestructiveAction> = buildList {
    if (isFriend && hasDeleteFriendAction) add(UserProfileDestructiveAction.DeleteFriend)
    if (hasBlockUserAction) add(UserProfileDestructiveAction.BlockUser)
}

internal fun canAddFriendFromProfile(user: User, myUid: String): Boolean =
    user.uid != myUid && user.role == UserRole.HUMAN

@Composable
fun UserProfileScreen(
    user: User?,
    myUid: String,
    isFriend: Boolean,
    hasPendingApply: Boolean,
    hasIncomingApply: Boolean = false,
    isApplyingFriend: Boolean = false,
    onAddFriend: () -> Unit,
    onViewFriendApplies: (() -> Unit)? = null,
    onSendMessage: () -> Unit,
    onCreateGroup: (() -> Unit)? = null,
    onDeleteFriend: (() -> Unit)? = null,
    onBlockUser: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "用户资料", onBack = onBack)
        UserProfileContent(
            user = user,
            myUid = myUid,
            isFriend = isFriend,
            hasPendingApply = hasPendingApply,
            hasIncomingApply = hasIncomingApply,
            isApplyingFriend = isApplyingFriend,
            onAddFriend = onAddFriend,
            onViewFriendApplies = onViewFriendApplies,
            onSendMessage = onSendMessage,
            onCreateGroup = onCreateGroup,
            onDeleteFriend = onDeleteFriend,
            onBlockUser = onBlockUser,
            presentation = UserProfilePresentation.FullPage,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/**
 * 用户资料的内容事实源。Android 用全屏版；Desktop 用紧凑弹窗版，避免为相同业务动作
 * 复制两套状态和 testTag。
 */
@Composable
fun UserProfileContent(
    user: User?,
    myUid: String,
    isFriend: Boolean,
    hasPendingApply: Boolean,
    hasIncomingApply: Boolean = false,
    isApplyingFriend: Boolean = false,
    onAddFriend: () -> Unit,
    onViewFriendApplies: (() -> Unit)? = null,
    onSendMessage: () -> Unit,
    onCreateGroup: (() -> Unit)? = null,
    onDeleteFriend: (() -> Unit)? = null,
    onBlockUser: (() -> Unit)? = null,
    presentation: UserProfilePresentation = UserProfilePresentation.FullPage,
    modifier: Modifier = Modifier,
) {
    var actionUiState by remember(user?.uid) {
        mutableStateOf(UserProfileActionUiState())
    }
    val destructiveActions = availableUserProfileDestructiveActions(
        isFriend = isFriend,
        hasDeleteFriendAction = onDeleteFriend != null,
        hasBlockUserAction = onBlockUser != null,
    )

    val confirmation = actionUiState.pendingConfirmation
    if (confirmation != null) {
        AlertDialog(
            onDismissRequest = { actionUiState = actionUiState.dismissConfirmation() },
            title = {
                Text(
                    when (confirmation) {
                        UserProfileDestructiveAction.DeleteFriend -> "删除好友"
                        UserProfileDestructiveAction.BlockUser -> "加入黑名单"
                    },
                )
            },
            text = {
                Text(
                    when (confirmation) {
                        UserProfileDestructiveAction.DeleteFriend -> "确定要删除该好友吗？"
                        UserProfileDestructiveAction.BlockUser ->
                            "确定要将该用户加入黑名单吗？加入后可在设置的黑名单中移除。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actionUiState = actionUiState.dismissConfirmation()
                        when (confirmation) {
                            UserProfileDestructiveAction.DeleteFriend -> onDeleteFriend?.invoke()
                            UserProfileDestructiveAction.BlockUser -> onBlockUser?.invoke()
                        }
                    },
                    modifier = Modifier.testTag(
                        when (confirmation) {
                            UserProfileDestructiveAction.DeleteFriend -> "profile.deleteFriend.confirm"
                            UserProfileDestructiveAction.BlockUser -> "profile.blockUser.confirm"
                        },
                    ),
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(
                    onClick = { actionUiState = actionUiState.dismissConfirmation() },
                ) { Text("取消") }
            },
        )
    }

    if (user != null) {
        Column(modifier = modifier) {
            when (presentation) {
                UserProfilePresentation.FullPage -> FullPageProfileHero(user)
                UserProfilePresentation.CompactDialog -> CompactProfileHero(user)
            }

            HorizontalDivider()

            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = if (presentation == UserProfilePresentation.CompactDialog) 0.dp else 16.dp,
                    vertical = 16.dp,
                ),
            ) {
                when {
                    isFriend -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = onSendMessage,
                                modifier = Modifier.weight(1f).testTag("profile.sendMessage"),
                            ) { Text("发消息") }
                            if (onCreateGroup != null) {
                                OutlinedButton(
                                    onClick = onCreateGroup,
                                    modifier = Modifier.weight(1f).testTag("profile.createGroup"),
                                ) { Text("发起群聊") }
                            }
                        }
                        if (UserProfileDestructiveAction.DeleteFriend in destructiveActions) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    actionUiState = actionUiState.request(
                                        UserProfileDestructiveAction.DeleteFriend,
                                        destructiveActions,
                                    )
                                },
                                modifier = if (presentation == UserProfilePresentation.CompactDialog) {
                                    Modifier.align(Alignment.CenterHorizontally).testTag("profile.deleteFriend")
                                } else {
                                    Modifier.fillMaxWidth().testTag("profile.deleteFriend")
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("删除好友") }
                        }
                    }
                    hasPendingApply -> {
                        OutlinedButton(
                            onClick = {},
                            modifier = Modifier.fillMaxWidth().testTag("profile.applied"),
                            enabled = false,
                        ) { Text("已申请，等待对方验证") }
                    }
                    hasIncomingApply -> {
                        OutlinedButton(
                            onClick = { onViewFriendApplies?.invoke() },
                            modifier = Modifier.fillMaxWidth().testTag("profile.incomingApply"),
                            enabled = onViewFriendApplies != null,
                        ) { Text("对方已申请，去处理") }
                    }
                    canAddFriendFromProfile(user, myUid) -> {
                        Button(
                            onClick = onAddFriend,
                            modifier = Modifier.fillMaxWidth().testTag("profile.addFriend"),
                            enabled = !isApplyingFriend,
                        ) { Text(if (isApplyingFriend) "申请中…" else "添加好友") }
                    }
                    else -> Unit
                }

                if (UserProfileDestructiveAction.BlockUser in destructiveActions) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            actionUiState = actionUiState.request(
                                UserProfileDestructiveAction.BlockUser,
                                destructiveActions,
                            )
                        },
                        modifier = if (presentation == UserProfilePresentation.CompactDialog) {
                            Modifier.align(Alignment.CenterHorizontally).testTag("profile.blockUser")
                        } else {
                            Modifier.fillMaxWidth().testTag("profile.blockUser")
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("加入黑名单") }
                }
            }
        }
    } else {
        Box(
            modifier = modifier.then(
                if (presentation == UserProfilePresentation.CompactDialog) Modifier.height(220.dp)
                else Modifier.fillMaxSize()
            ),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun FullPageProfileHero(user: User) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AvatarPlaceholder(name = user.name, size = 80)
        Spacer(Modifier.height(16.dp))
        Text(user.name, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "@${user.username}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "UID: ${user.uid.take(16)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompactProfileHero(user: User) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarPlaceholder(name = user.name, size = 64)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                user.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "@${user.username}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "UID: ${user.uid.take(16)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
