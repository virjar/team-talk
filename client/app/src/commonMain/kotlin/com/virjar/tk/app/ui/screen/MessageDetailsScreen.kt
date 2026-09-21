package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.ui.bridge.ChatMediaConfig
import com.virjar.tk.app.ui.component.*
import com.virjar.tk.app.ui.platform.TkBackHandler
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User

/** 平台决定桌面弹窗或移动端页面；正文复用原消息及其已认证资源作用域。 */
@Composable
fun MessageDetailsScreen(
    message: Message?,
    media: ChatMediaConfig,
    voicePlayback: VoicePlaybackController,
    actionAdmission: UiActionAdmission,
    resolveSender: ((String) -> User?)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    header: @Composable () -> Unit = { ScreenHeader("消息全文", onBack = onBack) },
) {
    val admitted = rememberAdmittedChatMedia(media, actionAdmission)
    val voice = rememberAdmittedVoicePlayback(voicePlayback, actionAdmission)
    TkBackHandler(onBack = onBack)
    Surface(modifier.fillMaxSize().testTag("chat.message.details")) {
        Column {
            header()
            CompositionLocalProvider(LocalFileDownloads provides admitted.fileDownloads) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(20.dp).testTag("chat.message.details.content")) {
                        if (loading) CircularProgressIndicator()
                        else if (message == null) Text("这条消息暂不可用")
                        else if (message.flags and Message.FLAG_REVOKED != 0) Text("消息已撤回")
                        else MessageBodyRenderer(message = message, imageContent = admitted.imageContent,
                            voicePlayback = voice, onMediaClick = admitted.onMediaClick,
                            onEmbeddedMediaClick = admitted.onEmbeddedMediaClick,
                            onMentionClick = admitted.onMentionClick?.let { callback ->
                                { uid -> if (uid != com.virjar.tk.protocol.model.MentionPolicy.ALL) callback(uid) }
                            }, onUrlClick = admitted.onUrlClick,
                            resolveSender = resolveSender)
                    }
                }
            }
        }
    }
}
