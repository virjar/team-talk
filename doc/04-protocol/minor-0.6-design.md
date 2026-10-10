# 协议 minor 0.6 设计稿：消息轴心深化（MSG-01）

状态：**设计稿**（2026-10-09 立项，未落码；来源为竞品功能吸收计划——DooTask / Mattermost /
Nextcloud Talk / Huly / Startalk(QTalk) 源码对照，收录依据与结论见工作区根导航）。

本页是 0.6 批次的契约草案与实现追踪表。各契约实现后折入
[消息与附件](messages-and-attachments.md)、[RPC 与事件](rpc-and-events.md)、
[版本机制](versioning.md) 和[功能状态](../10-reference/feature-status.md)，
本页随之移除；不得把已折入的结论继续留在这里维护。契约一经正式发行即冻结，
以 `releases/` 快照为准，本页草案不构成兼容承诺。落码前本批全部契约可在批内修订。

## 批次功能与契约总览

| # | 功能 | 契约影响 | 竞品依据 |
|---|---|---|---|
| F1 | 会话内线程回复 | `MessageRpc.getThreadMessages`（methodId 11，@SinceProtocol(6)）；**Message wire 零变更**——权威存储的 replyToMsgId 即服务端重写的目标 serverSeq（rebuildReply），归属在查询期在线推导 | Mattermost 全局线程、Talk threads、Huly ThreadMessage |
| F2 | 群公告 | **零契约**：复用 `Chat.notice`（Chat.kt）、`ChatRpc.update` 与 `CHAT_UPDATED(11)`；纯客户端 | QTalk set_muc_vcard、DooTask 群公告 |
| F3 | 定时发送 | MessageRpc 新增 schedule/cancel/list 三方法；服务端 `scheduled_messages` 表与到期投递 worker；可选 `NotifyType(67)` 待发列表变更提示 | Mattermost scheduled_post、Talk 定时消息、DooTask 消息定时 |
| F4 | 稍后提醒 | **零契约**：客户端本地通知，复用任务提醒引擎；多设备同步为可选后续 | Mattermost post reminder、Talk 稍后提醒 |
| F5 | 群已读列表 | ConversationRpc 新增 `listChatReadStates`；`Conversations.read_seq` 直查 | QTalk get_muc_readmark |
| F6 | 多选/合并转发 | 预期**零契约**：`MergeForwardBody` 已注册；实现时核实批量引用校验路径 | DooTask 合并转发 |

协议注解统一 `@SinceProtocol(6)`；正式发行前同批可修订编号与字段。

## 契约设计

### F1 线程回复

- **零 wire 变更（2026-10-09 修正）**：草案原拟为 Message 增加可选 `threadRootSeq` 派生字段；
  落码前核实 Message 是定长字段顺序格式、无字段演进机制（同 major 只加新编号），且权威存储的
  `ReplyBody.replyToMsgId` 本就是服务端发送时重写的目标 `serverSeq` 字符串
  （`MessageService.rebuildReply`，目标必须已落库、父先于子，链天然无环）——
  因此归属可以完全在查询期推导，不需要任何新字段。
- **归属规则（查询期）**：从回复沿 reply 链上溯，父消息仍是回复则继续，首个非回复消息即根；
  链断点（目标缺失/非法）以该消息自身为根。新旧行为统一，无历史回填问题。
- **查询 RPC**：`getThreadMessages(chatId, rootSeq, fromSeq, limit)`，倒序窗口语义与
  `getHistory` 对齐（fromSeq=0 从最新回复开始），返回不含根消息；撤回的回复按既有撤回形态返回。
  实现为会话前缀倒序扫描：非回复条目用有界头部 decoder 廉价跳过；扫描上限 100,000 条、
  链上溯上限 100 层，超限整次失败不截断（与 listReactions 同原则）。
- **客户端聚合**："查看 N 条回复"计数由本地按 reply 链推导（本地缓存窗内准确，祖先被回收时
  少计、点开线程即由 RPC 权威收敛）；线程未读是纯本地显示事实，不进协议。
- **明确不做**：全局线程 inbox、跨会话线程视图（Mattermost 式）；Message 与 REPLY wire 不动。

### F3 定时发送

- **命令走 RPC 而非 MESSAGE 帧**：
  `scheduleMessage(chatId, messageType, body, deliverAt, operationId): ScheduledMessage`、
  `cancelScheduledMessage(scheduledId, operationId)`、`listScheduledMessages(chatId)`。
  消息体复用 `MessageBody` 既有序列化；到点投递走服务端内部消息管道（与服务号补发同构），
  执行与 MESSAGE 帧相同的附件 READY 与正文校验。
- **幂等与恢复**：`operationId` 重试返回原 `ScheduledMessage` 不重复排队；同一 `scheduledId`
  只投递一次；实例重启后 WAITING 条目自然恢复扫描。
- **到期扫描**：沿 `MaintenanceRuntime` worker 模式周期扫描，有界批量；到点投递失败
  （如附件已失效）置 `FAILED`，不重试、不丢列表，由发起者主动处理。
- **状态模型**：`WAITING / DELIVERED / CANCELLED / FAILED`；投递成功即产生正常消息事件，
  原冻结身份落库。
- **跨设备**：待发列表量小，`listScheduledMessages` 拉取 + 可选瞬时提示
  `SCHEDULED_MESSAGE_CHANGED(67)`（可丢，重连拉取兜底）。
- **时间语义**：`deliverAt` 一律 epoch 毫秒，展示层处理时区；不做循环定时（批次外）。

### F5 群已读列表

- `listChatReadStates(chatId, seq)` 返回当前活跃成员中 `readSeq ≥ seq` 的投影
  （uid、姓名快照、readSeq）。服务端硬上限与 `listReactions` 同原则：超限整次失败，
  绝不截断返回。
- **可见性**：默认仅群主/群管理员与该消息发送者可调用，服务端裁决，其他成员拒绝
  （2026-10-09 保守默认，内测反馈可放开）。
- **语义边界**：基于已读水位的近似（"读到哪"），不是逐条回执；UI 文案必须表达
  "已读 = 阅读进度已到该消息"，不得暗示逐条追踪。

### F6 合并转发

`MergeForwardBody` 已注册（条目上限 10,000）。实现时核实：客户端多选组装的 body 经
MESSAGE 提交时，服务端是否对全部被引用消息执行发送者可读校验（对照 OFFICE_REF 的
发送时校验原则）；缺口补齐不新开编号。

### 编号预留说明

卡片动作回调路由（群投票、日历 RSVP 的骨架）属于 minor 0.7「协作容器」批次，
**不在本批占号**；0.6 只含上表契约，不修改任何既有 wire 结构。

## 实现追踪

每格完成后以提交号标注；一格一格推进，不跳格提交。

| 功能 | 契约 | 服务端 | SDK | Desktop | Android | 测试 | 文档折入 |
|---|---|---|---|---|---|---|---|
| F1 线程 | ✔ d8458f9d4 | ✔ d8458f9d4（含存储/集成测试） | ✔ f9dfb0d99（查询接入+本地聚合，含 jvmTest 回归） | ✔ fa2da3ff2 | ✔ fa2da3ff2 | 桌面✔：真实客户端 E2E 全链路通过（徽标计数/嵌套归链、面板根+回复渲染、按钮关闭；ESC 无效属既有 TkBackHandler 桌面空实现）。中途修复页大小超契约缺陷（3c8bf9c32）。Android 真机场景待验 | ☐ 批次末统一折入 |
| F2 群公告 | —（零契约） | —（已具备） | —（observeChat 投影已有） | ✔ 桌面 E2E（公告条+全文弹窗+关闭；设置端编辑为既有能力） | ☐ | 桌面✔ | ☐ 批次末统一折入 |
| F3 定时发送 | ✔ 424c85d03（message/12-14 + wire 模型） | ✔ 424c85d03（scheduled_messages + worker + 集成测试 5 场景全绿） | ✔ 3f5a9590f（三方法+校验，jvmTest 回归） | ✔ 3b7cc2eac + 真实 E2E：调度→到点自动投递→取消防投递，全链路通过（部署 3b7cc2eac 后验证） | ✔（共享 UI） | 桌面✔ | ☐ 批次末统一折入 |
| F4 稍后提醒 | —（零契约） | — | ✔（message_reminders 本地表 + 6.sqm 迁移 + 收据/取消 API，存储测试） | ✔ 菜单快捷项（1小时/明天9点）+ 桌面托盘到点通知循环（30s 轮询，收据防重复）；登记路径 E2E 验证；到点弹窗与 Android 系统通知接线为边界 | ✔（共享 UI） | ✔ 存储测试 | ☐ 批次末统一折入 |
| F5 群已读列表 | ✔（conversation/6 + ChatReadState 模型） | ✔（水位 join 查询 + 保守可见性裁决 + 2,000 上限；集成测试：水位/可见性/越权拒绝） | ✔（listChatReadStates + 身份校验） | ✔ 菜单'已读情况'（发送者/管理员可见）+ 空态/列表对话框；登记 E2E 验证 | ✔（共享 UI） | ✔ 服务端集成测试 | ☐ 批次末统一折入 |
| F6 合并转发 | ☐（核实） | ☐（核实） | ☐ | ☐ | ☐ | ☐ | ☐ |

## 批次验收（对齐 roadmap MSG-01 最小验证）

- 线程：双端发起线程、回复聚合计数、跨设备一致、断网重连后计数收敛；
- 公告：管理员编辑 → 成员端公告展示与入群提示，非管理员不可编辑；
- 定时：设定 → 发起者离线状态下到期投递 → 取消生效 → 服务重启恢复扫描；
- 稍后提醒：本地通知按时触发，点击定位到消息；
- 群已读：发送者/管理员可见列表、普通成员被拒、水位语义与 UI 文案一致；
- 合并转发：多选 → 合并消息渲染 → 点击展开定位各条原消息。
