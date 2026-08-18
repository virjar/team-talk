# ROADMAP — 路线图与待办

> 状态标记：✅ 完成 · 📋 计划 · 💡 想法。已完成的移入底部里程碑，本表只留**有效待办**。

---

## P0.5 — 管理后台（已完成 2026-08，MVP）

✅ React SPA（admin/ 第四端）+ 同进程 REST + 固定凭据；五页面（Dashboard/用户/
消息审查/日志/群）+ 封禁 enforcement 全链路。V2 遗留：审计日志/图表/公告/敏感词。

## P1 — 无头 IM 与 AI 接入（基础设施就绪，方向暂缓）

> 2026-08-17 用户决策：AI 员工暂缓（IM 主线优先）。基础设施四期已收官且保持可用状态。

| 项 | 说明 | 状态 |
|----|------|------|
| ~~CLI/AI 员工基础设施~~ | ✅ 2026-08 四期收官（doc/11-cli-agent）：tt-agent 守护进程（REST 22 端点+凭据持久化+环形缓冲）/ tt-cli 24 命令 / systemd 服务化（Linux 部署实测）/ tt-mcp（12 工具 MCP server）/ CliPeer e2e（测试路径=产品路径） | ✅ |
| AI 员工对话框架 | ImBot/agent 之上：消息→LLM→回复循环抽象（限速/会话白名单/人格配置/多 bot 编排）。基础设施已就绪，LLM 接入即可 | 💡 |
| Webhook/HTTP 桥 | agent REST 已覆盖收发（外部系统直接调 REST 即为 HTTP 桥）；缺 webhook 推送订阅 | 💡 |
| 群机器人管理 | bot 入群/踢出/权限 API 化（group-create/invite 已有，缺踢出/角色） | 💡 |
| agent TCP 半开窗口 | 长跑 agent 跨服务端重启存在 send 等超时窗口（read-idle 探测后自治重连自愈）；可缩短 PING 周期或发送侧快速失败 | 📋 |

## P2 — SDK 完善

| 项 | 说明 | 状态 |
|----|------|------|
| ~~发送队列与重试~~ | ✅ 2026-08：SendQueue（串行 worker + Channel 唤醒，FIFO 保序，QUEUED 状态机回写本地缓存，毒丸 30s 隔离）——SendQueueE2eTest：断线发送→QUEUED→重连补发→对端收达 | ✅ |
| 离线补发分页 | 服务端 `getEventsAfter` 单次 limit=100：长时间离线需多轮重连逐批补全（游标推进天然支持）；或协议升级分页游标 | 📋（低优先，已知限制） |
| 消息本地全文搜索 | 客户端 SQLite FTS（当前搜索纯服务端 Lucene，离线不可用） | 💡 |
| ~~AUTH_FAILED 无限重试失效 token~~ | ✅ 2026-08：authTerminal 终态（认证失败后停止自动重连，用户主动 login/register/authenticate 时重置；AuthTerminalE2eTest 锁定恰好失败一次） | ✅ |

## P3 — 服务端

| 项 | 说明 | 状态 |
|----|------|------|
| ~~ContactService.accept 全表扫~~ | ✅ 2026-08：Repository.getFriend(uid, friendUid) 单行直查，accept 通知两次查询各扫一次全列表的历史 | ✅ |
| 错误码国际化 | wire 错误已是分层码（400/401/500/504），剩余：message 中文串 → code 枚举 + 客户端本地化文案表 | 📋 |
| ChatStore.maxSeq 崩溃窗口 | 内存自增异步落库，崩溃丢增量致重启 seq 回退（updateMaxSeq 带 `< seq` 保护兜底）。WAL/同步刷盘权衡 | 💡 |

## P4 — UI 迭代（独立课题，SDK 之外）

| 项 | 状态 |
|----|------|
| ~~登录窗口/注册/二级子窗口按设计规范重排（doc/04-ui-design §2.3/2.6）~~ | ✅ 2026-08：登录窗口无装饰化（420×480/注册 560，窗口即卡片，windowStyle 变体）；子窗口统一宽 460 + 返回键 + ESC 逐级返回 |
| ~~暗色模式全页面走查~~ | ✅ 2026-08：应用内「外观」三态切换（跟随系统/浅色/深色，双端持久化）+ dark-* 七页截图走查 + `-Dteamtalk.theme` dev 强制参数 |
| Android 端专项打磨 | 📋 通讯录搜索/拼音分组/字母索引 ✅（双端共享 ContactsListScreen）；剩余：触控细节长尾、Android E2E T01-T34 |
| ~~桌面端导航独立重构（替换手搓 currentScreen 枚举，AppState 遗留清理）~~ | ✅ 2026-08：SubScreen 参数化 + DesktopNav（面板栈/子窗口局部栈）+ SubScreenContent 单渲染器；平行参数字段与死字段清除 |
| ~~Desktop 子窗口 ESC 关闭不可靠 / TestHttpServer 窗口语义 owner 泄漏~~ | ✅ 2026-08：AWT KeyEventDispatcher 层拦截（F31 根因）+ 窗口注册 onDispose 兜底注销 |
| F13 长按消息弹系统文本菜单（MessageBodyRenderer Text 被系统选择拦截 onLongClick） | 📋 |
| ~~Desktop 开发测试代码与发布包源码级隔离~~ | ✅（发布打包时物理移除 TestHttpServer） |
| Android E2E 全流程 T01-T34 剩余用例 | 📋 |
| ~~服务端视频缩略图生成~~ ✅（2026-08：javacv JNI 抽帧+元数据，图片 Java2D；客户端缓存体系+气泡缩略图数据源+画廊按需加载落地） | ✅ |
| ~~拖拽发送~~ ✅（用户实测通过：拖图即占位+进度+送达；语音/文件同链路） | ✅ |
| ~~Android 端媒体缓存体系接入~~ ✅（Coil 全局磁盘 LRU 250MB；发送端 uploadWithMeta 服务端元数据，视频本地抽帧兜底） | ✅ |
| 视频画廊/语音引擎上游缺陷跟踪（compose-media-player 0.9 对音频-only 不上报 duration、不触发 onPlaybackEnded、isPlaying 不回落——已用墙钟兜底，上游修复后可移除） | 📋 |
| currentUser 非响应式（@Volatile userSession + StateFlow.value 直读，首帧后不刷新；需 AppDataState 暴露 Compose State） | 📋 |
| ~~桌面右键菜单~~ ✅（用户实测通过：右键弹菜单 + 拖选文字不受干扰） | ✅ |
| 桌面右键菜单选词闪烁（menuEpoch 重建清选区的视觉闪烁，需事件拦截级方案） | 📋 |
| ~~消息正文列长度上限 500~~ ✅（2026-08：根因是 Conversations.lastMessage/draft varchar(500)，预览与草稿写入口截断 400 字符；消息体本体在 RocksDB 无限制。LongMessageE2eTest 锁定 2400 字符收发） | ✅ |

## 已完成里程碑（倒序）

- **发送队列与断线重试**（2026-08）：SendQueue 落地——断线发送不再立即失败，QUEUED 态排队（气泡显示「排队中」），重连自动按序补发；queue 生命周期随 ClientSession（close 级联）；e2e 全链路锁定
- **SDK 稳定性+服务端优化**（2026-08）：AUTH_FAILED 终态化（失效 token 不再重连风暴，AuthTerminalE2eTest）+ ContactRepository.getFriend 单行直查（accept 通知去全表扫）；语音时长/气泡布局/草稿泄漏/媒体上传动画系列修复（F26-F30）
- **飞书风格设计系统落地**（2026-08）：doc/04-ui-design 设计事实源（令牌/组件/交互/占位清单）+ `Tk` 令牌体系（spacing/dimens/扩展色板，双端密度）+ 核心组件重造（squircle 头像/会话项时间戳+静音+选中态/气泡双方头像+指向角/输入区工具行左对齐+Enter 发送+已读水位线指示）+ 桌面细导航栏（56dp 图标式）+ TestHttpServer 截图遮挡修复（toFront）。顺带根治服务端 refresh 认证响应漏 username/name（自动登录后客户端身份为空 → '?' 头像，ReconnectE2eTest 加断言锁定）。截图迭代闭环跑通（UI 自动化造数据 → 目视验收）
- **P0/P1 收尾**（2026-08）：上传接口 Bearer accessToken 鉴权（X-Uid 伪造通道封死）+ 本地库 schema 迁移（.sqm + v1 遗库识别坑修复）+ CLI 入口（headlessDist：register/login/selftest + MSG 行协议，端到端实跑验证）。好友红点时序项核实后关闭（问题不成立，真相是当年 SQL 直插无事件推送）
- **SDK 完整性收官**（2026-08）：重连三 bug 根治（B10 重放注册掉线 / B11 监听断链假活 / B12 补发游标快照）+ Presence 收敛 shared（契约登记）+ 事件流全家桶（contact/chat/presence）+ ImBot API 全量化（媒体/typing/撤回/群组/搜索）+ 测试体系（LocalCache/EventProcessor 单测 14 用例、bot 集成 9 用例、server 测试真实 PG 化去 embedded）
- **RPC IDL 化**（2026-08）：Kotlin interface = IDL + KSP2 生成 Contract/Stub/Proxy（精简版 gRPC）；serviceId 字符串化（协议 v2 一刀切）；手写 encodePayload/withPayload 根除（A3 坑根治）；uid 收敛 Stub 成员。演进方向：domain Service 直接实现 RpcStub 删薄壳层
- **文档体系深度重写**（2026-08）：wire 级协议规格 / 踩坑经验 40+ 条 / 设计理念 10 条，达到"凭文档可重写项目"标准
- **SDK 闭环**（2026-07）：shared=SDK 物理分层 + NOTIFY 契约表 + ImBot 无头入口 + bot 对 bot 验收
- **多设备同步**（2026-07）：readSeq 持久化断裂修复 + 会话行预创建 + peerReadSeq 持久化
- **技术债清理 9 连**（2026-07）：logout 凭证泄露 / auth-expiry 链路 / LocalCache 竞态 / 子页面 action 下沉 / FileOps 合并 / 全局单例治理 / HttpUtil / 死代码 / 服务端一致性
- **稳定性**（2026-06）：crash 兜底体系 + CoroutineExceptionHandler 全覆盖 + 静默 catch 审计
- **重构到基本可用**（2026-06）：协议重写（PacketBuffer/ProtoCodec 体系）+ 文档体系 v1
