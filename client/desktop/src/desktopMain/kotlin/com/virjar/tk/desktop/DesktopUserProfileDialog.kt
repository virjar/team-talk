package com.virjar.tk.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.virjar.tk.app.navigation.ScreenDataKey
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.ui.screen.UserProfileContent
import com.virjar.tk.app.ui.screen.UserProfilePresentation
import kotlinx.coroutines.launch

/**
 * Desktop 用户资料是归属于主窗口的对象预览模态层，不进入页面返回栈。
 *
 * 使用 common Dialog 的无装饰、owner-modal scene layer：它不会在 macOS 上创建
 * 第二套标题栏/红黄绿按钮，同时提供真正的焦点隔离、ESC 和背景输入阻断。
 * 直接使用带原生装饰的 Desktop DialogWindow 会让内容标题/关闭按钮重复。
 */
@Composable
internal fun DesktopUserProfileDialog(
    uid: String,
    nav: DesktopNav,
    ownerWindow: java.awt.Window,
    presentationGate: DesktopSessionPresentationGate,
    onDismiss: () -> Unit,
) {
    if (!presentationGate.isOpen || !nav.acceptsRendering) return

    val scope = rememberCoroutineScope()
    val dismissInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(uid) {
        nav.runAdmittedUiAction(presentationGate, onClosed = {}) {
            nav.loadScreenDataByKey(ScreenDataKey.UserProfile(uid))
        }
    }

    Dialog(onDismissRequest = presentationGate.guard(onDismiss)) {
        if (!presentationGate.isOpen || !nav.acceptsRendering) return@Dialog
        DesktopOwnedModalTelemetry(
            ownerWindow = ownerWindow,
            page = ClientUiPage.USER_PROFILE,
            telemetry = nav.telemetry,
            disposalExitReason = {
                desktopWindowDisposalExitReason(presentationGate.isOpen)
            },
        )

        Box(
            modifier = Modifier.fillMaxSize().testTag("profile.overlay"),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = dismissInteraction,
                        indication = null,
                        onClick = presentationGate.guard(onDismiss),
                    )
                    .focusProperties { canFocus = false }
                    .testTag("profile.dismissArea"),
            )

            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .width(440.dp)
                        .heightIn(max = 620.dp)
                        // 参与命中测试，但不添加虚假的点击动作或焦点目标。子组件先消费自己的手势；
                        // 剩余的空白卡片区域在这里被消费，无法到达下方的关闭层。
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Final).changes.forEach { change ->
                                        if (!change.isConsumed) change.consume()
                                    }
                                }
                            }
                        }
                        .testTag("profile.dialog"),
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
                                onClick = presentationGate.guard(onDismiss),
                                modifier = Modifier.size(36.dp).testTag("profile.close"),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "关闭",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        Box(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 568.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            UserProfileContent(
                                user = nav.account.profileUser?.takeIf { it.uid == uid },
                                myUid = nav.userSession.uid,
                                isFriend = nav.account.isFriend,
                                hasPendingApply = nav.account.hasOutgoingFriendApply(uid),
                                hasIncomingApply = nav.account.hasIncomingFriendApply(uid),
                                isApplyingFriend = nav.account.isApplyingFriend(uid),
                                onAddFriend = presentationGate.guard { nav.account.applyFriend(uid) },
                                onViewFriendApplies = presentationGate.guard {
                                    onDismiss()
                                    nav.openScreen(SubScreen.FriendApplies)
                                },
                                onSendMessage = {
                                    scope.launch {
                                        val chatId = nav.runAdmittedUiAction(
                                            presentationGate,
                                            onClosed = { null },
                                        ) {
                                            nav.discovery.startPersonalChat(uid)
                                        } ?: return@launch
                                        presentationGate.runIfOpen {
                                            nav.openChat(chatId)
                                        }
                                    }
                                },
                                onCreateGroup = if (nav.account.isFriend) {
                                    presentationGate.guard {
                                        onDismiss()
                                        nav.openScreen(SubScreen.CreateGroup(setOf(uid)))
                                    }
                                } else null,
                                onDeleteFriend = presentationGate.guard {
                                    nav.contactViewModel.deleteFriend(uid)
                                    onDismiss()
                                },
                                onBlockUser = if (uid != nav.userSession.uid) {
                                    presentationGate.guard {
                                        nav.account.blockContact(uid) {
                                            presentationGate.runIfOpen(onDismiss)
                                        }
                                    }
                                } else null,
                                presentation = UserProfilePresentation.CompactDialog,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
