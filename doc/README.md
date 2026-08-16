# TeamTalk 文档

> 面向中小组织的全栈 Kotlin IM：Android + Desktop + Server + **IM SDK + 无头 bot**。
> 文档目标：**据此可完全重写项目达到当前状态**——架构、wire 级协议规格、边界约束、踩坑经验全部结构化沉淀。

---

## 阅读路径

| 你是谁 | 路径 |
|--------|------|
| 新成员 | [架构总览](00-overview/architecture.md) → [设计理念](00-overview/design-philosophy.md) → **[踩坑经验](05-lessons/README.md)** → [开发上手](00-overview/getting-started/develop.md) |
| 改协议 | [wire-format](01-protocol/wire-format.md) → [rpc-methods](01-protocol/rpc-methods.md) → [notify-contracts](01-protocol/notify-contracts.md) |
| 改服务端 | [server README](02-server/README.md) → [database](02-server/database.md) → [threading](02-server/threading.md) |
| 改 SDK | [sdk README](03-sdk/README.md) → [imclient](03-sdk/imclient.md) → [local-cache](03-sdk/local-cache.md) |
| 改 UI | [设计系统](04-ui-design/README.md) → [令牌](04-ui-design/design-tokens.md) → [组件规格](04-ui-design/components.md) → [占位清单](04-ui-design/placeholders.md) |
| 写 bot / AI 接入 | [imbot](03-sdk/imbot.md) → [roadmap P1](09-roadmap.md) |
| 排查线上问题 | [日志体系](06-logging/README.md) → [测试与 E2E](07-testing/README.md) |
| 构建/部署 | [build-system](00-overview/build-system.md) → [deploy](00-overview/getting-started/deploy.md) |

## 目录

### 00-overview — 总览与理念
| 文档 | 内容 |
|------|------|
| [architecture.md](00-overview/architecture.md) | 系统组成/三条数据通道/发消息全链路/三级状态/依赖图 |
| [design-philosophy.md](00-overview/design-philosophy.md) | 10 条设计决策及其"为什么"（模型确定性/Owner-Driven/本地优先/水位线/契约优先…） |
| [build-system.md](00-overview/build-system.md) | Profile 构建体系/多渠道/CI |
| [getting-started/develop.md](00-overview/getting-started/develop.md) | 开发环境 |
| [getting-started/deploy.md](00-overview/getting-started/deploy.md) | 部署指南（deployServerDemo 一键） |
| [architecture-comparison.md](00-overview/architecture-comparison.md) | vs Signal/Telegram/开源 IM |

### 01-protocol — 协议规格（wire 级）
| 文档 | 内容 |
|------|------|
| [wire-format.md](01-protocol/wire-format.md) | **帧布局/PacketBuffer 原语表/全部 payload+模型字段布局/心跳/错误分层** |
| [rpc-methods.md](01-protocol/rpc-methods.md) | 全部 RPC 方法矩阵（请求布局/权限/事件）+ Repository 映射 |
| [notify-contracts.md](01-protocol/notify-contracts.md) | **契约表机制**（唯一事实源/三层防线/视角规则）+ 18 契约清单 |
| [authentication.md](01-protocol/authentication.md) | 认证体系（token 一次一换/三级状态） |
| [errors.md](01-protocol/errors.md) | 错误码体系 |
| [message-types.md](01-protocol/message-types.md) | 消息类型与渲染策略 |

### 02-server — 服务端
| 文档 | 内容 |
|------|------|
| [README.md](02-server/README.md) | 启动序列/TCP 管线/领域服务规则/**事件发射矩阵**/存储分工/DI 图 |
| [database.md](02-server/database.md) | 11 张表全 schema + 不变量（水位线/事件双索引模型） |
| [threading.md](02-server/threading.md) | 线程模型（EventLoop/IOExecutor/Looper） |
| [file-storage.md](02-server/file-storage.md) | 分层文件存储 |
| [fulltext-search.md](02-server/fulltext-search.md) | Lucene + IK 搜索 |

### 03-sdk — IM SDK（shared）
| 文档 | 内容 |
|------|------|
| [README.md](03-sdk/README.md) | SDK 组装/级联销毁/EventProcessor/Repository 模式/测试闭环 |
| [imclient.md](03-sdk/imclient.md) | 连接状态机/重连/心跳/**防御设计↔历史 bug 对照表** |
| [local-cache.md](03-sdk/local-cache.md) | stateLock 纪律/消息窗口 LRU/会话合并策略 |
| [imbot.md](03-sdk/imbot.md) | 无头客户端（AI bot/CLI 入口）+ 集成测试 |

### 04-ui-design — UI 设计系统（飞书风格）
| 文档 | 内容 |
|------|------|
| [README.md](04-ui-design/README.md) | 为什么选飞书/设计原则/截图迭代闭环 |
| [design-tokens.md](04-ui-design/design-tokens.md) | **令牌总表**（颜色/字阶/间距/圆角/尺寸，代码 `Tk` 对象对照） |
| [components.md](04-ui-design/components.md) | 组件规格（头像/会话项/气泡/输入区/导航栏）+ 页面布局 + 交互规范 |
| [placeholders.md](04-ui-design/placeholders.md) | 后端缺失占位清单（✅可接/🟡半接/🔴缺口 + 补齐顺序） |

### 05-lessons — 踩坑经验
| 文档 | 内容 |
|------|------|
| [README.md](05-lessons/README.md) | **40+ 条真实坑**分 6 类（协议契约/认证连接/并发/数据一致性/服务端/UI-E2E），每条含症状→根因→固化 |

### 06~08 — 工程体系
| 文档 | 内容 |
|------|------|
| [06-logging/README.md](06-logging/README.md) | trace/fault/snapshot 分级 + HTTP 上传 + Crash 持久化 |
| [07-testing/README.md](07-testing/README.md) | 测试金字塔（单测/契约/SDK 集成/服务端 e2e/UI E2E） |
| [07-testing/ai-workflow.md](07-testing/ai-workflow.md) | AI 驱动 E2E 操作手册（Desktop TestHttpServer/Android uiautomator2/TestPeer） |
| [07-testing/test-cases.md](07-testing/test-cases.md) | T01-T34 用例清单 |
| [08-conventions/README.md](08-conventions/README.md) | 编码规范（println 禁令/RPC 配对/Compose 状态） |

### 09-roadmap — 未来
| 文档 | 内容 |
|------|------|
| [09-roadmap.md](09-roadmap.md) | P0 正确性收尾 / **P1 无头 IM 与 AI 员工** / P2 SDK / P3 服务端 / P4 UI |

### 10-rich-messaging — 富消息课题（调研与分期）
| 文档 | 内容 |
|------|------|
| [README.md](10-rich-messaging/README.md) | **markdown 录入/渲染选型**（mikepenz renderer + Slack 式输入）、RICH_TEXT 协议设计（mentions 侧信道）、卡片/指令分期 |

---

## 三份最重要的文档

1. **[踩坑经验](05-lessons/README.md)** —— 项目的隐性知识，防止重蹈覆辙
2. **[wire-format](01-protocol/wire-format.md)** —— 协议从零重写的完整依据
3. **[设计理念](00-overview/design-philosophy.md)** —— 所有边界约束的"为什么"
