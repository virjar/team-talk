# ROADMAP — 路线图与待办

> 状态标记：✅ 完成 · 🚧 进行中 · 📋 计划 · 💡 想法

---

## P0 — 正确性收尾

| 项 | 说明 | 状态 |
|----|------|------|
| 离线事件补发接线 | 客户端已维护 lastEventId 游标，但未回填 `AuthRequestPayload.lastEventId`（恒 0）；服务端补发链路就绪。接线点：AuthController/ImBot 认证前读游标持久值 | 📋 |
| 好友红点初始加载时序 | ContactViewModel.init 的 refreshPendingApplyCount 可能在认证完成前执行，RPC 被门禁拦截返回空。修法：延迟到 AUTHENTICATED 后刷新 | 📋 |
| 客户端 DB schema 迁移 | `.sq` 加列 = 清库重建（开发期可接受）。正式版需要 SQLDelight migration 文件 | 📋 |
| 上传接口鉴权 | `/api/v1/files/upload` 目前 `X-Uid` 可伪造（TODO 标注） | 📋 |
| F13 长按消息弹系统菜单 | MessageBodyRenderer 的 Text 可被系统选择拦截 onLongClick | 📋 |
| T14 删除好友 UI 接线 | UserProfileScreen deleteFriend 按钮接 ViewModel | 📋 |

## P1 — 无头 IM 与 AI 接入（战略方向）

| 项 | 说明 | 状态 |
|----|------|------|
| ImBot 无头客户端 | register/login/消息流/回执，bot 对 bot 集成测试全绿 | ✅ |
| CLI 可执行入口 | `headless` main：配置文件/环境变量账号 → 消息桥 stdout/stdin/管道；`gradlew :shared:installDist` 产物可跑在服务器 | 📋 |
| AI 员工对话框架 | ImBot 之上：消息→LLM→回复循环抽象（限速/会话白名单/人格配置/多 bot 编排）。目标：AI 员工通过 IM 与人和其他 AI 跨域协作 | 💡 |
| Webhook/HTTP 桥 | bot 收发消息映射为 HTTP 回调（接入外部系统） | 💡 |
| 群机器人管理 | bot 入群/踢出/权限的 API 化 | 💡 |

## P2 — SDK 完善

| 项 | 说明 | 状态 |
|----|------|------|
| NOTIFY 契约表 | 唯一事实源 + 双侧校验 + 测试（CONTACT_ACCEPTED/DELETED 错配已修） | ✅ |
| SDK 分层 | client/repository 并入 shared；SDK 测试不编译 Compose | ✅ |
| bot 集成测试 | 对真实服务器 3 用例（认证/消息全链路/已读回执） | ✅ |
| 发送队列与重试 | 断线期间发送排队、重连后补发（当前失败即终止） | 📋 |
| 消息本地全文搜索 | 客户端 SQLite FTS（当前搜索纯服务端 Lucene） | 💡 |
| 多端已读同步 | readSeq/peerReadSeq 服务端权威化（换设备不丢） | ✅ |
| ImBot 常驻进程化 | 断线自动重连策略参数化 + 心跳看门狗 | 📋 |

## P3 — 服务端

| 项 | 说明 | 状态 |
|----|------|------|
| markRead 持久化断裂修复 + 会话行预创建 | 多设备未读同步根因 | ✅ |
| peerReadSeq 持久化 | ✓✓ 回执换设备不丢 | ✅ |
| 邀请链接原子自增 | use_count 竞态 | ✅ |
| ContactService.accept 全表扫 | listFriends().find 优化为直查 | 📋 |
| 错误码国际化 | 中文 message → code 枚举 + 客户端本地化文案 | 📋 |
| HTTP 上传鉴权 | 见 P0 | 📋 |
| ChatStore.maxSeq 崩溃窗口 | 内存自增异步落库的极端场景（E2 见 lessons） | 💡（WAL/同步刷盘权衡） |

## P4 — UI 迭代（独立课题）

| 项 | 状态 |
|----|------|
| 桌面端导航独立重构（替换手搓 currentScreen 枚举，AppState 移除遗留） | 📋 |
| Desktop 子窗口 ESC 关闭不可靠 / TestHttpServer 窗口语义 owner 泄漏 | 📋 |
| F15 Desktop Profile 源码级隔离 | 📋 |
| Android E2E 全流程 T01-T34 剩余用例 | 📋 |

## 已完成里程碑（倒序摘录）

- **SDK 闭环**：shared=SDK 物理分层 + NOTIFY 契约表 + ImBot 无头入口 + bot 对 bot 验收（2026-07）
- **多设备同步**：readSeq 持久化断裂修复 + 会话行预创建 + peerReadSeq 持久化（2026-07）
- **技术债清理 9 连**：logout 凭证泄露 / auth-expiry 链路 / LocalCache 竞态 / 子页面 action 下沉 / FileOps 合并 / 全局单例治理 / HttpUtil / 死代码 / 服务端一致性（2026-07）
- **稳定性**：crash 兜底体系 + CoroutineExceptionHandler 全覆盖 + 静默 catch 审计（2026-06）
- **重构到基本可用**：协议重写（PacketBuffer/ProtoCodec 体系）+ 文档体系 v1（2026-06）
