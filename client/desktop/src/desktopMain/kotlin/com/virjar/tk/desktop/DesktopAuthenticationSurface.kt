package com.virjar.tk.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal enum class DesktopAuthenticationSurface {
    LOGIN,
    AUTHENTICATED,
    PROTOCOL_UPGRADE,
}

/** 协议升级永远优先；已退役的会话绝不能继续占用业务外壳。 */
internal fun desktopAuthenticationSurface(
    hasLocalSession: Boolean,
    hasActiveSession: Boolean,
    requiresProtocolUpgrade: Boolean,
): DesktopAuthenticationSurface = when {
    requiresProtocolUpgrade -> DesktopAuthenticationSurface.PROTOCOL_UPGRADE
    hasLocalSession && hasActiveSession -> DesktopAuthenticationSurface.AUTHENTICATED
    else -> DesktopAuthenticationSurface.LOGIN
}

internal enum class DesktopProtocolUpgradeAction { EXIT_APPLICATION }

internal data class DesktopProtocolUpgradeSurfacePolicy(
    val title: String,
    val message: String,
    val actionLabel: String,
    val action: DesktopProtocolUpgradeAction,
)

internal val forceDesktopProtocolUpgradeSurfacePolicy = DesktopProtocolUpgradeSurfacePolicy(
    title = "客户端需要更新",
    message = "当前版本与服务器不兼容。请更新到最新版本后再继续使用 TeamTalk。",
    actionLabel = "退出应用",
    action = DesktopProtocolUpgradeAction.EXIT_APPLICATION,
)

/** 全窗口终结界面：有意不提供任何关闭或认证回调。 */
@Composable
internal fun DesktopProtocolUpgradeSurface(
    onExit: () -> Unit,
    policy: DesktopProtocolUpgradeSurfacePolicy = forceDesktopProtocolUpgradeSurfacePolicy,
    message: String = policy.message,
) {
    Surface(modifier = Modifier.fillMaxSize().testTag("auth.upgrade.dialog")) {
        Column(
            modifier = Modifier.fillMaxSize().padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(policy.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            when (policy.action) {
                DesktopProtocolUpgradeAction.EXIT_APPLICATION -> Button(
                    onClick = onExit,
                    modifier = Modifier.testTag("auth.upgrade.exit"),
                ) {
                    Text(policy.actionLabel)
                }
            }
        }
    }
}
