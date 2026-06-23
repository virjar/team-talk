# TeamTalk 文档

> TeamTalk — 面向中小组织的全栈 Kotlin IM 系统（Android + Desktop + Server）。

## 文档索引

### 模块文档（多级目录，详细设计）

| 文档 | 内容 | 适合谁读 |
|------|------|---------|
| [**01-protocol/**](01-protocol/) | 协议设计：帧格式、RPC、NOTIFY、心跳、编解码、认证、错误码、消息类型 | 理解通信机制 |
| ├ [README.md](01-protocol/README.md) | 协议总览（为什么不用Protobuf / 帧格式 / 包类型 / RPC / 心跳 / ProtoCodec） | |
| ├ [authentication.md](01-protocol/authentication.md) | 认证体系（Token方案 / AUTH流程 / 三级状态） | |
| ├ [errors.md](01-protocol/errors.md) | RPC 错误码体系（400/401/500/504 + AppError映射） | |
| └ [message-types.md](01-protocol/message-types.md) | 消息类型与消息体（15种类型 / MessageBody多态 / flags位标记 / 渲染策略） | |
| [**02-server/**](02-server/) | 服务端架构：领域层、协议层、存储、搜索、事件同步 | 后端开发 |
| ├ [README.md](02-server/README.md) | 服务端总览（DDD领域层 / 协议层状态机 / 基础设施 / 启动流程） | |
| ├ [threading.md](02-server/threading.md) | 线程模型（EventLoop/IOExecutor / ImAgentFacade GC安全 / 采样日志） | |
| ├ [file-storage.md](02-server/file-storage.md) | 文件存储与传输（双层存储 / HTTP接口 / 拖拽文件 / 本地缓存优先） | |
| └ [fulltext-search.md](02-server/fulltext-search.md) | 全文搜索（Lucene + IK中文分词 / 索引设计 / 搜索范围） | |
| [**03-client/**](03-client/) | 客户端架构：本地优先、连接管理、状态合并、导航、草稿未读 | 前端/跨平台 |
| ├ [README.md](03-client/README.md) | 客户端总览（本地优先 / ImClient / LocalCache / 状态合并 / 导航 / 消息渲染） | |
| ├ [module-structure.md](03-client/module-structure.md) | 完整文件树（commonMain/Android/Desktop 各目录文件清单） | |
| └ [draft-and-unread.md](03-client/draft-and-unread.md) | 草稿与未读管理（草稿持久化 / 未读计数合并 / 多设备同步 / Badge显示） | |
| [**04-shared/**](04-shared/) | 共享 SDK：数据模型、协议枚举、ImClient、日志抽象 | 所有开发者 |
| └ [README.md](04-shared/README.md) | 共享模块（数据模型 / 消息体 / 协议枚举 / ImClient / TkLogger注入） | |
| [**05-logging/**](05-logging/) | 日志体系：trace/fault/snapshot 分级、HTTP 上传、Crash 持久化 | 排查问题 |
| └ [README.md](05-logging/README.md) | 日志全貌（分级策略 / LocalLogFile / HttpLogUploader / CrashDumper / 移除logback） | |
| [**06-testing/**](06-testing/) | E2E 测试：AI 驱动方法、TestHttpServer、testTag 参考 | QA / 测试 |
| ├ [README.md](06-testing/README.md) | 测试理念 + TestHttpServer + TestPeer + 6条约束 | |
| ├ [test-tags.md](06-testing/test-tags.md) | testTag 完整参考表（按页面分组） | |
| ├ [test-cases.md](06-testing/test-cases.md) | T01-T34 测试用例清单 + 结果 | |
| └ [ai-workflow.md](06-testing/ai-workflow.md) | AI驱动工作流（Desktop/Android/TestPeer 操作方法） | |
| [**07-conventions/**](07-conventions/) | 编码规范：println 禁令、RPC 配对、状态管理 | 所有贡献者 |
| └ [README.md](07-conventions/README.md) | 6大约束（println / RPC / 状态合并 / Compose状态 / 共享边界 / 文件大小） | |

### 总览文档

| 文档 | 内容 | 适合谁读 |
|------|------|---------|
| [**00-overview/**](00-overview/) | 总览性文档 | 所有人 |
| ├ [architecture.md](00-overview/architecture.md) | 架构总览（原则总表 + 系统图 + 模块索引） | 新人入门 |
| ├ [build-system.md](00-overview/build-system.md) | 构建系统与 Profile 体系（JSON Profile + 私有化部署 + CI/CD） | 运维/构建 |
| ├ [architecture-comparison.md](00-overview/architecture-comparison.md) | 与 Signal/Telegram/WuKongIM/OpenIM 横向对比 | 架构决策 |
| ├ [ROADMAP.md](00-overview/ROADMAP.md) | 功能路线图与完成状态 | 所有人 |
| ├ [getting-started/develop.md](00-overview/getting-started/develop.md) | 开发环境搭建 | 新人入门 |
| └ [getting-started/deploy.md](00-overview/getting-started/deploy.md) | 部署指南 | 运维 |

## 快速入门

1. **新人**：先读 [00-overview/architecture.md](00-overview/architecture.md) 了解全貌 → [getting-started/develop.md](00-overview/getting-started/develop.md) 搭建环境
2. **贡献代码**：必读 [07-conventions/](07-conventions/) 编码规范
3. **排查问题**：读 [05-logging/](05-logging/) 理解日志体系
4. **理解通信**：读 [01-protocol/](01-protocol/) 协议设计
5. **跑测试**：读 [06-testing/](06-testing/) 测试方法 + testTag 参考
