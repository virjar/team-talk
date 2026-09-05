package com.virjar.tk.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.ConversationWirePolicy
import com.virjar.tk.shared.client.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val MESSAGE_CHANNEL = "teamtalk.messages"
private const val OPEN_MESSAGE_ACTION = "com.virjar.tk.android.OPEN_MESSAGE"

/** Activity 的通知入口；等待已认证导航就绪，既支持 onCreate，也支持 onNewIntent。 */
internal class AndroidNotificationNavigation {
    private val pending = MutableStateFlow<AndroidNotificationTarget?>(null)
    val target: StateFlow<AndroidNotificationTarget?> = pending.asStateFlow()

    fun accept(intent: Intent?): Boolean {
        if (intent?.action != OPEN_MESSAGE_ACTION) return false
        val uri = intent.data ?: return false
        if (uri.scheme != "teamtalk-local" || uri.authority != "message") return false
        val parts = uri.pathSegments
        if (parts.size != 4 || parts.any { it.isBlank() || it.length > 256 }) return false
        if (parts[3].length > ConversationWirePolicy.MAX_CHAT_ID_LENGTH) return false
        pending.value = AndroidNotificationTarget(parts[0], parts[1], parts[2], parts[3])
        return true
    }

    fun consume(expected: AndroidNotificationTarget) {
        pending.compareAndSet(expected, null)
    }

    fun clearFor(deploymentFingerprint: String, datasetId: String, uid: String) {
        val current = pending.value ?: return
        if (current.belongsTo(deploymentFingerprint, datasetId, uid)) consume(current)
    }
}

internal data class AndroidNotificationTarget(
    val deploymentFingerprint: String,
    val datasetId: String,
    val uid: String,
    val chatId: String,
) {
    fun belongsTo(deploymentFingerprint: String, datasetId: String, uid: String): Boolean =
        this.deploymentFingerprint == deploymentFingerprint && this.datasetId == datasetId && this.uid == uid

    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
        action = OPEN_MESSAGE_ACTION
        // data 参与 PendingIntent 身份比较；不同会话不会互相覆盖点击目的地。
        data = Uri.Builder().scheme("teamtalk-local").authority("message")
            .appendPath(deploymentFingerprint).appendPath(datasetId).appendPath(uid).appendPath(chatId)
            .build()
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
}

/** 每次在线阶段从真实缓存快照建基线；只有新增消息造成的未读增量才提醒。 */
internal class AndroidUnreadNotificationTracker {
    private var previous: Map<String, Conversation>? = null

    fun update(conversations: List<Conversation>, foreground: Boolean): List<Conversation> {
        val baseline = previous
        previous = conversations.associateBy(Conversation::chatId)
        if (baseline == null || foreground) return emptyList()
        return conversations.filter { current ->
            val old = baseline[current.chatId]
            !current.isMuted && current.unreadCount > (old?.unreadCount ?: 0) &&
                current.lastSeq > (old?.lastSeq ?: 0L)
        }
    }
}

/**
 * 已登录会话拥有的系统通知收集器。它独立于 Compose 重组，在 Activity 后台但进程仍存活时工作。
 * 不申请后台运行能力；会话退役同步关闭发布入口、取消收集并移除这个账号的通知。
 */
internal class AndroidMessageNotifications(
    private val context: Context,
    private val deploymentFingerprint: String,
    private val datasetId: String,
    private val uid: String,
    conversations: Flow<List<Conversation>>,
    connectionState: StateFlow<ConnectionState>,
    foreground: StateFlow<Boolean>,
    private val navigation: AndroidNotificationNavigation,
) : AutoCloseable {
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private var closed = false
    private val posted = mutableSetOf<String>()

    init {
        manager.createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL, "聊天消息", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "应用在后台运行时的新消息提醒"
            },
        )
        scope.launch {
            connectionState.collectLatest { state ->
                if (state != ConnectionState.AUTHENTICATED) return@collectLatest
                val tracker = AndroidUnreadNotificationTracker()
                // AUTHENTICATED 在同步完成后发布。每次重新收集的首个缓存快照只作基线，
                // 因此冷启动和离线重放不会被误认为这一在线阶段的新消息。
                combine(conversations, foreground) { items, active -> items to active }
                    .collect { (items, active) ->
                        val additions = tracker.update(items, active)
                        synchronized(lock) {
                            if (closed) return@synchronized
                            // onStart 可能先于已排队的后台投影回调执行，发布时再读当前前后台事实。
                            val currentlyForeground = foreground.value
                            val eligible = items.filter { !it.isMuted && it.unreadCount > 0 }
                                .mapTo(mutableSetOf(), Conversation::chatId)
                            posted.toList().filter { currentlyForeground || it !in eligible }.forEach(::cancel)
                            if (!currentlyForeground && connectionState.value == ConnectionState.AUTHENTICATED &&
                                manager.areNotificationsEnabled()
                            ) additions.forEach(::show)
                        }
                    }
            }
        }
    }

    private fun tag(chatId: String) = "$deploymentFingerprint:$datasetId:$uid:$chatId"

    private fun show(conversation: Conversation) {
        val target = AndroidNotificationTarget(deploymentFingerprint, datasetId, uid, conversation.chatId)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, target.intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(conversation.chatName?.takeIf(String::isNotBlank) ?: "TeamTalk")
            .setContentText("${conversation.unreadCount} 条未读消息")
            .setNumber(conversation.unreadCount)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            manager.notify(tag(conversation.chatId), 0, notification)
            posted += conversation.chatId
        } catch (_: SecurityException) {
            // 用户可能刚刚在系统设置撤销权限；消息和未读仍由 LocalCache 正常呈现。
        }
    }

    private fun cancel(chatId: String) {
        manager.cancel(tag(chatId), 0)
        posted -= chatId
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        scope.cancel()
        posted.toList().forEach(::cancel)
        navigation.clearFor(deploymentFingerprint, datasetId, uid)
    }
}

/** 回到应用时移除已有消息通知，也清理上个被系统回收的进程留下的消息通知。 */
internal fun clearAndroidMessageNotifications(context: Context) {
    val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
    manager.activeNotifications.filter { it.notification.channelId == MESSAGE_CHANNEL }
        .forEach { manager.cancel(it.tag, it.id) }
}

/** 登录后最多自动申请一次；拒绝不会阻挡聊天，也不会在重连或 Activity 重建时反复弹窗。 */
@Composable
internal fun RequestAndroidMessageNotificationPermission() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return@LaunchedEffect
        }
        val preferences = context.getSharedPreferences("message_notifications", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("permission_requested", false)) {
            preferences.edit().putBoolean("permission_requested", true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
