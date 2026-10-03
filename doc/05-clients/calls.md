# 1:1 通话

语音与视频 1:1 通话（协议 minor 0.5）覆盖 Android 与 Desktop：WebRTC P2P 直连媒体，
服务端只做信令路由与呼叫状态机，媒体面不经过服务端进程；NAT 兜底由**内嵌 TURN/STUN**
提供（纯 Kotlin 实现于服务端进程内，无独立中继进程）。iOS 通话入口与媒体引擎不在本期
（iOS 客户端收到来电信令时无 UI 呈现，由振铃看门狗 50s 自动拒接）。

## 范围与边界

- 好友私聊 1:1 语音/视频通话；群聊、系统账号与保存的消息会话不提供入口。
- 被叫在线 → 实时振铃；被叫离线 → 主叫收到"不可达"，服务端自动落一条未接来电
  （CALL_LOG），离线推送沿用普通消息链路。被叫忙线不打扰。
- 未接来电通知、通话记录、历史检索按普通消息语义工作；不支持离线设备实时唤醒
  （厂商透传通道与系统级通话界面属后续阶段）。
- 不提供通话保持/转移/群聊会议（SFU）；媒体协商为非 trickle（ICE gathering 完成后
  整段 SDP 交换），ICE 候选通道保留作兜底。
- **混布（服务端已升 minor 5、被叫客户端未升）**：服务端按连接协商版本投影——
  CALL_EVENT/CALL_SIGNAL 对旧连接静默不下发（被叫无感知，主叫按无应答超时收敛）；
  CALL_LOG 消息事件对旧客户端投影为占位形态（保留身份与类型码、剥正文、打
  FLAG_PROJECTION_PLACEHOLDER），旧端红点背后可见"当前版本不支持此消息"提示，
  升级后按标记位重拉历史自愈。反向混布（客户端已升、服务端未升）：通话入口点击按
  "不可达"收敛（RPC 版本门禁异常被 CallCenter 转换），其余功能不受影响。

## 协议契约（`@SinceProtocol(5)`，随 0.0.6 发行冻结）

| 契约 | 编号 | 说明 |
|---|---|---|
| `CallRpc` | serviceId `call`，method 1-4 | invite/answer/signal/hangup；invite 返回可达/忙线裁决与短期 ICE 凭据 |
| `CALL_EVENT` | NotifyType 65（瞬时，eventId=0） | RING 只投被叫、ACCEPTED 只投主叫、ENDED 双方收敛；RING 携带本轮 ICE 服务器 |
| `CALL_SIGNAL` | NotifyType 66（瞬时） | SDP/ICE 中继，服务端不理解内容 |
| `CALL_LOG` | MessageType 19 | 服务端在呼叫终结时以主叫身份写入双方私聊；`clientMsgId=callId` 幂等；旧客户端按未知类型安全跳过 |

瞬时信令不持久化、离线不补偿：信令丢失即本次呼叫失败，双方以本地超时（振铃 45s）收敛。
未知 `CallEndReason` 读侧按连接中断收敛，不拒绝解码。

## 服务端

- `domain/call/CallService`：进程内呼叫表（振铃→接通→终结）。准入=好友且双向未拉黑；
  忙线以 `CallInviteOutcome(busy)` 表达。依赖收窄为准入/会话定位/记录落库/在线判定/ICE
  签发五个端口。
- 超时由 `MaintenanceWorker("call-timeout-sweep")` 回收；参与方全部设备离线经 presence
  观察按连接中断终结。进程重启即呼叫消失（有意设计，不做呼叫恢复）。
- CALL_LOG 落库走服务端专用旁路（`sendCallLog`）；客户端提交的 CALL_LOG 消息被
  `CREATABLE_MESSAGE_TYPES` 拒绝——通话记录是服务端权威事实。

## 内嵌 TURN/STUN

- `server/infra/turn`：RFC 5389/5766 UDP 子集（Binding、Allocate/Refresh/CreatePermission/
  ChannelBind、Send/Data 指示与 ChannelData）。正确性由 RFC 5769 官方向量、已知消息类型
  表与真实 UDP 全流程集成测试锁定。
- 认证为 coturn 同款 time-limited credentials：`username=过期秒`、
  `password=HMAC-SHA1(secret, username)`；凭据仅随 invite 按呼叫签发（1 小时有效）。
- allocation/permission/channel 有硬生命周期与周期清扫；relay 端口范围与总量有界；
  进程重启全部回收。
- 部署配置 `server { turn { ... } }` 只含非敏感参数（端口、relay 范围、realm、对外地址
  ——默认取 HTTP URL 主机）；凭据是 `deployment.secrets` 的 `TURN_SECRET` 键（≥16 字节），
  首次部署自动生成并默认启用，部署时写入远端 `conf/env.sh`（600），升级部署自动回拉。
  TURN 与 STUN 同一配置块——没有它客户端只剩 host 候选，而用户大多在 NAT 后，
  跨网通话基本打不通，所以默认开启；需放行 UDP 3478 与 relay 端口范围。

## 客户端

- `client/shared`：`CallCenter` 消费瞬时信令流驱动 `CallMediaEngine` 抽象，向 UI 暴露
  单一 `CallViewState`（振铃/连接/通话/结束）。本地忙时自动拒接来电；媒体失败按连接
  中断终结。作为 ClientSession owned 资源接入生命周期。
- 引擎实现：Android `AndroidCallEngine`（stream-webrtc-android，官方 `org.webrtc` API）、
  Desktop `DesktopCallEngine`（webrtc-java；视频帧 I420→Skia 位图限频推送）。iOS 引擎
  属后续阶段（见上文范围边界）。
- UI：`CallScreen`（commonMain）覆盖来电接听/拒接、去电等待、通话中控制（静音/免提/
  翻转摄像头/挂断）与本地小窗预览；聊天头部私聊提供语音/视频入口。CALL_LOG 以气泡
  卡片渲染（呼出/呼入、时长、未接原因）。
- Android：接听/拨打前请求麦克风/摄像头权限；音频焦点与听筒/扬声器切换由
  AudioManager 管理。来电在前后台都呈全屏覆盖层。
- 双端互操作契约收敛在 `client/shared` 的 `IceInterop`（纯函数，commonTest 锁定）：
  候选行剥离 `ufrag/network-id/network-cost`、缓冲先快照再清空、SDP 候选计数。
  两套引擎必须调用同一实现，禁止在平台侧复制规则——历史上复制粘贴导致"修一漏一"。
  候选**上送**也必须发清洗后的行：Android 曾把清洗结果只存进本地死列表、实际发送
  原始行，靠对端新版 libwebrtc 宽容才未暴露。

### 静默失败防线（引擎观测点约定）

真机联调的最大教训：多数故障的表象是"看起来在工作"而非报错。新增或改动引擎/协商
代码时，以下调用类别必须配计数或日志，评审按此检查：

- **返回 void 的 native 调用**：`addIceCandidate`、`addTrack`、`removeTrack` 等——
  至少在关键路径打点（已应用候选数、远端描述候选总数）。
- **回调可能永不触发的等待**：gather 完成回调、set-remote 成功回调、首帧——
  必须有兜底时延或看门狗（gather 3s 兜底、摄像头 5s 首帧看门狗、
  RINGING/CONNECTING 阶段看门狗），超时要写 fault 日志并上抛 UI。
- **信令先于引擎就绪到达**：被叫的 answer RPC 先于 `startEngine` 返回，主叫
  offer 可能抢在 PeerConnection 创建前送达——必须缓存待 start 后应用（双端
  `pendingRemoteSdp`），静默丢弃会让呼叫永远停在"连接中"。
- **异步失败只进平台的暗渠**：双端引擎统一接 `TkLogger`（trace=流程、fault=故障），
  不允许 `android.util.Log`/`System.err` 直写。
- **批量缓冲的清空**：一律 `snapshotAndClear`（锁内），禁止 `also { it.clear() }`。

## 验收入口

- 服务端状态机：`CallServiceTest`（10 项行为）。
- TURN 协议与数据面：`TurnProtocolTest`（RFC 5769 向量）、`TurnServerTest`（真实 UDP 全流程）。
- 客户端编排：`CallCenterTest`（假 RPC+假引擎）。
- 远程全链路：`CallSignalingRemoteE2eTest`（`-Dtk.e2e.remote=true`，目标 im.virjar.com；
  覆盖振铃/接听/中继/挂断/CALL_LOG/离线/非参与方拒绝）。
- 媒体面互通：双端真实对打（Android ↔ Desktop）按发布验收执行。
