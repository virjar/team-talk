# TeamTalk 文档中心

这里是 TeamTalk 产品与工程知识的统一入口。文档面向四类读者：准备私有化部署的使用者、
参与客户端或服务端开发的贡献者、对接 SDK/协议的集成方，以及负责测试和运维的维护者。

文档按稳定的知识边界组织，不按某次需求、某轮 UI 修改或某个历史问题建立顶级章节。

## 从你的目标开始

| 目标 | 推荐阅读路径 |
|---|---|
| 组织少量开发者内测 | [预览版范围与最小验收](01-getting-started/developer-preview.md) |
| 先把项目运行起来 | [快速上手](01-getting-started/README.md) → [开发环境](01-getting-started/development.md) |
| fork 后部署自己的服务器 | [私有化部署](01-getting-started/private-deployment.md) → [运行配置](07-operations/configuration.md) → [部署与升级](07-operations/deployment.md) |
| 判断项目是否适合业务 | [产品定位](02-product/README.md) → [为什么是 TeamTalk](02-product/why-teamtalk.md) → [能力模型](02-product/capabilities.md) → [功能状态](10-reference/feature-status.md) |
| 接手项目、按源码掌握状态与恢复 | [架构入门与阅读练习](03-architecture/architecture-primer.md) → [客户端所有权图](03-architecture/client-and-sdk.md) → [路线图交接切片](10-reference/roadmap.md) |
| 理解一次消息如何流转 | [系统架构](03-architecture/README.md) → [数据与同步](03-architecture/data-and-sync.md) → [消息与附件](04-protocol/messages-and-attachments.md) |
| 编写另一个客户端或 SDK | [协议总览](04-protocol/README.md) → [Wire Format](04-protocol/wire-format.md) → [RPC 与事件](04-protocol/rpc-and-events.md) |
| 修改 Desktop 或 Android | [客户端架构](05-clients/README.md) → 对应平台文档 → [设计系统](05-clients/design-system.md) |
| 增加一个业务能力 | [仓库导览](08-development/repository-guide.md) → [变更指南](08-development/change-guides.md) → [测试策略](09-testing/README.md) |
| 排查线上问题 | [可观测性](07-operations/observability.md) → [故障排查](07-operations/troubleshooting.md) |

## 文档地图

### 01 · 上手与部署

从零开始运行、开发和私有化部署，不解释所有内部实现。

- [章节导览](01-getting-started/README.md)
- [开发环境](01-getting-started/development.md)
- [开发者预览版与小范围内测](01-getting-started/developer-preview.md)
- [私有化部署](01-getting-started/private-deployment.md)

### 02 · 产品与领域

说明 TeamTalk 解决什么问题、核心对象是什么、各能力如何组合，以及明确不解决什么。

- [产品定位](02-product/README.md)
- [为什么是 TeamTalk：与主流办公平台的选择逻辑](02-product/why-teamtalk.md)
- [领域模型](02-product/domain-model.md)
- [能力模型](02-product/capabilities.md)

### 03 · 系统架构

解释模块边界、所有权、数据流、同步语义、可靠性模型和关键取舍。

- [架构入门（新维护者首读）](03-architecture/architecture-primer.md)
- [架构总览](03-architecture/README.md)
- [客户端与 SDK](03-architecture/client-and-sdk.md)
- [服务端运行时](03-architecture/server-runtime.md)
- [数据与同步](03-architecture/data-and-sync.md)
- [架构决策](03-architecture/decisions.md)

### 04 · 协议与契约

面向 SDK 开发者的规范。这里描述“线上字节和行为必须是什么”，代码生成物与共享模型是实现侧
事实源。

- [协议总览](04-protocol/README.md)
- [版本、兼容窗口与数据演进](04-protocol/versioning.md)
- [Wire Format](04-protocol/wire-format.md)
- [RPC 与事件](04-protocol/rpc-and-events.md)
- [消息与附件](04-protocol/messages-and-attachments.md)
- [认证与错误](04-protocol/authentication-and-errors.md)

### 05 · 客户端

解释共享 UI 与平台壳的边界、Desktop/Android 交互模型、设计语言和富文本体系。

- [客户端总览](05-clients/README.md)
- [Desktop](05-clients/desktop.md)
- [Android](05-clients/android.md)
- [无头客户端](05-clients/headless.md)
- [设计系统](05-clients/design-system.md)
- [富文本与媒体](05-clients/rich-content.md)

### 06 · 服务端

解释领域服务、持久化、文件、搜索和管理后台，不与部署操作混写。

- [服务端总览](06-server/README.md)
- [领域服务](06-server/domain-services.md)
- [持久化](06-server/persistence.md)
- [文件存储](06-server/file-storage.md)
- [搜索与管理](06-server/search-and-admin.md)

### 07 · 运维

面向部署维护者的配置、发布、日志、健康检查和排障手册。

- [运维总览](07-operations/README.md)
- [运行配置](07-operations/configuration.md)
- [部署与升级](07-operations/deployment.md)
- [统一发行流程](07-operations/releasing.md)
- [Desktop 交叉打包与签名](07-operations/desktop-cross-build.md)
- [可观测性](07-operations/observability.md)
- [故障排查](07-operations/troubleshooting.md)

### 08 · 开发与扩展

面向贡献者的仓库导航、强约束和常见扩展流程。

- [开发总览](08-development/README.md)
- [仓库导览](08-development/repository-guide.md)
- [工程约束](08-development/engineering-rules.md)
- [变更指南](08-development/change-guides.md)
- [依赖维护与工具链升级](08-development/dependency-maintenance.md)

### 09 · 测试与验收

区分本地确定性测试、SDK 集成测试、真实部署业务验收和客户端交互验证。

- [测试策略](09-testing/README.md)
- [本地测试](09-testing/local-tests.md)
- [真实部署验收](09-testing/deployment-acceptance.md)
- [Desktop 自动化](09-testing/desktop-automation.md)
- [业务场景目录](09-testing/scenario-catalog.md)

### 10 · 参考资料

存放需要快速查询或会随版本变化的内容，避免污染稳定的设计正文。

- [参考入口](10-reference/README.md)
- [功能状态](10-reference/feature-status.md)
- [RPC 参考](10-reference/rpc-reference.md)
- [事件参考](10-reference/event-reference.md)
- [测试选择器](10-reference/test-selectors.md)
- [术语表](10-reference/glossary.md)
- [路线图](10-reference/roadmap.md)

## 权威边界

同一个事实只能有一个权威来源。其他文档应链接，不应复制维护。

| 事实 | 权威来源 |
|---|---|
| 产品定位、能力边界 | `02-product/` |
| 模块职责、所有权与数据流 | `03-architecture/` |
| 线上帧、字段顺序、认证和事件语义 | `04-protocol/` + `protocol` 模块中的协议/IDL 代码 |
| Desktop/Android 交互和视觉规则 | `05-clients/` |
| 服务端领域与存储实现 | `06-server/` |
| 环境变量、目录、部署和排障 | `07-operations/` |
| 编码约束与扩展步骤 | `08-development/` |
| 测试分层和验收流程 | `09-testing/` |
| 当前完成度、缺口和计划 | `10-reference/` |

代码与文档冲突时：协议字段和 ID 以 `protocol` 中的枚举、模型和生成 Contract 为准；构建任务以
Gradle 为准；运行配置以 `buildSrc`、`gradle/deployment.json` 和服务端环境读取代码为准。
发现冲突必须同时修正文档，不能用“以后再更新”作为长期状态。

## 文档写作规则

1. **按知识归属写，不按需求归属写。** “新增群设置抽屉”应更新 Desktop 交互文档，不新建一个
   顶级课题目录。
2. **设计、操作、参考、状态分开。** 架构正文解释为什么和如何工作；操作手册给步骤；参考表供
   查询；路线图只记录未完成事项。
3. **正文使用现在时。** “本轮改了”“曾经出过问题”属于提交记录；只有理解取舍确实必要时，
   才在架构决策中保留背景。
4. **链接到权威来源。** 不在 README、AGENTS.md 和分册中复制同一张完整表。
5. **状态必须可验证。** 功能状态使用“已实现/部分实现/未实现”，并给出代码或测试入口。
6. **示例不包含秘密。** 公开坐标可引用 `deployment.json`，口令、私钥和真实 token 只能写成占位符。
7. **结构变更要校验链接。** 移动文档后必须扫描仓库内 Markdown 链接和旧路径引用。

## 生命周期

- 稳定设计变化时，修改对应权威文档。
- 新功能未完成前写入[路线图](10-reference/roadmap.md)，完成后迁入产品/架构正文并更新
  [功能状态](10-reference/feature-status.md)。
- 重大且难以从代码推断的取舍写入[架构决策](03-architecture/decisions.md)。
- 临时调查、截图批次、故障现场和 AI 工作记录不进入主文档；它们应留在任务、提交或外部知识库。
