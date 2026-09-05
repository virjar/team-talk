# 工程约束

这些规则保护协议确定性、生命周期和可测试性。违反时应先说明为什么原边界不再成立，而不是增加
例外开关。

## 0. 设计取舍

- 优先实现一条直接、可读、可维护且能从失败中恢复的产品路径；不要先为假设中的攻击、未来领域或
  尚未出现的部署规模建立通用框架。
- 权限在服务端业务入口集中裁决，客户端只消费结果并收敛本地投影。除非已有可复现竞态，同一事实
  不在 UI、Repository、缓存和播放器分别维护 gate、epoch 或授权副本。
- 安全规则保护明确边界，不以代码行数、排列组合测试数或围栏层数衡量质量。新增抽象必须同时减少
  调用方复杂度；否则优先使用现有直接路径。
- 评审首先检查用户结果、离线与取消、资源释放、错误恢复和可读性，再检查必要的服务端权限与秘密边界。

### 面向项目所有者的可读性

- 代码分层尽量与目录对应，同一业务的实现与相关模型放在可局部阅读的位置。不要把目录铺得过宽，
  也不要为一个简单文件再建一层目录；短小的一次性函数和类型可与使用它们的主体放在同一文件。
- 目录表达一组内聚职责，而不是给每个类加模块外壳。只有一个简单文件时，优先放回最近的合适目录；
  相关实现散在别处时先按职责归并，不一律上提到根目录或塞入 `util`。跨平台 source set 中同一包的
  expect/actual 要合起来判断，独立依赖、协议和资源生命周期边界也不按文件数机械删除。
- 先减少理解业务必须认识的概念和跳转次数，再考虑文件长度。复杂方法优先理顺主流程、提前返回、
  合并重复分支；只有独立职责才提取方法或类，不以机械拆分制造更多阅读入口。
- 简单且只有一个实现的接口，若没有真实的分层边界，优先改成直接调用。领域到数据库、网络等外层
  的端口可保留，但不能仅为单元测试或假设中的未来实现增加接口。
  删除接口时同步收掉纯转发适配器、无消费者的实现及多余装配，不能只换名字而保留同样的跳转。
- 同一模型不要在相邻步骤反复包装成 Row、Snapshot、Context、Result 等近似实体。先判断是否可以
  直接使用已有数据；只有表示不同事实、生命周期或隔离边界时才引入新模型。
- 从真实生产入口判断代码是否可达；一组废弃方法互相调用、或只被测试调用，不算产品使用。
  删除无消费者声明、测试独占的旧业务支路及其附属适配器，相关测试删除或改走现行业务入口；
  不为保住测试而在产品中保留第二套流程。失去消费者的测试钩子也一并清理。
  核对时包含 DI、框架回调、生成代码和明确的对外 SDK 契约；仍用于真实故障验证的观测/注入口
  只保留必要的最小接缝，不据此另造业务实现。

## 1. 依赖与所有权

- `protocol ← protocol-netty ← shared ← app ← android/desktop` 单向依赖；`protocol` 禁止 Netty。
- `server → protocol/protocol-netty` 只复用契约和 TCP 适配；服务端生产代码禁止依赖客户端 SDK `shared`。
- Admin 由独立 `:server:admin` Gradle 模块在 `server/admin/build` 内按锁文件构建；Node.js 与 npm 也由
  该模块管理。Server 通过任务产物依赖获取静态资源；`server/admin/dist`、`node_modules` 和 `build`
  均是本地产物，禁止跟踪或绕过构建链作为分发输入。
- 服务端 `domain` 不 import `infra`、RPC 生成 Stub 或 transport adapter；外部能力由领域端口注入。
- 每个长期对象有唯一所有者，owner 销毁时级联销毁。
- `close/destroy` 幂等。
- 网络断开只清连接层；AUTH_FAILED 清用户层。
- 大附件传输必须使用已知长度、可重复打开的分块 source；禁止用 `File.readBytes()` 或
  `InputStream.readBytes()` 回退。HTTP Repository 固定 server 与 owner uid，每请求读取同会话最新
  token，uid 变化或 owner 关闭后失败关闭。

## 2. 协议

- IProto 读写字段严格同序同类型。
- RPC IDL 是 method 编解码的唯一入口；每个方法必须显式声明唯一 `@RpcMethod(id)`。
- 新 NotifyType 登记 NotifyContracts。
- 新 MessageType 登记 body registry 和 policy。
- 同一协议 major 只追加 wire，新签名/类型用新编号和 since 注解并递增 minor；清单、最低支持版本与退役检查见[版本规则](../04-protocol/versioning.md)。手写 codec 仍须核对 golden tests，不能靠重写清单掩盖旧布局变更。
- 优先传稳定模型，不手写重复 payload。

## 3. 本地优先

- ViewModel 不把一次网络响应作为长期页面状态。
- 新数据必须有 LocalCache/事件/恢复路径。
- 服务端写操作大多通过 NOTIFY 收敛客户端。
- readSeq、serverSeq、version 等单调字段用 max 合并。
- EventProcessor 成功后才推进游标。

## 4. 服务端

- 每个 Ktor Application/测试容器拥有独立 `PostgresDatabase`；`createServerModule` 必须显式接收其
  `Database`。生产代码中的 `transaction` / `newSuspendedTransaction` 必须指定该实例，关闭时只注销并
  关闭本容器的句柄，不得读取或替换 Exposed 进程默认数据库。
- 唯一生产 `Database.connect` 必须直接包装 `BlockingIoGuardDataSource`；新增 UoW、Repository 或直接
  查询不得绕过这一连接获取边界，也不得用逐方法 `ThreadIOGuard.check` 伪装完整覆盖。
- 未认证与已认证连接使用不同 frame limit。
- TCP 与 HTTP Netty EventLoop 的线程工厂必须在完整线程生命周期内 protect，并在 `finally` 中
  unprotect；EventLoop 不做阻塞 IO，普通 IO/Looper/trace worker 不散布反向 `unprotect` 调用。
- Ktor route 只能注册在 Application 全局 HTTP 阻塞边界之后；route、请求体、静态文件、数据库与
  本地存储访问都使用这一个固定线程数、固定队列、`ServerResourceOwner` 自有的执行器。禁止在 route
  内用 `Dispatchers.IO`、临时 executor 或 fire-and-forget 协程绕过统一准入、关闭和过载语义。
- 权限和附件校验在成功响应前完成。
- 领域状态写入后才持久化/推送事件。
- 消息、幂等索引和待投影 outbox 原子写入；跨存储投影失败必须可重试和启动恢复。
- 服务端禁止 `println`；初始化前必要信息使用受控 stderr，其余走 SLF4J/Recorder。
- 不吞异常；CancellationException 保持取消语义。
- 生产 BCrypt 只能位于 `infra/security/BCryptPasswordHasher`；domain/application 依赖窄
  `PasswordHasher` 端口。密码计算使用 Application 自有的有界 CPU executor，不得在 PostgreSQL
  事务、EventLoop、`Dispatchers.Default` 或普通 IO worker 中直接执行。缺失/策略禁用身份使用 dummy
  verifier 消耗等价工作。
- 人类注册禁止先查用户名/手机号再插入；密码 hash 必须先于事务，唯一性由数据库约束决定，驱动错误
  只能按精确约束映射为不包含冲突值与 driver detail 的业务错误。服务账号只保存随机不可登录 marker。
- `server/application` 只能依赖领域与 application 端口，不得 import `infra`、Exposed、Ktor、Netty 或
  `java.io.File`；数据库、连接注册表和文件诊断由 `ServerModule` 绑定外层适配器。
- 管理文件诊断的日志数、client uid/device/file 层级、tail 字节/行数和目录遍历 entry 都必须有集中、
  可测试的硬预算；部分容量统计必须显式标记为下限，不能伪装成完整 byte 总量。
- 管理分页必须复用已校验的 page/size 值对象；数据库 offset 使用 Long，只有边界仍要求 Int 时才做
  显式上限检查，禁止先用 Int 乘法再转 Long。按日统计通过可注入 Clock 和本地时区确定日界。

## 5. Kotlin 与状态

- 传输和状态 data class 使用 `val`，通过 `copy()` 更新。
- Compose 可空状态先捕获局部变量，不在跨重组分支中滥用 `!!`。
- StateFlow 的读改写在明确同步边界内完成。
- 不把可变集合直接暴露给 UI。
- Repository 保持 `CancellationException`；Feature/ViewModel 捕获宽泛异常时必须先重抛取消，
  owner 销毁后不得写入错误状态或调用结果回调。
- 搜索、切换对象等可重入异步请求必须在首次挂起前固定目标与世代；输入变化、路由退出或 owner 销毁时
  立即使旧 token 失效，只有最新请求可以提交 loading、结果或错误状态。
- 生命周期 best-effort 清理可以收集普通 `Exception`，但必须先跑完后续 owner，再原样传播取消与
  非 `Exception` 缺陷并用 suppressed cause 汇总；诊断回调不得把致命失败伪装成成功。
- 已提交远端结果后的 staging 普通清理失败只进入诊断，不能把成功改写成可重试失败；但清理中的
  `CancellationException` 与非 `Exception` 缺陷仍按生命周期终态传播，并保留原失败树。
- 单文件超过约 500 行时检查职责和主流程，只按真实边界拆分。`checkArchitecture` 对超过 800 行的
  文件提示评审热点，不因行数阻止构建；实际依赖违规、资源边界违规仍会失败。

## 6. 平台 UI

- Android 与 Desktop 不共享导航。
- 共享组件不创建平台 Window/NavController。
- Desktop 页面先选择工作区、检查器、模态、任务窗口或确认框。
- 缺后端能力时显示明确空态，不放假按钮/假数据。
- 稳定交互添加 testTag；已有 tag 非必要不改名。
- Desktop Enter 换行、Cmd/Ctrl+Enter 发送。

## 7. 配置

- 默认不增加布尔开关、profile、flavor 或运行时服务器选择。
- 部署坐标统一从 deployment.json 读取。
- secret 不入库、不写日志。
- 构建产物内嵌完整 build identity/build time；dirty source 必须显式标记，发布任务必须拒绝 dirty tree。
- CI 部署只消费 producer job 已生成并带 identity manifest 的产物，不允许在 deploy job 重新构建。
- 新配置必须说明所有者、默认值、组合测试和废弃方式。
- 部署生产代码禁止直接启动外部进程；关键命令使用有总超时、检查退出码的统一执行器，语义探测声明
  可接受退出码，best-effort 只用于明确的非关键清理并必须留下警告。
- 部署成功必须建立在必需产物已真实上传以及完整健康 JSON 契约通过之上；HTTP 200、日志文案或旧进程
  仍可访问都不能单独作为成功依据。
- 本地 secret 保存必须完整、owner-only、no-follow 且同目录原子替换；写入 shell 环境文件的任意值
  必须使用可 round-trip 的 POSIX 字面量编码。

## 8. 测试

- 遵循[测试策略](../09-testing/README.md#取舍原则轻单元重集成与端到端)：优先稳定业务边界的真实
  集成/E2E，简单内部实现不配套单元测试；仅为复杂易错规则、必要 wire 契约和难以稳定触发的故障
  保留最小确定性回归，不在多层重复穷举。
- 跨模块 SDK 测试替身放入 `shared-testkit`，只能从 test 配置依赖；产品源集不得引入
  `com.virjar.tk.testing`。
- 跨服务/跨客户端业务加入真实部署验收。
- UI 改动启动真实客户端并截图。
- 不为单元测试暴露仅测试使用的生产 API。
- 不用固定坐标脚本代替语义验证。

## 9. 文档

- 稳定事实写权威章节；状态/缺口写 reference；过程留在提交/任务。
- 不复制 RPC、事件、配置表。
- 代码路径和命令必须可执行。
- 移动文档后扫描所有 Markdown、源码注释和 AGENTS.md 链接。
