# TeamTalk

TeamTalk 是一个面向中小型组织、可私有化并可深度定制的即时通讯与办公协作系统。项目使用 Kotlin
Multiplatform 与 Compose Multiplatform 构建 Android、Desktop 和无头 SDK 客户端，服务端
采用 Kotlin、Ktor 与 Netty。客户端、服务端、协议和部署工具位于同一个仓库，适合私有化部署和
二次开发。

> 官网：[im.virjar.com](https://im.virjar.com) ·
> 仓库：[github.com/virjar/team-talk](https://github.com/virjar/team-talk)

## 项目边界

TeamTalk 选择的是“可理解、可部署、可演进”的单体架构，目标规模是单组织万级用户，而不是
互联网级公共聊天平台。这个边界带来三项明确取舍：

- 服务端以单进程为主，避免把业务拆成需要复杂协调的微服务。
- PostgreSQL 保存关系数据，RocksDB 保存消息和文件，Lucene 提供全文搜索。
- 客户端采用本地优先模型：页面观察本地 SQLite，网络写入通过事件同步收敛到本地状态。

项目仍处于正式发布前的快速演进阶段。协议、数据库和客户端缓存可能发生不兼容调整；当前版本
适合开发、测试和私有化评估，不建议未经评审直接用于生产环境。内部开发从零号预览基线开始按
[版本与兼容规则](doc/04-protocol/versioning.md)维护短期兼容和数据迁移，普通升级保留既有资料。

## 核心能力

- 私聊与群聊，支持富文本、图片、语音、视频和文件消息。
- 回复、转发、编辑、撤回、已读水位和多设备会话同步。
- 联系人、好友申请、群成员、邀请链接、设备与在线状态管理。
- 服务端全文搜索与客户端全局搜索入口。
- 内嵌文件服务；附件始终由 TeamTalk 服务端管理，不依赖第三方对象存储。
- Android、macOS、Windows、Linux Desktop 客户端，以及可用于自动化和 AI 接入的无头 SDK。
- 可配置的私有化部署、管理后台、健康检查、客户端日志和真实部署验收。

能力的实现状态与已知缺口见[功能状态](doc/10-reference/feature-status.md)。

## 五分钟了解仓库

模块按架构语义分三个顶层组，Gradle 任务使用对应层级路径，例如 `:client:shared:jvmTest`：

```text
team-talk/
├── protocol/                 契约与传输
│   ├── protocol/             跨端 wire、模型、消息体、RPC IDL 与生成代码
│   ├── protocol-netty/       Netty 帧适配器
│   └── rpc-processor/        RPC IDL 的 KSP 代码生成器
├── client/                   客户端
│   ├── shared/               客户端 SDK：连接、事件、缓存、Repository、ImBot
│   ├── shared-testkit/       E2E 测试夹具（FakeLocalCache 等）
│   ├── richeditor/           Compose 富文本编辑器 fork
│   ├── app/                  Compose 共享 UI、ViewModel
│   ├── android/              Android 应用壳
│   └── desktop/              Desktop 窗口与系统集成
├── server/                   服务端
│   ├── server/               Ktor + Netty 单体服务端
│   └── admin/                管理后台前端
├── buildSrc/                 部署配置解析、构建与发布任务
├── scripts/                  验收脚本与 e2e 自动化辅助
└── doc/                      产品、架构、运维、开发和测试文档；design/ 保存品牌与图标素材
```

`shared-testkit` 是 E2E 测试夹具模块，仅供测试源集复用；不得进入任何产品 main 配置。
richeditor 是上游 compose-rich-editor 的受控 fork，注释不翻译以最小化上游 diff。

依赖方向保持单向：

```text
android / desktop ──▶ app ──▶ shared ──▶ protocol-netty ──▶ protocol
                         server ─────────▶ protocol-netty ──▶ protocol
                    rpc-processor ──▶ 为 protocol 生成 RPC 代码
```

## 快速开始

### 前置条件

- JDK 17
- Node.js 24 LTS 与 npm（本地也支持 Node.js 22.12+ 的 22 LTS；Server 资源图会从锁文件构建 Admin）
- Docker（本地 PostgreSQL）
- Android Studio（仅 Android 开发需要）

### 启动 Desktop 客户端

客户端默认读取 [`gradle/deployment.json`](gradle/deployment.json) 中的公开服务器坐标：

```bash
./gradlew :client:desktop:run
```

### 启动本地服务端

```bash
docker compose up -d
./gradlew :server:server:run
```

本地服务端和客户端的完整配置、数据目录与调试方法见[开发环境](doc/01-getting-started/development.md)。

小范围技术体验的范围与分发前检查见[开发者预览版指南](doc/01-getting-started/developer-preview.md)。

### 常用验证

```bash
./gradlew :protocol:protocol:jvmTest :protocol:protocol-netty:jvmTest :client:shared:jvmTest
./gradlew :server:server:test
./gradlew :client:app:desktopTest :client:desktop:desktopTest
./gradlew :client:desktop:compileKotlinDesktop
./gradlew :server:server:acceptanceTest
```

本地测试负责协议、算法和确定性边界；跨客户端业务流程以配置服务器上的真实验收为准。测试分层
见[测试策略](doc/09-testing/README.md)。

## 私有化部署

fork 项目后修改 [`gradle/deployment.json`](gradle/deployment.json) 中的 HTTP、TCP 和 SSH
坐标，并在本地或 CI 中提供不入库的 `gradle/deployment.secrets`。标准部署流程为：

```bash
./gradlew verifyRelease
./gradlew deployServer \
  -PsslCert=/secure/teamtalk/fullchain.pem \
  -PsslKey=/secure/teamtalk/privkey.pem
./gradlew :server:server:acceptanceTest
./gradlew releaseClients
```

上述示例使用当前远程客户端已经接通的 HTTPS + TLS/TCP 路径；首次 HTTPS 部署需要成对证书参数，
升级可同时省略以保留现有证书。服务运行时、SDK 与部署工具对明文和证书的支持尚未完全一致，
具体见[传输配置边界](doc/07-operations/configuration.md#传输配置边界)。
配置字段、安全边界、首次安装与升级流程见[私有化部署](doc/01-getting-started/private-deployment.md)。

## 架构摘要

TeamTalk 有三条用途不同的数据通道：

| 通道 | 传输 | 用途 |
|---|---|---|
| 实时命令 | TCP `INVOKE/RESPONSE` | 用户、联系人、群、会话等 RPC |
| 消息与事件 | TCP `MESSAGE/ACK/NOTIFY` | 消息发送、实时推送和离线补发 |
| 大数据与运维 | HTTP(S) | 文件上传下载、健康检查、管理后台和日志上传 |

TLS 是传输配置；当前客户端和部署组合的边界见上面的配置说明。

一次消息写入会经过 SDK 校验、TCP 认证门禁、服务端成员与附件校验、幂等落库、事件持久化、
多端推送和客户端本地缓存更新。完整链路见[系统架构](doc/03-architecture/README.md)与
[消息生命周期](doc/03-architecture/data-and-sync.md)。

## 文档入口

完整文档从 [`doc/README.md`](doc/README.md) 开始。常用入口：

| 你要做什么 | 从这里开始 |
|---|---|
| 第一次运行项目 | [快速上手](doc/01-getting-started/README.md) |
| 了解产品概念与能力边界 | [产品与领域](doc/02-product/README.md) |
| 判断为什么选择 TeamTalk | [与飞书、钉钉、企业微信的选择逻辑](doc/02-product/why-teamtalk.md) |
| 理解模块、数据流和可靠性 | [系统架构](doc/03-architecture/README.md) |
| 对接 SDK 或实现其他语言客户端 | [协议与契约](doc/04-protocol/README.md) |
| 修改 Desktop、Android 或富文本体验 | [客户端](doc/05-clients/README.md) |
| 修改领域服务或存储 | [服务端](doc/06-server/README.md) |
| 部署、监控和排障 | [运维](doc/07-operations/README.md) |
| 增加 RPC、消息类型或业务能力 | [开发与扩展](doc/08-development/README.md) |
| 运行本地或真实部署测试 | [测试与验收](doc/09-testing/README.md) |
| 查状态、术语和路线图 | [参考资料](doc/10-reference/README.md) |

## 参与开发

提交变更前请先阅读[仓库导览](doc/08-development/repository-guide.md)、
[工程约束](doc/08-development/engineering-rules.md)和
[变更指南](doc/08-development/change-guides.md)。使用 AI 参与开发时还应遵守
[AI 协作约定](AGENTS.md)。文档应描述稳定的产品或系统事实；开发过程、
临时结论和待办必须进入提交记录、任务系统或路线图，不能继续堆进架构正文。

## 开源许可

除另有标注的第三方组件外，TeamTalk 自有代码与文档依据
[Apache License 2.0](LICENSE) 发布。该许可证允许使用、修改、分发和商业应用，也允许闭源衍生；
再分发时须遵守许可证、归属与修改声明要求。第三方组件继续适用各自许可证。
