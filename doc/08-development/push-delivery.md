# 离线推送投递（厂商通道）

本文是消息离线推送的开发知识底座：描述现有厂商通道的端到端链路、设计不变量、扩展切入点，
以及 iOS/APNs 的现状与空白面。操作步骤（开通账号、DSL 写法、环境变量对照）在
[大陆厂商官方推送](../07-operations/configuration.md#大陆厂商官方推送)，客户端交互语义在
[Android 通知](../05-clients/android.md#消息通知的当前范围)，完成度在
[功能状态](../10-reference/feature-status.md#客户端体验)，本文不重复维护。

## 1. 范围与现状

"消息推送"在本项目语境指**离线厂商唤醒**：目标设备没有活跃 TCP 连接时，由厂商系统通知触达用户；
用户点击后再走既有登录与权威同步取回内容。在线投递始终走 TCP `NOTIFY` 加 `sync_events`
离线补发，与厂商通道无关，也不依赖它。

| 目标 | 现状 |
| --- | --- |
| OPPO 官方通道（服务端下发 + Android 客户端） | 代码端到端就绪：协议注册 RPC（`device/3`，`@SinceProtocol(2)`）、服务端 `infra/push/`、客户端 `src/oppo/` 源集、部署 DSL 均已实现；**未完成任何真机送达验收**（[CLIENT-07](../10-reference/roadmap.md#client-07--android-后台唤醒与系统通知)） |
| 其余五家 Android 厂商（小米/华为/荣耀/vivo/魅族） | 同上，六家共用一套框架，均未真机验收 |
| APNs（服务端下发） | 未实现。服务端推送仅覆盖大陆六家厂商，无任何 Apple 通道代码 |
| iOS 客户端 | 仓库不存在 iOS 壳：无 Kotlin/Native target、无 Xcode 工程；[路线图](../10-reference/roadmap.md)将 iOS 列为"先明确产品范围"，未立项。客户端发布注册表的 platform 枚举已预留 `ios` 位 |

因此"开发 OPPO 推送"的实际工作是**开通与真机验收收尾**，而"iOS 推送"的前置是 iOS 客户端
立项本身；两者的知识准备分别见第 5、6 节。

## 2. 端到端链路

### 2.1 服务端：从消息落库到厂商通知

```text
发送方 MESSAGE 帧
 └─ MessageService 落库并投影
     └─ MessageProjector.appendEvent(MESSAGE_RECV)        sync_events 持久事件
 └─ SyncEventDispatcher 派发
     ├─ 在线：ClientRegistry.push → ImAgent 写 NOTIFY 帧（按 uid 的全部活跃设备）
     └─ markDispatched 同事务回调 onDispatched
         └─ OemPushNotifications.recordDispatchedEvent     只记待推送状态，无网络 IO
             （筛选：MESSAGE_RECV、非发送者本人、未撤回、会话未静音且仍有未读；
               更新 OemPushRegistrations 行的 pendingEventId 与 pendingChats=chatId→seq 合并）
 └─ MaintenanceWorker("oem-push") 每秒 drainDue（每轮最多 32 行）
     └─ currentNotification：发送前实时复核，选最新未读且未静音会话
     └─ OemPushSender 按 vendor 路由 → OppoPushHttpClient 等
         （OPPO：sha256(appKey+timestamp+appSecret) 换 24h authToken，unicast 按
           registration_id 下发，click_action_type=5 intent 跳转，off_line_ttl=3600）
 └─ finish：成功才消费 pending 集；失败指数退避 5s×2ⁿ 封顶 1 小时；
     厂商判定注册无效（如 OPPO code 10000）直接删除注册行
```

设备侧由厂商系统服务展示固定文案（"你有新的未读消息，点击查看"，不含正文与会话名）；点击
经 `teamtalk-local://message/{fingerprint}/{datasetId}/{uid}/{chatId}` intent 拉起应用，
`MainActivity` 等认证与导航就绪后定位会话，身份不匹配则丢弃。

### 2.2 客户端：注册与上报

```text
登录后首次授权（AndroidOemPushDialog，"同意并开启/暂不开启"）
 └─ 初始化本机厂商 SDK（OPPO：HeytapPushManager.register(appKey, appSecret)）
     └─ registrationId StateFlow 就绪
 └─ AndroidOemPushRegistration 组合（连接状态, registrationId, 用户开关）
     └─ 连接 AUTHENTICATED 时调用 DeviceRepository.setOemPushRegistration
         （vendor, registrationId, packageName, deploymentFingerprint）
         失败每 30s 重试；serverRegistrationMayExist() 先写盘再发 RPC（崩溃安全）
 └─ 登出/凭据失效 → unregister，pendingUnregister 持久化直至服务端确认
```

服务端 `OemPushNotifications.register` 在认证 RPC 上下文里校验：vendor 在枚举内、
`deviceCredentialEpoch` 与 `Credentials` 行一致、refresh token 未过期、`deviceFlag` 为
Android、`packageName` 与部署配置一致；注册绑定到当前 refresh token hash。换账号或换
registrationId 时清除旧绑定。OPPO 是唯一客户端注册也需要 appSecret 的厂商（官方注册接口要求）。

## 3. 设计不变量

修改推送链路前先核对这些约束为什么存在：

- **挂点在 `markDispatched` 同一事务**：只有 TCP 已成功投递的事件才进入待推送状态，避免
  "在线端已收到还推厂商"；同事务写入保证崩溃时既不丢也不重复。hook 内不做任何网络 IO。
- **网络发送全部在后台 `drainDue`**：失败按行退避重试，`OemPushRegistrations` 的
  `nextAttemptAt` 索引驱动；dedup key 取 `sha256(generation:pendingEventId)` 前 20 字符，
  跨重启稳定。
- **注册绑定 refresh token hash 而非设备表**：推送注册的生命周期等于凭据生命周期，登出、
  踢设备、凭据失效经 FK 级联自然回收，不需要独立清理状态机。
- **发送前实时复核**（`currentNotification`）：静音、已读或访问范围变化后不再白发；正文
  与会话名不出现在厂商通知里（隐私与厂商审核双重要求）。
- **客户端先写盘再发 RPC**（`serverRegistrationMayExist`）：关闭推送的 RPC 丢失时，
  重启后仍能补发解绑，不出现"关了又响/响了又关"的双向漏判。
- **厂商通道与本地通知互斥**：厂商注册生效期间抑制 TCP 本地未读通知（`vendorNotifications`），
  回前台清除厂商通知，防双响；任务提醒走独立的本地渠道，不经厂商通道。
- **不接 Google/FCM 与聚合推送**；各厂商透传已停用或受限，进程回收后只承诺"通知触达、
  点击后恢复同步"，不做后台保活。

## 4. 扩展点

### 4.1 协议演进规则

推送注册 RPC `setOemPushRegistration` 是 `device/3`（`@SinceProtocol(2)`）。任何新增契约
（新 vendor 字段值不需要新 RPC，但新增参数、新 NotifyType、新模型需要）遵循
[变更指南](change-guides.md)与[版本机制](../04-protocol/versioning.md)：当前冻结基线为
protocol 0.3，本发行周期首次新增契约把根 `gradle.properties` 的 `teamtalk.protocolMinor`
推进到 4 并统一声明 `@SinceProtocol(4)`，同批共用；纯实现修复与真机验收不推进协议号。
新增/修改后运行 `writeProtocolBaseline` 登记开发清单，并更新 `RpcMethodIdGoldenTest`。

### 4.2 新增一个 Android 厂商通道的改动面

以既有六家为模板，改动面固定为六处：

1. `buildSrc/src/main/kotlin/deployment/OemPushConfig.kt`：vendor 常量、凭据文件键、
   环境变量生成与校验；`DeploymentDsl.kt` 增加 DSL 函数；补 `OemPushDeploymentTest`。
2. `client/android/src/<vendor>/kotlin`：实现 `OemPushChannel` 接口
   （`AndroidOemPush.kt` 定义：vendor/displayName/available/registrationId/initialize/
   unregister/clearNotifications），需要清单组件的在 `AndroidManifest.xml` 用
   `${vendor}PushEnabled` 占位。
3. `client/android/build.gradle.kts`：BuildConfig 参数注入；`GenerateOemPushChannels.kt`
   自动生成聚合表与占位组件，保证任意配置组合可合并清单。
4. 厂商 AAR 不进仓库与 version catalog，由部署配置 `sdkFile(s)` 从本地登记。
5. `server/.../infra/push/`：新 HTTP client + `OemPushSender` 路由分支 +
   `OemPushConfiguration.fromEnvironment` 环境变量装配。
6. [configuration.md](../07-operations/configuration.md#大陆厂商官方推送) 厂商章节。

### 4.3 服务端推送代码的边界

`infra/push/` 全部为 internal 类，刻意不进 `domain`：通过 `SyncEventDispatcher` 构造函数
回调 `onDispatched` 与函数式 `send` 注入解耦。新增通道沿用该模式即可；除非出现第二个
领域消费者，否则不新建 `domain/push` 端口。服务端唯一的出站 HTTP 基础设施在
`OemPushHttpSupport.kt`（协程桥接、15s 超时、16KB 响应上限、签名工具）与
`OemPushSender.kt`（`java.net.http.HttpClient`，`sendAsync` + 有界关闭）。

## 5. OPPO 通道收尾清单

代码无已知缺口，剩余全部是开通与验收（归
[CLIENT-07](../10-reference/roadmap.md#client-07--android-后台唤醒与系统通知)）：

- OPPO 开放平台以实际包名创建应用、申请推送权限、通过审核；登记 `oppoPush { appKey,
  channelId, credentialsFile(appSecret), sdkFile(AAR) }`（OPPO 要求 channel_id 与审核通过的
  通知渠道一致，客户端注册同时持有 appKey 与 appSecret）。
- 部署后核对 `OemPushRegistrations.lastFailure`：厂商错误码（如限流 code 13）与
  `PROVIDER_CONFIGURATION_REJECTED` 在此定位平台配置问题。
- 真机矩阵：锁屏送达、进程被系统回收后送达、设备断网恢复、静音与已读后不推、重复投递、
  通知点击定位、权限关闭、厂商服务不可用，以及在线/离线切换时与本地通知不双响。

## 6. iOS 与 APNs 的空白面

当前仓库做不了"iOS 推送交付"，因为不存在 iOS 客户端；本节记录事实空白与若立项时的
切入点，产品决策见路线图（未排期）。

- **客户端前置**：需要先立项 iOS 壳（Compose Multiplatform 或原生）。发布注册表
  platform 枚举已预留 `ios`，服务端侧无其他预留。
- **协议**：`AuthRules` 的 `deviceFlag` 只有 unknown/Android/Desktop，需追加 iOS 值
  （只追加枚举值，不改已发行语义）；注册路径可评估复用 `device/3`（放开服务端两处
  `DEVICE_FLAG_ANDROID` 校验）或按认证模型新增契约，两者都走 minor 4 批次。
- **服务端通道**：APNs Provider API 为 HTTP/2 + token-based JWT（p8 密钥、keyId、teamId、
  topic= bundleId），与现有 HTTP/1 风格的 `oemPushExchange` 封装不同；`java.net.http.HttpClient`
  支持 HTTP/2，连接生命周期与 JWT 签名需自行管理。通知合并、退避、注册表语义可整体复用
  `OemPushNotifications` 框架，仅新增 `ApnsPushHttpClient` 与 `OemPushSender` 分支。
- **部署配置**：仿照现有 `credentialsFile` 模式管理 p8 私钥（绝不入快照 JSON 与仓库），
  DSL 章节与 `oemPushEnvironment()` 输出对应环境变量。
- **客户端语义**：Android 侧 `AndroidOemPushRegistration` 的崩溃安全注册/解绑、授权弹窗、
  与本地通知互斥语义应在 iOS 壳对等复刻；iOS 无"厂商多通道"概念，只有 APNs 一条。

## 7. 实现坐标速查

| 层 | 位置 |
| --- | --- |
| 协议 IDL / 注册 RPC | `protocol/protocol/src/commonMain/kotlin/com/virjar/tk/protocol/rpc/def/DeviceRpc.kt` |
| 平台 flag | `protocol/.../model/AuthRules.kt`（`DEVICE_FLAG_*`） |
| 服务端待推送状态机 | `server/server/src/main/kotlin/com/virjar/tk/server/infra/push/OemPushNotifications.kt` |
| 服务端事件挂点 | `server/.../infra/sync/SyncEventDispatcher.kt`（`onDispatched` 回调） |
| 服务端 vendor 路由与 HTTP | `server/.../infra/push/OemPushSender.kt`、`OppoPushHttpClient.kt`、`OemPushHttpSupport.kt` |
| 服务端配置装配 | `server/.../infra/push/OemPushConfiguration.kt`（`fromEnvironment`）、`di/ServerModule.kt` |
| 注册表存储 | `server/.../infra/db/OemPushRegistrations.kt`（FK→Credentials 级联） |
| 注册 RPC 服务端实现 | `server/.../protocol/rpc/RpcImpls.kt`（`DeviceRpcImpl`） |
| 后台驱动 | `server/.../Application.kt`（`MaintenanceWorker("oem-push")`，1s 循环） |
| 客户端通道接口与注册器 | `client/android/src/main/kotlin/com/virjar/tk/android/AndroidOemPush.kt` |
| OPPO 客户端源集 | `client/android/src/oppo/kotlin/com/virjar/tk/android/OppoPushChannel.kt` |
| 客户端 RPC 封装 | `client/shared/src/commonMain/kotlin/com/virjar/tk/shared/repository/DeviceRepository.kt` |
| 本地通知与点击导航 | `client/android/src/main/kotlin/com/virjar/tk/android/AndroidMessageNotifications.kt`、`AndroidNotificationTarget` |
| 部署 DSL / 环境变量 / 代码生成 | `buildSrc/src/main/kotlin/deployment/OemPushConfig.kt`、`DeploymentDsl.kt`、`GenerateOemPushChannels.kt` |
| env.sh 生成与上传 | `buildSrc/src/main/kotlin/deployment/EnvSh.kt`、`UploadLogic.kt` |
