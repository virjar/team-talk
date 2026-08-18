# TeamTalk 开发协作说明

本文件只保存参与开发时必须遵守的约束。产品、架构、协议、部署和测试细节统一从[文档中心](doc/README.md)进入，不在这里复制维护。

## 沟通与项目边界

- 使用中文沟通，先说明结论、证据和风险。
- 核心仓库是当前 `team-talk/`；同级目录仅用于研究和对照，不能直接修改或复制为产品事实。
- 项目处于发布前 AI 开发周期，允许有理由的破坏性重构和清理测试数据。
- Git 提交用于保存可恢复的工作状态；完成一个可验证阶段后主动提交，正式发布前再整理历史。
- 工作区可能包含他人的未提交改动。先检查状态，只修改任务相关内容，不覆盖或回退未知变更。

## 开始工作前

1. 阅读根 [README](README.md) 和与任务相关的文档章节。
2. 用代码和测试核对当前事实，不把旧注释或历史记录直接当设计。
3. 明确改动属于产品、协议、SDK、客户端、服务端、运维还是测试。
4. 涉及多个边界时，先写清数据所有者和完整链路，再开始实现。

常用导航：

- [系统架构](doc/03-architecture/README.md)
- [协议与契约](doc/04-protocol/README.md)
- [客户端](doc/05-clients/README.md)
- [服务端](doc/06-server/README.md)
- [开发与扩展](doc/08-development/README.md)
- [测试与验收](doc/09-testing/README.md)
- [功能状态](doc/10-reference/feature-status.md)

## 不可破坏的架构约束

### 单向分层

```text
android / desktop → app → shared
server → shared
rpc-processor → 编译期生成 RPC 契约代码
```

- `shared` 是完整 IM SDK，包含协议、连接、模型、缓存、Repository 和 ImBot，禁止依赖 Compose。
- `app` 是共享 UI 与 ViewModel，只通过 SDK 公开 API 工作。
- `android`、`desktop` 负责窗口、导航、系统媒体、下载和平台存储。
- SDK 问题应在 SDK 层形成测试闭环，不能靠 UI 规避。

### 单一所有者

每个状态和资源只有一个所有者：进程拥有应用级配置，UserSession 拥有用户会话，ImClient 拥有单次连接。销毁从所有者向下级联，数据不反向持有。

- TCP 短暂断开不能清除用户身份。
- 认证失效属于用户会话终态，应停止自动重连并回到登录。
- socket、pending ACK 和连接协程只属于 ImClient。
- Compose 可变状态由 ViewModel 或明确的导航所有者管理。

详见[客户端与 SDK](doc/03-architecture/client-and-sdk.md)。

### 本地优先

UI 观察 LocalCache，RPC 与 Notify 让缓存收敛。服务端仍是权限和共享数据的权威来源。

- 页面不为了渲染而各自直连网络建立第二套状态。
- 领域写操作必须有对应的缓存收敛路径。
- 水位、版本和序号采用单调合并，不用旧快照覆盖新状态。
- 乐观 UI 可以表达发送中，但成功必须由服务器 ACK 确认。

### 确定性协议

- 当前帧为 `TYPE(1B) + LENGTH(4B big-endian) + PAYLOAD`，协议版本为 4。
- RPC interface 是 IDL；方法只追加，稳定 `methodId` 不得重排。
- 新 NotifyType 必须登记 `NotifyContracts`，服务端和客户端共享 payload 契约。
- 新 MessageType 必须补 Body、Registry、策略、服务端校验、渲染和 round-trip 测试。
- 不兼容 wire 变更必须明确升级协议版本，不能静默猜测旧格式。

具体步骤见[变更指南](doc/08-development/change-guides.md)和[RPC 参考](doc/10-reference/rpc-reference.md)。

### 附件安全

TeamTalk 不把第三方 URL 当作文件消息事实。消息只保存 TeamTalk FileStore 的相对路径。

1. SDK 在出站前规范化附件结构并拒绝非法 URL、绝对路径和路径穿越。
2. 服务器在分配 seq 和返回成功 ACK 前校验文件、缩略图和元数据真实存在。
3. 客户端结合自身部署地址解析下载 URL。
4. ImBot 与图形客户端必须走同一校验链，不能因为是机器人就跳过安全规则。

“消息发送成功”必须代表附件已通过服务器权威校验。详见[消息与附件](doc/04-protocol/messages-and-attachments.md)。

## 客户端产品规则

- Desktop 和 Android 共享产品语义与设计令牌，但导航容器按平台习惯实现。
- Desktop 用户资料使用模态弹窗；群设置从聊天栏打开右侧临时检查器，点击外部、关闭按钮或适当的 ESC 路径可收回。
- 搜索是应用级能力；添加好友从用户资料发起，建群从好友资料或明确成员上下文发起。
- 文本消息默认使用 Markdown / `RICH_TEXT`；`TEXT` 只保留旧数据兼容。
- 底层尚未支持的入口可以保留明确空态，但不能用演示数据或假成功伪装实现。
- 新的关键交互必须提供稳定 `testTag`。

详见[Desktop 交互](doc/05-clients/desktop.md)、[设计系统](doc/05-clients/design-system.md)和[富文本与媒体](doc/05-clients/rich-content.md)。

## 服务端与配置规则

- 服务端保持模块化单体：传输层只做连接和解码，领域服务做权限与业务，Repository 做持久化。
- PostgreSQL 保存关系数据，RocksDB 保存消息、事件、token 和文件元数据，文件系统保存大文件，Lucene 保存可重建索引。
- 禁止在数据库、缓存和 UI 中各写一份相互竞争的业务规则。
- 私有化部署参数必须可配置，但不要为每个临时需求增加布尔开关和环境矩阵。
- 秘密进入 `gradle/deployment.secrets` 或运行时环境，不提交真实口令、token、证书和私钥。
- 当前 TCP 运行时仍实际使用 5100；完成配置接线前，文档和测试不得宣称任意 TCP 端口已生效。

## 日志与错误

- 生产代码不使用 `println` 记录业务日志；服务端使用 SLF4J，TCP trace 使用 Recorder，客户端使用统一日志接口。
- 不静默吞掉未知异常。能安全忽略的异常必须在局部说明原因。
- 日志不得输出密码、refresh token、Authorization、私钥或完整敏感正文。
- 认证错误不重试；业务错误不靠解析中文 message 判断；超时写操作要考虑服务器可能已经执行。

详见[可观测性](doc/07-operations/observability.md)和[认证与错误](doc/04-protocol/authentication-and-errors.md)。

## 测试与完成标准

本地单测只是一层安全网。业务系统的标准验证顺序是：

```text
本地测试 → 测试环境部署 → :server:acceptanceTest → 真实客户端操作与截图
```

- 协议、算法、缓存和确定性边界在本地测试。
- 跨 RPC、数据库、文件或账户的流程在真实部署验收。
- 窗口、输入、动画、下载和平台行为在真实客户端验证。
- Desktop 使用进程内测试服务读取语义树、执行单步操作并截图；优先 `testTag`，坐标只作回退。
- 不使用 `pkill -f gradle` 作为常规停止方式；结束自己启动的 Gradle 会话或明确 PID，避免误杀并发构建。

常用命令：

```bash
./gradlew :shared:jvmTest
./gradlew :server:test
./gradlew :app:desktopTest :desktop:desktopTest
./gradlew :desktop:compileKotlinDesktop
./gradlew :server:acceptanceTest
./scripts/check-println.sh
```

测试策略与长期场景见[测试与验收](doc/09-testing/README.md)。

## 文档维护

- 按知识领域修改现有章节，不按每个需求新建顶级目录。
- 稳定设计、操作手册、快速参考和当前状态分开维护。
- 正文使用现在时；开发经过、某轮问题和通过数量留在提交、任务或 CI 报告。
- 未完成能力进入[功能状态](doc/10-reference/feature-status.md)或[路线图](doc/10-reference/roadmap.md)。
- 移动文件后校验仓库内 Markdown 链接和旧路径引用。

## 提交前检查

1. 查看 diff，确认没有覆盖无关工作。
2. 运行与风险相称的本地测试和真实验收。
3. 用户可见改动完成真实客户端操作与截图核对。
4. 协议、配置、状态或交互变化同步更新权威文档。
5. `git diff --check` 无格式错误，再创建清晰的 Git 工作快照。
