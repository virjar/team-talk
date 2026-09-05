# 客户端

TeamTalk 客户端共享业务能力和视觉语言，但不追求像素或导航一致。Desktop 是多窗格生产力应用，
Android 是单屏触控应用；把其中一端的页面结构直接复制到另一端会产生错误的交互层级。

## 1. 代码边界

| 层 | 内容 |
|---|---|
| `protocol` | 消息模型、wire 与 RPC 契约 |
| `shared` | 会话、Repository、LocalCache、连接与事件处理 |
| `app/commonMain` | 可复用 Screen/Component、ViewModel、主题、富文本与消息渲染 |
| `desktop` | Window、三栏壳、弹窗/抽屉/任务窗口、托盘、桌面媒体、测试服务 |
| `android` | Activity、NavHost、权限、系统返回、通知和 Android 媒体 |

共享 Composable 不应该决定自己是全屏、弹窗还是抽屉。平台壳提供容器与导航回调，业务组件提供内容
和动作。

会话级代码按职责分成三类：ViewModel 输出持续可观察的数据，`navigation/feature` 编排账户、群组、
发现等用例，`AppDataState` 只组装它们并管理销毁。新功能不得通过向 `AppDataState` 追加一组字段和
方法来获得“全局可访问性”。

文档工作区规模较大，其状态、创建、保存、树导航与草稿恢复集中在
`navigation/feature/document/`，不再平铺在其他业务 Feature 之间。阅读编辑与保存链路时，先看
`DocumentWorkspaceFeature.beginDocument/updateDraft/saveActive`，再到
`DocumentWorkspaceSaveCoordinator`：它直接使用所属工作区的状态，编排草稿持久化、远端写入和
结果发布，不再经过一层只转发方法的状态端口。普通保存的持久收尾在
`DocumentWorkspaceDraftLifecycle`；创建已经提交但没有可发布快照时，
`DocumentWorkspaceCreateCompletion` 在同一文件内完整说明身份绑定与迟到编辑的收尾顺序。

聊天里的收藏、办公引用候选加载和目标读取由 `navigation/feature/MessageActionsFeature` 负责，
生命周期随登录会话，不随当前聊天切换。引用选择器直接使用带 `spaceId/targetId` 的 `OfficeRefBody`，
双端共用 `OfficeRefPickerDialog`，选中后交给当前 `ChatViewModel.sendMessage`，与普通消息共用持久
发送队列、重试和失败气泡。平台负责开关和打开目标后的导航，不另造候选身份模型或 ACK 等待路径。

## 2. 信息架构

一级栏目稳定为：

- 会话：收件箱、聊天和消息上下文。
- 通讯录：好友申请、本地联系人和资料入口。
- 设置：个人资料、安全、设备和应用信息。

全局搜索属于应用壳，不属于会话或通讯录标题。添加好友由搜索结果的用户资料发起；创建群组由
用户资料或明确的成员选择流程发起；邀请成员只存在于已有群上下文。

消息搜索结果使用 `chatId + serverSeq` 进入原会话。共享聊天层先建立最新权威历史链，再以固定一页
加载目标附近历史，精确序号出现后滚动并短时高亮；不存在与失权使用同一安全反馈，不能退化为打开
会话首页或选择相邻消息。Android 路由与 Desktop 导航只负责携带身份，不各自实现定位算法。

## 3. 交互状态与远端状态

客户端拥有窗口、导航、焦点、输入草稿、菜单、下载与播放进度。服务端拥有权限、群成员、附件
存在性、消息序列和已读水位。UI 可以展示乐观发送和上传动画，但服务端拒绝后必须回到失败状态。

聊天普通草稿沿 `LocalCache → 平台聊天入口 → ChatPanel` 进入输入框；Android 不再保存一份只初始化
一次的影子草稿，Desktop 也不再通过单方法 dispatcher 转发。保存统一交给 `AppDataState.saveDraft` 的
会话级写入队列，页面离开不会取消已入队的保存。

阅读这条链路时，输入框同步规则集中在 `ChatScreenState.kt` 的 `ChatDraftSync`：缓存尚未加载用
`null`，明确清空用空字符串；新缓存只替换仍等于上一份缓存的普通正文，不覆盖本机新输入、回复、
消息编辑或富资产上下文。最后观察的缓存与最后发布的正文分别记录，因为“已入队”不等于“已落盘”。
导航时由现有 `ChatComposerContextStore` 保留这两个比较值；离开补写后的最终帧也更新发布值，返回
不会重复发布旧正文。不新增持久草稿模型或平台状态机；富资产恢复边界见[富文本与媒体](rich-content.md)。

好友 Presence 是服务端在线连接事实的会话内投影，不写 LocalCache；连接尚未取得完整快照或已经断开
时保持 UNKNOWN，不能把“未知”伪装为权威离线。Typing 则只是一条可丢弃的当前会话交互提示，不进入
草稿、消息、outbox 或持久事件游标；平台前台状态只控制是否尝试发送，权威成员校验仍在服务端。

文档正文草稿暂时属于页面交互状态，服务端 Document/revision 是远端事实。保存冲突时客户端必须保留
当前草稿并提示刷新或人工合并，不能用刚拉到的服务器快照静默覆盖用户输入。

### 版本协商与升级提示

发行版本、协议版本和本地数据 schema 是三个不同的事实。Android、Desktop、SDK 使用相同的
`TeamTalkBuild.RELEASE_VERSION` 三段发行字符串；它用于显示和诊断，不按字符串大小判断兼容。
连接兼容由独立的协议 `major/minor` 及双方实际保留的 minor 窗口决定；完整规则见
[协议与契约](../04-protocol/README.md)。

每条 TCP 连接先发 `NEGOTIATE`，收到并验证 `NEGOTIATE_RESP` 后才发送 AUTH 凭据。
[AuthSyncCoordinator](../../client/shared/src/commonMain/kotlin/com/virjar/tk/shared/client/AuthSyncCoordinator.kt)
持有协商结果，`ImClient`/`ClientSession` 只读发布，`AuthState` 把同一个结果交给双端壳；连接仍使用
`CONNECTED → SYNCHRONIZING → AUTHENTICATED`，不为横幅另造连接状态机。

| 已知事实 | 客户端表现 |
|---|---|
| 同 major、有共同 minor，客户端 minor 未落后 | 正常认证与进入工作区 |
| 同 major、有共同 minor，客户端 minor 低于服务器 | 正常使用；共享升级横幅持续显示在工作区上方 |
| 客户端低于最低 minor、major 不兼容或双方窗口无交集 | AUTH 前拒绝，停止重连，强制升级表面优先于工作区 |
| 断网、超时或无有效协商响应 | 按网络失败恢复，不据此持久标记不兼容 |

图形客户端把服务器已淘汰的旧客户端版本（低于最低 minor 或客户端 major 落后）按
`deployment + 精确 major/minor ID` 持久保存，因而同一被淘汰版本离线重启也不能重开工作区。
服务器自身过旧（包括客户端 major 更高）只阻断当前工作区，不持久锁住客户端；管理员升级服务器后，
重新启动即可保留凭据再次协商。旧单字节版本拒绝键不沿用到新的版本 ID 空间，迁移标识时保留凭据。
首次观察到服务器提高最低版本之前，离线客户端无法预测新要求，仍按已有本地身份打开缓存；
恢复联网收到明确拒绝后才退役该工作区。提示只提供升级说明，强制表面可退出应用；当前没有自动下载或安装流程。

**运行中收到版本拒绝**与**安装跨 major 的新客户端**有不同的数据后果：前者保留本地账号工作，
后者由平台启动所有者在凭据、数据库和页面打开前重置本安装的数据并要求重新登录。同 major 的
minor 升级走 schema 迁移，保留草稿、待发消息和其他可靠事实；不能用删库重同步代替迁移。
详细存储边界见[客户端与 SDK](../03-architecture/client-and-sdk.md#6-localcache)。

同 major 内的新增消息类型仍需明确的旧版本适配或提高最低 minor；客户端不提供 Generic/opaque
扩展逃生通道，也不能假设未知消息 body 总能安全跳过。业务兼容规则以协议契约及实际版本适配为准。

## 4. 平台文档

- [Desktop](desktop.md)：三栏、窗口和上下文容器。
- [Android](android.md)：页面栈、触控和平台能力。
- [通知机器人](notification-bots.md)：群内创建、一次性凭据与外部系统入站通知。
- [无头客户端](headless.md)：ImBot、tt-agent、CLI 与 MCP 接入。
- [设计系统](design-system.md)：颜色、字阶、间距、组件与状态。
- [富文本与媒体](rich-content.md)：编辑、渲染、附件和播放。

## 5. 新页面的设计顺序

1. 在[领域模型](../02-product/domain-model.md)中确认对象与权限。
2. 确定入口属于应用、栏目、对象还是当前会话上下文。
3. 为 Desktop 选择工作区、检查器、模态、任务窗口或确认框。
4. 为 Android 选择当前页内容、全屏目的页、bottom sheet 或 dialog。
5. 复用主题令牌和内容组件，不强行共享导航。
6. 定义空态、加载、错误、无权限和数据缺口。
7. 添加稳定 testTag，并在真实客户端验证。
