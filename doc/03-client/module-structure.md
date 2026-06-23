# 客户端模块文件结构

> 客户端各目录的完整文件清单和职责说明。

## commonMain（共享层，70%+ 代码）

```
app/src/commonMain/kotlin/com/virjar/tk/
├── client/
│   ├── ImClient.kt              # TCP 连接生命周期（连接/断开/重连/心跳）
│   ├── RpcClient.kt             # INVOKE/RESPONSE 封装（requestId 管理、超时）
│   ├── EventProcessor.kt        # NOTIFY 事件处理（解析 + 写本地 DB）
│   ├── LocalCache.kt            # 本地数据读写接口
│   ├── LocalCacheImpl.kt        # SQLDelight 实现（消息懒加载 + user 关联）
│   ├── ClientSession.kt         # 会话编排（持有 ImClient + 各 Repository）
│   ├── HttpLogUploader.kt       # 日志 HTTP 上传（fault 触发 + 定时批量）
│   ├── CrashDumper.kt           # Crash 持久化（原子写 pending 文件 + 重启上传）
│   └── ApiClient.kt             # HTTP 文件上传/下载
│
├── repository/                  # Repository 层（封装 RPC 调用 + 本地写入）
│   ├── UserRepository.kt
│   ├── ContactRepository.kt
│   ├── ChatRepository.kt
│   ├── MessageRepository.kt
│   ├── ConversationRepository.kt
│   └── DeviceRepository.kt
│
├── viewmodel/                   # ViewModel（StateFlow + 增量更新）
│   ├── BaseViewModel.kt         # 基类（error/loading 状态管理）
│   ├── ConversationViewModel.kt
│   ├── ContactViewModel.kt
│   └── ChatViewModel.kt
│
├── navigation/                  # 导航与状态
│   ├── AppDataState.kt         # 纯数据/业务状态（repos/VMs/屏幕数据，不含导航）
│   ├── AppState.kt             # Desktop 导航状态（继承 AppDataState + currentScreen 枚举）
│   ├── SubScreenRouter.kt      # Desktop 子屏幕路由（共享子屏幕 Composable 分发）
│   └── AppNavigation.kt        # MainTab 枚举（会话/通讯录/设置）
│
├── ui/
│   ├── bridge/
│   │   └── ChatMediaConfig.kt  # 平台媒体能力配置（收敛 ChatPanel 参数）
│   ├── component/              # 共享 UI 组件
│   │   └── MessageBodyRenderer.kt  # 按 body 类型渲染消息
│   └── screen/                 # 共享 UI 屏幕（跨平台 Composable，不含导航）
│       ├── LoginScreen.kt
│       ├── RegisterScreen.kt
│       ├── ChatScreen.kt       # ChatPanel（聊天面板，被 Android/Desktop 共用）
│       ├── ConversationListScreen.kt
│       ├── ContactListScreen.kt
│       ├── SearchUsersScreen.kt
│       ├── SearchMessagesScreen.kt
│       ├── UserProfileScreen.kt
│       ├── CreateGroupScreen.kt
│       ├── GroupDetailScreen.kt
│       ├── EditProfileScreen.kt
│       ├── ChangePasswordScreen.kt
│       ├── MeScreen.kt         # 设置页
│       ├── ForwardScreen.kt
│       ├── FriendAppliesScreen.kt
│       ├── DeviceScreen.kt
│       ├── BlockListScreen.kt
│       ├── InviteMembersScreen.kt
│       ├── InviteLinksScreen.kt
│       └── MediaGalleryWindow.kt  # 全屏图片画廊
│
├── util/
│   ├── AppLog.kt               # 跨平台日志（trace/fault/snapshot）
│   ├── LogBuffer.kt            # 环形日志缓冲区
│   └── MessagePreview.kt       # 消息→单行预览文本 + formatFileSize
│
└── body/                       # 消息体子类型（通过 IProto 序列化）
    ├── (在 shared 模块定义)
```

## Android 专属（androidMain）

```
app/src/androidMain/
├── client/
│   ├── AppDatabase.android.kt  # SQLDelight AndroidSqliteDriver
│   └── TokenStorage.android.kt # SharedPreferences
└── util/
    └── AppLog.android.kt       # android.util.Log 实现

android/src/main/kotlin/com/virjar/tk/
├── TeamTalkApp.kt              # Application（日志注入/crash拦截/ServerConfig）
├── MainActivity.kt             # 入口 Activity（NavHost 导航）
├── AndroidChatScreen.kt        # ChatPanel 的 Android 包装（ChatMediaConfig 构造）
├── HomeScreen.kt               # Android HomeScreen（Tab 容器）
├── MediaHelper.kt              # 文件上传（multipart HTTP）
├── MediaThumb.kt               # 图片缩略图（Glide）
├── VoicePlayer.kt              # 语音播放（MediaPlayer）
├── VideoPlayer.kt              # 视频播放（ExoPlayer）
├── TokenStore.kt               # Android token 持久化
├── TestTagEnabler.kt           # 测试 testTag 开关
└── Routes.kt                   # NavHost 路由定义
```

## Desktop 专属（desktopMain）

```
app/src/desktopMain/
├── client/
│   ├── AppDatabase.desktop.kt  # SQLDelight JdbcSqliteDriver
│   └── TokenStorage.desktop.kt # Properties 文件
└── util/
    └── AppLog.desktop.kt       # LocalLogFile（FileWriter）实现

desktop/src/desktopMain/kotlin/com/virjar/tk/
├── Main.kt                     # 入口（日志注入/异常拦截/dataDir）
├── MainAppContent.kt           # 三栏布局（导航+列表+详情/聊天）
├── LoginWindow.kt              # 登录/注册窗口
├── AppState.kt                 # Desktop 导航状态
├── SubWindowHost.kt            # 子窗口管理（EditProfile/CreateGroup/...）
├── SubScreenRouter.kt          # 子屏幕路由
├── DesktopMediaHelper.kt       # 文件选择/上传/录音/图片解码
├── DesktopEnvironment.kt       # Desktop 环境配置（dataDir 等）
└── test/
    └── TestHttpServer.kt       # 测试 HTTP 服务（语义树/点击/输入）
```
