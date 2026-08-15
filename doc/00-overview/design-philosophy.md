# 设计理念

> TeamTalk 的每一条架构决策背后的"为什么"。理解这些理念，才能在扩展时做出一致的取舍。
> 对应的行为约束见 [CLAUDE.md](../../CLAUDE.md)（项目宪法）与 [架构总览](architecture.md)。

---

## 1. 模型确定性 > 灵活性

**决策**：IM 核心概念（认证、消息、好友、群、会话）全部走 TCP 二进制协议，字段顺序和类型编译期锁定；JSON 只出现在 HTTP 文件上传响应等边缘位置。

**为什么**：二进制协议的核心价值不是省流量，而是**模型确定性**。JSON 的"灵活"意味着多版本行为兼容是灾难——字段增删靠约定、类型漂移靠运行时爆炸、双方理解不一致只能靠人肉排查。本项目历史上最痛的一类 bug（[CONTACT_ACCEPTED 契约错配](../05-lessons/README.md)）正是"两端各自维护类型映射"的产物——修复方案不是更灵活的 JSON，而是**唯一事实源**（[NotifyContracts 契约表](../01-protocol/notify-contracts.md)）。

**推论**：
- 不兼容变更必须递增 `PROTOCOL_VERSION`（`shared/.../protocol/Frame.kt`）
- 新 RPC 方法 = 加枚举 + 两侧 handler，编译器保证引用一致
- 新 NOTIFY 类型必须登记契约表，否则完备性测试失败

## 2. 所有者驱动（Owner-Driven Model）

**决策**：每个对象有且仅有一个所有者；所有者销毁时其拥有的对象全部级联销毁；数据只能从所有者流向被拥有者。

**为什么**：状态驱动的系统里，"谁负责清理"没有明确答案时，泄漏和竞态是必然结果。本项目用一张三级状态表把所有权写死：

| 层级 | 所有者 | 持有内容 | 销毁时机 |
|------|--------|----------|----------|
| App 全局 | 进程 | ServerConfig、TokenStore、登录窗口 | 进程退出 |
| 用户层 | `UserSession` | uid/refreshToken/身份、`ClientSession`、ViewModel | AUTH_FAILED 或登出 |
| 连接层 | `ImClient` | TCP socket、pendingAcks、pendingAuth | TCP 断开（自动重连） |

关键推论：
- **TCP 断开不清用户层**——掉线不该丢登录态（历史上"断连清 uid 导致消息左右颠倒"的教训）
- **ViewModel 不自断连接**——认证失效统一上抛 `onAuthExpired` → `session.close()`，由会话所有者执行级联
- **全局单例必须随会话清理**——`AppLog` 的 buffer/onFault 由 createSession 注入、由 close 置空（曾经泄漏过 stale 引用）

## 3. 本地优先（Local-First）

**决策**：客户端所有页面从本地 SQLite 渲染；网络只用于写操作和事件同步。

**为什么**：UI 直接依赖网络的系统，弱网下每一帧都是加载态；且"服务端返回什么渲染什么"会让服务端字段波动直接击穿 UI。本地 DB 是一层稳定的防波堤。

**推论**：
- ViewModel 不直接调网络渲染 UI（写操作除外）
- 每个服务端数据变更必须推对应 NOTIFY（[事件矩阵](../02-server/README.md#事件发射矩阵)）
- 读路径双通道：RPC 拉取（写 DB）+ NOTIFY 推送（写 DB），UI 只观察 DB

## 4. 认证失效停而非重试

**决策**：`AppError.AuthExpired` 永不重试，直接登出到登录页。

**为什么**：token 失效后重试只会得到一连串 401，用户卡在"转圈但永远不成功"的假死状态。停下来让用户重新认证是唯一正确的终点。

## 5. 单体 + 单机 + 内存模型

**决策**：服务端单体架构（Ktor + Netty 单进程），PostgreSQL + RocksDB + Lucene 单机部署，无消息队列、无微服务。

**为什么**：目标规模是中小组织（≤1万用户），2核4G 单机即可支撑。分布式带来的复杂度（一致性、运维、排障）在这个规模下是纯负担。领域层内部保持 DDD 分层（Service→Store→Repository），未来需要拆分时边界是现成的。

## 6. SDK 严格分层（UI / SDK / Server）

**决策**：物理模块边界强制单向依赖 `shared(SDK) ← app(UI) ← android/desktop(shell)`。

**为什么**：曾经 client/repository（SDK 能力）和 UI 同模块，UI 代码可以直接摸 SDK 内部实现，协议层的 bug 以"UI 状态异常/崩溃"的形式被发现，排查要穿透 4 层才能定位到 RPC 未对齐。SDK 层必须**独立闭环测试**（[测试金字塔](../07-testing/README.md)），bug 在 SDK 层内暴露，不允许漏到 UI 集成时。

**推论**：
- shared 禁止 import 任何 Compose/UI
- 无头客户端（[ImBot](../03-sdk/imbot.md)）直接依赖 shared 运行——这也是 AI 员工/机器人接入的基础
- 反模块膨胀：不轻易新增 Gradle 模块，shared 就是 SDK

## 7. 不要过早实现 + 克制参数化

**决策**：没有实际调用方的功能不写（曾经删除过零注册方的 GenericDispatcher 扩展机制）；遇到"要不要加个开关"默认不加。

**为什么**：N 个布尔开关 = 2^N 种未测组合，分散在 BuildConfig/系统属性/运行时参数里，出 bug 时甚至无法确认产物用了什么组合。配置一律走 Profile 模板体系（[构建系统](build-system.md)），构建产物内嵌 git commit + build time 可溯源。

## 8. 已读/未读 = 可合并的单调水位线

**决策**：readSeq / peerReadSeq 是只增不减的水位线，取 max 即合并；unreadCount 由服务端权威计算（`lastSeq - readSeq`）下发。

**为什么**：已读本质是一种**可合并的事件**——"读到 seq=N"蕴含"读到所有 ≤N"。如果用不可合并的状态（如本地计数未读数），多设备同步就是灾难（曾经换设备登录全部未读）。水位线模型下：
- 服务端 markRead 持久化是唯一权威
- 客户端合并规则极简：`readSeq = max(local, remote)`
- 多设备、离线补发、乱序到达全部天然正确

## 9. 契约优先（Contract-First）

**决策**：跨端类型映射不允许两端手写 `when` 分支各自维护；必须存在唯一事实源 + 两侧运行时/测试期校验。

**为什么**：见理念 1 的 CONTACT_ACCEPTED 案例。系统性解法是三层防线：
1. **唯一事实源**：`NotifyContracts` 表
2. **服务端 emit 前校验**：类型错配当场抛异常（测试期失败）
3. **契约测试**：完备性 + round-trip + 类名解析

任何"两端各自维护的平行结构"都是未来的契约 bug。

## 10. 崩溃兜底与静默 catch 审计

**决策**：所有协程 scope 挂 CoroutineExceptionHandler → `logUnhandledError()`（stderr + CrashDumper 原子落盘）；每一个静默 catch 必须能回答"为什么这里的吞掉是安全的"。

**为什么**：IM 客户端的崩溃现场无法复现（弱网/时序相关），落盘 + HTTP 上传（TCP 断了也能传，[日志体系](../06-logging/README.md)）是唯一的排障入口。静默 catch 是 bug 的温床——要么记日志，要么注释说明安全理由。
