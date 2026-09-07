package com.virjar.tk.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.client.AccountBanState

/** 双端共享的封禁终结界面；清理未完成时不暴露登录或工作区操作。 */
@Composable
fun AccountBanSurface(state: AccountBanState, onReturnToLogin: () -> Unit, onExit: () -> Unit) {
    Surface(Modifier.fillMaxSize().testTag("auth.account-banned")) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("账号已被封禁", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Text(
                when (state) {
                    AccountBanState.CLEANING -> "正在清理此账号在本设备上的资料，请稍候。"
                    AccountBanState.CLEARED -> "此账号在本应用内的资料已清理，无法继续使用。"
                    AccountBanState.CLEANUP_FAILED -> "本地资料清理尚未完成。请退出并重新打开应用继续清理。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            when (state) {
                AccountBanState.CLEANING -> CircularProgressIndicator()
                AccountBanState.CLEARED -> Button(onReturnToLogin, Modifier.testTag("auth.account-banned.login")) {
                    Text("返回登录")
                }
                AccountBanState.CLEANUP_FAILED -> Button(onExit, Modifier.testTag("auth.account-banned.exit")) {
                    Text("退出应用")
                }
            }
        }
    }
}
