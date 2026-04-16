# 通知架构设计

> 架构决策记录 — 消息即时提醒的系统通知方案

---

## 背景
TeamTalk 当前收到新消息后没有任何系统级提醒。Signal 使用 `NotificationChannels`（Android）+ `NSUserNotificationCenter`（macOS），Telegram 的 `NotificationCenter` 管理 200+ 事件类型。TeamTalk 采用渐进策略：先 Desktop（Phase 1），再 Android（Phase 2），最后完善过滤分组（Phase 3）。

## 1. 设计目标

| 目标 | 说明 |
|------|------|
| 消息即时提醒 | 收到新消息立即弹出系统通知 |
| 通知分组 | 同一会话消息聚合显示 |
| 免打扰 | 静音会话不弹通知 |
| 点击跳转 | 点击通知打开对应聊天 |
| 前台不重复 | 正在查看该会话时不弹通知 |

## 2. Desktop 托盘 + 通知方案

### 2.1 为什么不用 JVM SystemTray

JVM 标准 `SystemTray` + `TrayIcon` 存在以下问题：

| 问题 | 说明 |
|------|------|
| 菜单功能极简 | AWT `PopupMenu` 仅支持文本菜单项，无子菜单、分隔符、可选中项、图标、禁用状态 |
| 无左键点击 | `TrayIcon.actionPerformed` 行为不可控，无法区分左键/右键，IM 左键恢复窗口是标准交互 |
| HDPI 图标模糊 | `java.awt.Image` 不支持高分辨率渲染，现代屏幕上图标模糊 |
| Linux 外观过时 | AWT `PopupMenu` 在 Linux 上外观类似 Windows 95 |
| 无暗色模式 | macOS 上菜单栏颜色跟随壁纸而非系统主题，黑白图标无法自动适配 |
| Linux 兼容性 | GNOME + Wayland 下 `SystemTray.isSupported()` 可能返回 false |

### 2.2 方案：ComposeNativeTray

采用 [ComposeNativeTray](https://github.com/kdroidFilter/ComposeNativeTray)（MIT 协议，Maven Central: `io.github.kdroidfilter:composenativetray`）替代 JVM `SystemTray`。

该库为三大桌面平台提供原生托盘实现：

| 平台 | 实现 | 菜单引擎 |
|------|------|----------|
| Windows | Win32 API (JNI) | 原生 HMENU |
| macOS | Cocoa (JNA) | 原生 NSMenu |
| Linux | GTK (JNI) | 原生 GTK Menu |
| 回退 | AWT SystemTray | AWT PopupMenu |

**引入范围**：仅使用 `Tray` API（稳定版）。`TrayApp`（Alpha 弹出窗口）暂不引入。`SingleInstanceManager` 不引入（TeamTalk 已有 `FileLocker`）。

### 2.3 托盘图标

```kotlin
// desktop/src/main/kotlin/.../tray/AppTray.kt

@Composable
fun ApplicationScope.AppTray(
    isConnected: Boolean,
    unreadCount: Int,
    onOpenMainWindow: () -> Unit,
    onNavigateToChat: (channelId: String, channelType: Int) -> Unit,
    onExit: () -> Unit,
) {
    val isDark = isMenuBarInDarkMode()
    val statusIcon = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Cancel
    val statusText = if (isConnected) "在线" else "离线"

    Tray(
        icon = statusIcon,
        // tint=null 时自动适配：暗色模式白色，亮色模式黑色
        tint = null,
        tooltip = if (unreadCount > 0) "TeamTalk - $unreadCount 条未读" else "TeamTalk",
        primaryAction = { onOpenMainWindow() }
    ) {
        Item("打开 TeamTalk", icon = Icons.Default.OpenInNew) {
            onOpenMainWindow()
        }

        Divider()

        CheckableItem(
            label = "通知",
            checked = true,
            onCheckedChange = { /* 后续接入通知开关 */ }
        )

        Divider()

        // 禁用项：显示连接状态，不可点击
        Item(statusText, icon = statusIcon, isEnabled = false)

        Divider()

        Item("退出", icon = Icons.Default.Close) {
            onExit()
        }
    }
}
```

**图标策略**：

| 状态 | 图标 | tooltip |
|------|------|---------|
| 已连接 | `Icons.Default.CheckCircle`（自动黑白） | `TeamTalk` 或 `TeamTalk - N 条未读` |
| 未连接 | `Icons.Default.Cancel`（自动黑白） | `TeamTalk - 连接中...` |

ComposeNativeTray 的 `tint=null` 自动适配机制：macOS 上菜单栏颜色取决于壁纸而非系统主题，库内部通过 `isMenuBarInDarkMode()` 检测并自动切换图标色调（黑/白），无需手动处理。

### 2.4 Desktop 系统通知

ComposeNativeTray 负责**托盘图标和菜单**，**系统通知**仍需独立方案。

| 方案 | 说明 | 可行性 |
|------|------|--------|
| `java.awt.SystemTray.displayMessage()` | 依赖 TrayIcon 实例 | 不可行（已弃用 SystemTray） |
| `ProcessBuilder("notify-send", ...)` | Linux 系统通知守护进程 | 仅 Linux |
| JNA 调用原生通知 API | macOS: NSUserNotification, Windows: Win32 Toast | 三平台覆盖，需额外实现 |
| `java.awt.Toolkit.beep()` | 声音提醒 | 仅声音，无内容 |

**建议**：Phase 1 使用 `ProcessBuilder("notify-send")` 覆盖 Linux；macOS 和 Windows 通过 JNA 调用原生通知 API。通知内容（发送者 + 摘要 + 会话 ID）通过命令行参数或 JNA 传递，点击通知时唤起主窗口并跳转到对应聊天。

```kotlin
// app/src/desktopMain/kotlin/.../notification/DesktopNotificationManager.kt
class DesktopNotificationManager(
    private val onNavigateToChat: (channelId: String, channelType: Int) -> Unit
) {
    private val activeNotifications = mutableMapOf<String, NotificationEntry>()

    fun onNewMessage(
        channelId: String, channelType: Int,
        channelName: String, senderName: String, contentPreview: String
    ) {
        val entry = activeNotifications[channelId]
        if (entry != null) {
            val updated = entry.copy(count = entry.count + 1, latestText = contentPreview)
            activeNotifications[channelId] = updated
            showNotification(channelName, "${updated.count} 条新消息: $contentPreview", channelId, channelType)
        } else {
            activeNotifications[channelId] = NotificationEntry(channelId, channelType, 1, contentPreview)
            showNotification(senderName, contentPreview, channelId, channelType)
        }
    }

    fun clearChannel(channelId: String) {
        activeNotifications.remove(channelId)
    }

    private fun showNotification(title: String, body: String, channelId: String, channelType: Int) {
        when (getOperatingSystem()) {
            OperatingSystem.LINUX -> showLinuxNotification(title, body, channelId, channelType)
            // macOS/Windows 后续通过 JNA 调用原生 API
            else -> showFallbackNotification(title, body)
        }
    }

    private fun showLinuxNotification(title: String, body: String, channelId: String, channelType: Int) {
        // notify-send 支持桌面通知，点击行为通过 desktop file activation 实现
        ProcessBuilder("notify-send", "-a", "TeamTalk", "-i", "teamtalk", title, body).start()
    }

    private fun showFallbackNotification(title: String, body: String) {
        Toolkit.getDefaultToolkit().beep()
    }
}
```

## 3. Android 通知方案（Phase 2）

Android 后台 TCP 连接会被系统回收，需 FCM 唤醒：
```
服务端                              客户端
消息写入 RocksDB                    FCM Token 注册
       ↓                                  ↓
FCM SDK 推送(data msg) ──HTTPS──→  FirebaseMessaging.onMessageReceived
                                          ↓
                                     唤醒 → 拉取消息 → NotificationChannel
```
NotificationChannel 三类：`messages`（个人）、`groups`（群组）、`system`（好友申请等）。通知样式使用 `Person` + `MessagingStyle`（对话式，与系统短信一致）。

## 4. 通知过滤策略

```
消息到达 → shouldNotify()
  ├─ 会话 is_muted=true？
  │    ├─ 被 @提及 → 弹通知（@优先于静音，可配置）
  │    └─ 否 → 静默（仅更新 badge）
  ├─ 正在查看该会话？ → 不弹通知
  ├─ 免打扰时段？ → 静默
  └─ 弹通知
```

前台检测（Desktop）：维护 `currentViewingChannelId`，由 `DesktopChatPanel` 进入/退出聊天时更新。

跨平台接口（expect/actual）：
```kotlin
// app/src/commonMain/.../notification/NotificationManager.kt
expect class NotificationManager() {
    fun onNewMessage(channelId: String, channelName: String, senderName: String,
                     contentPreview: String, isMuted: Boolean, isViewing: Boolean)
    fun clearChannel(channelId: String)
    fun updateConnectionState(connected: Boolean)
}
```

Desktop actual：ComposeNativeTray 负责托盘（图标 + 菜单 + 未读 badge），`DesktopNotificationManager` 负责系统通知（弹窗提醒）。
Android actual：`NotificationManagerCompat` + FCM。

## 5. 通知数据流

```
TCP RECV → ApiClient.onMessage()
       │
       ▼
NotificationManager.onNewMessage(channelId, senderUid, payload, type)
       │
       ├─ 检查静音 → 是 → 静默（@例外）
       ├─ 检查前台 → 是 → 不弹
       ├─ 更新未读 badge → ComposeNativeTray tooltip 更新（Compose 重组）
       └─ 构建系统通知
           ├─ 同会话已有 → 聚合更新
           └─ 新会话 → 新建通知
```

## 6. 实现优先级

**Phase 1 — Desktop 托盘 + 基础通知**（当前）：
- [ ] 引入 ComposeNativeTray 依赖（`io.github.kdroidfilter:composenativetray`）
- [ ] `AppTray` Composable：托盘图标（在线/离线状态）+ 菜单（打开/通知开关/退出）+ 未读 badge
- [ ] `DesktopNotificationManager`：Linux notify-send + macOS/Windows 原生通知（JNA）
- [ ] TCP 消息到达 → 弹系统通知（发送者 + 摘要）
- [ ] 点击通知跳转聊天 + 前台检测

**Phase 2 — Android FCM 推送**：
- [ ] 服务端 FCM SDK，devices 表存 FCM Token
- [ ] Android FirebaseMessagingService → 唤醒拉取消息
- [ ] NotificationChannel 三类分组 + MessagingStyle

**Phase 3 — 通知增强**：
- [ ] 多会话 Group Summary 分组
- [ ] 静音 + @提及例外 + 夜间免打扰
- [ ] 通知声音自定义

---

## 与 Signal / Telegram 的对比

| | Signal | Telegram | TeamTalk |
|---|---|---|---|
| Android 推送 | FCM + UnifiedPush | FCM + 自建推送 | FCM |
| Desktop 托盘 | Electron BrowserWindow | tdlib + 原生托盘 | ComposeNativeTray（原生 API） |
| Desktop 通知 | Electron Notification | tdlib → 原生通知 | notify-send (Linux) + JNA 原生通知 (macOS/Windows) |
| 通知渠道 | per-conversation | per-chat 分组 | 三类渠道 |
| 前台检测 | Activity 判断 | 前台窗口检测 | currentViewingChannelId |

TeamTalk 选择 ComposeNativeTray 而非 JVM `SystemTray`，因为 `SystemTray` 的 AWT `PopupMenu` 无法满足 IM 托盘的交互需求（子菜单、分隔符、左键点击、暗色模式适配、HDPI 图标）。ComposeNativeTray 通过各平台原生 API（Win32/Cocoa/GTK）解决这些问题，且与 Compose 重组机制无缝集成。
