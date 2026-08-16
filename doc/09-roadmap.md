# ROADMAP — 路线图与待办

> 状态标记：✅ 完成 · 📋 计划 · 💡 想法。已完成的移入底部里程碑，本表只留**有效待办**。

---

## P0.5 — 管理后台（已完成 2026-08，MVP）

✅ React SPA（admin/ 第四端）+ 同进程 REST + 固定凭据；五页面（Dashboard/用户/
消息审查/日志/群）+ 封禁 enforcement 全链路。V2 遗留：审计日志/图表/公告/敏感词。

## P1 — 无头 IM 与 AI 接入（战略方向）

| 项 | 说明 | 状态 |
|----|------|------|
| ~~CLI 可执行入口~~ | ✅ 已完成（2026-08）：`headlessDist` 产物（register/login/selftest 三模式 + stdin/stdout 行协议），见里程碑 | ✅ |
| AI 员工对话框架 | ImBot 之上：消息→LLM→回复循环抽象（限速/会话白名单/人格配置/多 bot 编排）。AI 员工经 IM 与人和其他 AI 跨域协作 | 💡 |
| Webhook/HTTP 桥 | bot 收发消息映射 HTTP 回调（接外部系统） | 💡 |
| 群机器人管理 | bot 入群/踢出/权限 API 化 | 💡 |

## P2 — SDK 完善

| 项 | 说明 | 状态 |
|----|------|------|
| 发送队列与重试 | 断线期间发送当前立即失败（合成 ack code=-1）；排队重连后补发 + 状态机（sending→queued→sent） | 📋 |
| 离线补发分页 | 服务端 `getEventsAfter` 单次 limit=100：长时间离线需多轮重连逐批补全（游标推进天然支持）；或协议升级分页游标 | 📋（低优先，已知限制） |
| 消息本地全文搜索 | 客户端 SQLite FTS（当前搜索纯服务端 Lucene，离线不可用） | 💡 |

## P3 — 服务端

| 项 | 说明 | 状态 |
|----|------|------|
| ContactService.accept 全表扫 | `listFriends(fromUid).find{}` 优化为直查（两好友行 INSERT 后按 (uid,friendUid) 查） | 📋 |
| 错误码国际化 | wire 错误已是分层码（400/401/500/504），剩余：message 中文串 → code 枚举 + 客户端本地化文案表 | 📋 |
| ChatStore.maxSeq 崩溃窗口 | 内存自增异步落库，崩溃丢增量致重启 seq 回退（updateMaxSeq 带 `< seq` 保护兜底）。WAL/同步刷盘权衡 | 💡 |

## P4 — UI 迭代（独立课题，SDK 之外）

| 项 | 状态 |
|----|------|
| 桌面端导航独立重构（替换手搓 currentScreen 枚举，AppState 遗留清理） | 📋 |
| Desktop 子窗口 ESC 关闭不可靠 / TestHttpServer 窗口语义 owner 泄漏 | 📋 |
| F13 长按消息弹系统文本菜单（MessageBodyRenderer Text 被系统选择拦截 onLongClick） | 📋 |
| F15 Desktop Profile 源码级隔离 | 📋 |
| Android E2E 全流程 T01-T34 剩余用例 | 📋 |

## 已完成里程碑（倒序）

- **P0/P1 收尾**（2026-08）：上传接口 Bearer accessToken 鉴权（X-Uid 伪造通道封死）+ 本地库 schema 迁移（.sqm + v1 遗库识别坑修复）+ CLI 入口（headlessDist：register/login/selftest + MSG 行协议，端到端实跑验证）。好友红点时序项核实后关闭（问题不成立，真相是当年 SQL 直插无事件推送）
- **SDK 完整性收官**（2026-08）：重连三 bug 根治（B10 重放注册掉线 / B11 监听断链假活 / B12 补发游标快照）+ Presence 收敛 shared（契约登记）+ 事件流全家桶（contact/chat/presence）+ ImBot API 全量化（媒体/typing/撤回/群组/搜索）+ 测试体系（LocalCache/EventProcessor 单测 14 用例、bot 集成 9 用例、server 测试真实 PG 化去 embedded）
- **RPC IDL 化**（2026-08）：Kotlin interface = IDL + KSP2 生成 Contract/Stub/Proxy（精简版 gRPC）；serviceId 字符串化（协议 v2 一刀切）；手写 encodePayload/withPayload 根除（A3 坑根治）；uid 收敛 Stub 成员。演进方向：domain Service 直接实现 RpcStub 删薄壳层
- **文档体系深度重写**（2026-08）：wire 级协议规格 / 踩坑经验 40+ 条 / 设计理念 10 条，达到"凭文档可重写项目"标准
- **SDK 闭环**（2026-07）：shared=SDK 物理分层 + NOTIFY 契约表 + ImBot 无头入口 + bot 对 bot 验收
- **多设备同步**（2026-07）：readSeq 持久化断裂修复 + 会话行预创建 + peerReadSeq 持久化
- **技术债清理 9 连**（2026-07）：logout 凭证泄露 / auth-expiry 链路 / LocalCache 竞态 / 子页面 action 下沉 / FileOps 合并 / 全局单例治理 / HttpUtil / 死代码 / 服务端一致性
- **稳定性**（2026-06）：crash 兜底体系 + CoroutineExceptionHandler 全覆盖 + 静默 catch 审计
- **重构到基本可用**（2026-06）：协议重写（PacketBuffer/ProtoCodec 体系）+ 文档体系 v1
