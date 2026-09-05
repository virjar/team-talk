package com.virjar.tk.app.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.payload.ProtocolNegotiateResponsePayload
import com.virjar.tk.shared.client.ProtocolCompatibility

/** 可兼容的旧 minor 仍可使用工作区；升级提示在双端所有页面保持可见。 */
@Composable
fun ProtocolUpgradeBanner(compatibility: ProtocolCompatibility?) {
    if (compatibility?.recommendsUpgrade != true) return
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("status.upgrade")
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            "当前客户端版本较旧，建议更新客户端以使用最新功能。",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

fun forcedProtocolUpgradeMessage(compatibility: ProtocolCompatibility?): String = when (compatibility?.code) {
    ProtocolNegotiateResponsePayload.CODE_CLIENT_TOO_OLD ->
        "当前客户端版本低于服务器要求的最低版本。请更新客户端后再继续使用 TeamTalk。"
    ProtocolNegotiateResponsePayload.CODE_SERVER_TOO_OLD ->
        SERVER_UPGRADE_MESSAGE
    ProtocolNegotiateResponsePayload.CODE_MAJOR_UNSUPPORTED ->
        if (compatibility.server.major < compatibility.client.major) SERVER_UPGRADE_MESSAGE
        else "当前客户端与服务器版本不兼容。请安装兼容的客户端后再继续使用 TeamTalk。"
    else -> "当前客户端与服务器版本不兼容。请安装兼容的客户端后再继续使用 TeamTalk。"
}

private const val SERVER_UPGRADE_MESSAGE =
    "服务器版本过低。请联系管理员升级服务器后重新启动客户端，或安装与服务器兼容的客户端。"
