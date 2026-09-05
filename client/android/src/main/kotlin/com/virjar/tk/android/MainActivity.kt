package com.virjar.tk.android

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.app.ui.AppTheme
import com.virjar.tk.app.ui.theme.initThemeStore

class MainActivity : ComponentActivity() {
    private val appDataStateHolder: AndroidAppDataStateHolder by lazy {
        ViewModelProvider(this)[AndroidAppDataStateHolder::class.java]
    }
    private val beforeSessionRetirement: (ClientSession, SessionEndReason) -> Unit by lazy {
        { session, reason -> appDataStateHolder.beforeSessionRetirement(session, reason) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptNotificationIntent(intent)
        val processOwner = application as TeamTalkApp
        // 全局初始化（日志注入、ServerConfig、异常拦截）已在 TeamTalkApp.onCreate 完成
        // 主题持久化：需在首次组合（TkTheme 读取）前就绪
        initThemeStore(applicationContext)
        setContent {
            AppTheme(touchDensity = true) {
                TestTagEnabler {
                    AndroidAppRoot(
                        applicationContext = applicationContext,
                        serverConfig = processOwner.serverConfig,
                        appDataStateHolder = appDataStateHolder,
                        beforeSessionRetirement = beforeSessionRetirement,
                        onProtocolUpgradeExit = { finishAffinity() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptNotificationIntent(intent)
    }

    private fun acceptNotificationIntent(intent: Intent?) {
        if (appDataStateHolder.notificationNavigation.accept(intent)) {
            // 请求已由保留的 holder 持有；Activity 重建不应重新播放已经消费的点击。
            setIntent(Intent(checkNotNull(intent)).apply { action = null; data = null })
        }
    }

    override fun onStart() {
        super.onStart()
        appDataStateHolder.setNotificationForeground(true)
        clearAndroidMessageNotifications(this)
    }

    override fun onStop() {
        // 在 Compose 持有的编辑器状态仍处于挂接状态时捕获它。持久化本身是一个进程级串行队列：
        // 入队一个屏障后立即返回，避免主线程等待五秒。Android 可能在 onStop 之后回收进程，
        // 因此这里不能依赖销毁时机。
        try {
            appDataStateHolder.setNotificationForeground(false)
            appDataStateHolder.captureAndScheduleDocumentDraftFlush()
        } finally {
            // 框架生命周期完成流程不能被草稿捕获的缺陷跳过。
            super.onStop()
        }
    }
}
