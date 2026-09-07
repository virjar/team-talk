# 版本、兼容窗口与数据演进

TeamTalk 处于开发者预览阶段，**不对使用者保证版本兼容，未来仍可能有破坏性变更**。这不等于内部开发可以
随意破坏已有资料：从开发者预览零号基线开始，普通升级保留数据，同一协议大版本采用追加契约与明确迁移。
本章是协议生命周期和内部兼容规则的权威说明。

## 先分清版本与安装数字

| 身份 | 当前值与来源 | 用途 | 递增时机 |
|---|---|---|---|
| 统一展示版本 | `teamtalk.releaseVersion=0.0.0` | Server、SDK、Android、Desktop、MCP 使用同一字符串，配合 commit 排查构建范围 | 用户明确确认正式产品发行后推进；内测 snapshot 保持它不变，不从它推导协议能力 |
| 协议数字版本 | 已发行 `0.0`（ID 0），当前开发 `0.1`（ID 1）；`id=(major << 16) \| minor` | 连接协商、协议注解、支持窗口与升级提示 | 同一正式发行周期共用一个待发布 minor；本轮固定为 1，正式发布 0.0.1 后才冻结；新 major 从 minor 0 开始 |
| 正式构建计数 | 根 `teamtalk.releaseBuildNumber`，初始为 `0` | Android `versionCode=buildNumber+1`；正式 Desktop revision 同样映射为 `buildNumber+1`；macOS jpackage 的系统包版本从 `1.0.0` 映射 | 正式发行时由用户确认推进；内测 snapshot 不修改它，Android 保持当前 code 手动覆盖 |
| 内测 Desktop 修订号 | `desktopRevision=完整 Git first-parent 提交数+根构建号+1` | 满足 Conveyor 同一展示版本不同安装包的 revision 要求，记录在内测清单与安装元数据中 | 手动 snapshot 交付时自动计算，不写回配置、不依赖 tag；同展示版本的后续 snapshot 从已分发源码的后代构建，原字节重试复用原号 |

人工版本配置均来自根 `gradle.properties`；内测 Desktop revision 另外依据完整 Git 历史推导。
`ProtocolVersions` 与 SDK `TeamTalkBuild` 由构建生成；禁止在业务、
SDK 或平台壳里另外硬编码一个发行字符串。`major` 范围 `0..32767`，`minor` 范围 `0..65535`，
打包后的 ID 是非负递增整数。不能因为客户端显示版本字符串更大就推定它支持某个 RPC。

例如：纯 UI 修复可从展示版本 `0.0.0` 发布为 `0.0.1`，协议仍为 `0.0`；增加一个 RPC 可将协议升为
`0.1`，而展示版本由该批发行决定。展示版本的大号变化也不触发本地清理，**协议 major 变化才触发**。
同一修复也可先作为私有内测 snapshot 交付：展示版本保持 `0.0.0`、根构建号保持 `0`、Android code
保持 `1`，仅 Desktop revision 随已提交源码自动计算。正式发行继续增加根构建号使 Android code 升级；
Desktop 比较完整安装版本，新展示版本的末位 revision 可按根构建号重新映射。

数据库自身的布局标记与发行版本分别管理。服务端 PostgreSQL/data epoch 保持现存值
`1`，客户端数据库文件仍为 `cache_e0...db`，SQLDelight schema 从 `1` 起步。改写这些已有标记不会产生
迁移，反而会使同一批数据被误判为不兼容；初始化空实例也不要求把这些格式标记归零。

## 开发编号与发行契约分开管理

协议版本表示两次正式发行之间的整体契约差异，不表示功能数、提交数或 AI 调试次数。
当前正式基线是 `0.0.0 / protocol 0.0`；自该版本以来新增的组织资料 RPC 和账号封禁帧统一属于
待发布协议 `0.1`。**在用户正式发布 `0.0.1` 之前，所有新增及修订继续使用 minor 1，不递增到 2。**
展示版本和根构建号现在仍保持 `0.0.0 / 0`，本规则不自动发版。

| 阶段 | 协议动作 | 兼容对象 |
|---|---|---|
| 正式发行后首次新增契约 | 开启下一 minor；纯实现/UI 修复不增加协议号 | 已正式冻结的版本 |
| 同一发行周期继续开发 | 新增、修订本轮类型与 RPC，统一使用该 pending minor；审阅并登记开发 TSV | 已正式冻结版本，不为中间提交维护额外分支 |
| 本机验收、服务端开发部署、私有 snapshot | 保持 pending minor，记录源码 SHA 与实际 TSV 哈希 | 已正式冻结版本；同号开发包按同批源码配套验证 |
| 用户确认正式发行 | 审阅整批增量，定稿人工说明并冻结 `releases/<version>/` | 此后该协议成为新的不可修改基线 |

```mermaid
flowchart LR
    Zero["已发布 0.0.0：协议 0 冻结"] --> Pending["待发布协议 1"]
    Pending --> Changes["组织资料、封禁帧、后续本轮修改"]
    Changes --> Pending
    Pending --> QA["本机验收 / 内测包：源码 SHA + schema 哈希"]
    QA --> Pending
    Pending --> Approval["用户确认发布 0.0.1"]
    Approval --> Frozen["协议 1 冻结"]
    Frozen --> Next["以后有新契约才开启协议 2"]
```

`wire-baseline.tsv` 是当前源码的审阅清单，**不是一份已发行契约**。它的新增条目可以在本轮修订或删除，
但修改后必须显式 `writeProtocolBaseline`；不能通过改它掩盖协议 0 的变化。构建兼容检查始终以已冻结
快照为准，并拒绝在同一发行周期累加多个开发 minor。需要测试高版本窗口时，用测试数据构造版本，
不要为调试修改产品计数或保留只服务于中间状态的适配代码。

正式发行仍需用户确认后推进根展示版本与构建号，提交人工说明，显式执行 `prepareProtocolRelease`。
Tag 标记对应源码，GitHub 自动摘要只补充提交信息。已准备的正式快照不可覆盖或删除，发布失败重试也
不回收已登记的编号。

用户要求刷内测包即授权该次手动 snapshot；它不要求再次确认正式发行，不增加根展示版本或构建号，
不运行 `prepareProtocolRelease` 或 `prepareProtocolContract`。`private-first` 首次私有测试交付同样使用
当前待发布契约。两种模式相对正式冻结基线检查兼容，并密封源码与 schema 哈希；后续同号开发包应连同
服务端更新，不承诺所有中间构建互通，也不因协议同号便认领为同一份字节。数据兼容仍独立维护，
普通升级不删除草稿、发件箱、凭据或服务器资料。

历史上明确登记的独立稳定契约 `contracts/<major>.<minor>/` 继续受保护；当前仓库没有这类记录。
`prepareProtocolContract` 只用于用户另行明确要求冻结独立稳定协议的交付，不是普通内测步骤。
本轮只以已发行协议 0 为兼容基线，不将此前本机试验的 minor 1/2 变成两条历史。

| 文件 | 事实与修改规则 |
|---|---|
| 根 `gradle.properties` | 当前展示版本、安装计数、唯一 pending 协议窗口；不随内部小修复递增 |
| `protocol/protocol/wire-baseline.tsv` | 可审阅的当前开发清单，经 `writeProtocolBaseline` 登记 |
| `protocol/protocol/releases/<version>/` | 用户确认正式发行后的冻结快照，既有目录不可修改 |
| `protocol/protocol/contracts/<major>.<minor>/` | 历史或明确独立稳定交付的冻结记录，普通 snapshot 不新增 |
| 密封产物的 manifest / checksums | 此次实际源码、协议清单哈希、安装身份和文件字节，不作为新的兼容版本 |

内测 Desktop revision 仍依据完整 first-parent 历史推导，以满足安装器的同名版本替换要求；它不是协议号。
保留已交付源码历史，不通过压缩或重写制造安装修订号倒退。Android 同 code 可手动覆盖安装，
签名和安装身份保持稳定。完整命令见[内测更新](../07-operations/releasing.md#保持展示版本的内测更新)。

## 一次连接怎样协商

```mermaid
sequenceDiagram
    participant C as 客户端
    participant S as 服务端
    participant U as 客户端界面
    C->>S: TCP/TLS 就绪，NEGOTIATE（支持窗口、展示版本）
    S->>S: 检查 major 与 minor 窗口交集
    alt 没有可用交集
        S-->>C: NEGOTIATE_RESP（拒绝、服务端窗口）
        C->>U: 强制升级或更换匹配版本，禁止工作区
        Note over C,S: 不发送密码，不进入认证业务
    else 可以兼容
        S-->>C: NEGOTIATE_RESP（最高共同 minor）
        C->>C: 校验响应确实匹配原始提议
        C->>S: AUTH
        S-->>C: AUTH_RESP，随后正常同步
        opt 客户端协议低于服务端当前版本
            C->>U: 横幅提示升级，当前兼容会话继续使用
        end
    end
```

同一 major 的支持窗口是 `[minimumMinor, currentMinor]`；存在交集时选择双方 currentMinor 的较小值。
这也允许新客户端连接尚未更新、但仍在客户端保留范围内的旧服务端。不同 major 不混用编号，也不猜测解码。
协商信封是固定 bootstrap 契约，不携带凭据。AUTH 里的 `TK + 0 + 1` 是固定格式标记，**不是业务协议版本**。

服务端构建下限由 `teamtalk.minimumProtocolMinor` 固定，运行配置 `MINIMUM_PROTOCOL_MINOR` 只能提高
该下限，不能低于编译保留范围或高于当前 minor。运行配置在启动时读取，修改后重启生效；它不改变数据集。

客户端明确收到不兼容结论后，停止重连并退役工作区。服务器已淘汰旧客户端时，按部署和本客户端
精确协议 ID 保存拒绝状态，更新客户端后重新判断；服务器自身落后时只阻止本次会话，管理员升级后
重新启动客户端即可重新协商。网络超时、坏响应或临时维护不能伪装成强制升级。
已有账号仍保留离线启动能力：完全离线时无法预知服务端刚提高的下限，但**一旦已知旧客户端被淘汰，
之后的离线启动也不得绕过该限制**。拒绝旧客户端不删除账号凭据、草稿或发件箱。

## 同一 major 只新增，不修改已发行 wire

1. 已冻结发行 RPC 的 `(serviceId, methodId)`、参数顺序、类型和返回值不变。新签名使用新 methodId，
   新旧入口在同一组代码和 jar 内共存，各自调用明确的兼容业务逻辑。
2. 已冻结发行 IProto 模型的 wire 字段和编码顺序不变，不能以“字段可空”或“只加在末尾”为理由修改旧布局。
   要改变布局就创建新模型，并通过新 RPC/消息/通知入口引入。
3. 新增 RPC、PacketType、NotifyType、MessageType 或 wire 类型时，复用本轮待发布 minor，并用
   `@SinceProtocol(minor)` 描述首次正式支持版本；同批开发不单独递增。零号基线中没有注解的已有条目属于 minor 0。
4. 同一 major 内退役的编号保留墓碑，不重新使用。两次正式发行之间的所有协议新增共同属于同一个 pending minor。
5. 只改变实现方式、修复保持原契约的缺陷，不要求增加协议版本；不能用“重构”掩盖线上字节或业务语义变化。

示意（方法编号仅作说明，实施时在所属服务内分配未使用编号）：

```kotlin
@RpcMethod(21)
@SinceProtocol(1)
suspend fun getProfileV2(uid: String): UserProfileV2

@RpcMethod(3)
@SinceProtocol(0)
@RemovedInProtocol(3)
suspend fun getProfile(uid: String): UserProfile
```

`@RemovedInProtocol(3)` 表示协商版本从 minor 3 起不再调用该入口；如果服务端还兼容 minor 0–2，
旧实现仍要保留。最低支持版本升到 3 后，构建检查要求退役实现，清单留下原编号和签名的墓碑。
服务端请求上下文携带真实协商版本，生成代理和 dispatcher 共用版本窗，业务需要分支时从该上下文读取，
不得在不同层猜客户端展示版本。

## 构建怎样发现误改与废弃

```bash
# 普通检查，同时对照开发清单和独立的冻结发行快照
./gradlew :protocol:protocol:verifyProtocolBaseline

# 有意新增、退役或收敛未发行 minor 后，生成可 review 的开发清单
./gradlew :protocol:protocol:writeProtocolBaseline

# 用户明确确认本次发行后：新增不可覆盖的发行快照
./gradlew :protocol:protocol:prepareProtocolRelease

# 仅用户明确要求冻结独立稳定协议时使用；普通内测不运行
./gradlew :protocol:protocol:prepareProtocolContract
```

KSP 每次编译都从 `releases/` 与 `contracts/` 选择最新冻结契约检查实际源码，再检查开发清单是否已经登记；即使手工修改开发 TSV，也不能
掩盖已经发行的签名变化或墓碑复用。显式 `writeProtocolBaseline` 允许相对冻结快照重新登记未发行的工作，
因此修订本轮新增契约不受中间开发记录的兼容约束。它不会写入或修改发行历史。

`prepareProtocolRelease` 检查展示版本和安装序号递增、minor 连续、已发行布局与生命周期不被改写，再写入
新的发行目录。它是用户确认本次发行后才能执行的源码编辑操作，**不会由发布任务或 CI 偷偷执行**；维护者必须审阅并提交根配置、
人工说明、代码与两类清单。正式发行的 `verifyReleaseMetadata` 要求当前根版本已有匹配的最新发行记录，开发清单与冻结文件
逐字节一致。普通发行校验和编译均不需要 GitHub，也不查询远端 tag 才决定是否应保护一个契约。
CI 的 `verifyReleaseChange -PreleaseBase=<比较基点>` 还检查基点已有的发行快照和独立协议契约未被修改或删除；
正式发行要求根展示版本和构建号一起推进，并校验本次人工说明和新快照；单独变更或回退根构建号会被拒绝。
内测刷包不修改这两个字段，也不由 CI 自动生成 snapshot。纯开发 minor 变化不触发发行冻结。

手动服务端开发部署沿用 `verifyRelease` 的干净源码、架构和 wire 校验，可以运行尚未发行的开发 minor，
不强迫每次调试都登记客户端发行快照。客户端统一 `release` 同时执行这项门禁与相应模式的元数据检查；
首次私有分发与内测 snapshot 相对冻结基线检查当前开发契约，不新增冻结记录。实际交付仍须在用户授权范围内执行，
不能以服务端调试路径绕过交付纪律。

收敛时应先列出所有大于上一发行 minor 的 `@SinceProtocol`、首次声明的 `@RemovedInProtocol`、版本判断、
迁移和测试，把同一批变更统一指向下一 minor，再登记开发清单。构建会阻止未收敛版本被打包成发行，
但不会用自动正则替换业务源码。每次审阅应同时看版本计数器、注解、清单 diff 和业务适配，不能只提交生成文件。

结构清单不能证明任意手写 `writeTo/readFrom` 的语义都未变化。例如在方法体里调换两次写入，即使构造
字段没改也可能破坏 wire；维护者仍须遵守冻结规则，并为实际编码变化保留 round-trip/golden 检查。

## 兼容分支不是任意新业务的自动翻译器

服务端可按协商版本投影推送：不支持的瞬时事件不发送；不支持的持久事件用无业务 payload 的
`EVENT_CURSOR_ADVANCED` 承载相同 eventId，让旧端推进游标而不解析新字段。此通知仅用于连接输出投影，
不能作为新的领域事实写进持久事件流。

这有两项必须由新增功能处理的边界：

- 旧客户端跳过某类事件后再升级，新功能需要通过权威 RPC/checkpoint 或本地 minor 迁移补齐初始投影，
  不能指望已经越过的事件再次自动到达。
- 当前 Message body 没有独立长度信封，历史列表中的未知消息类型不能安全跳过。新增消息类型必须提供
  明确的历史/同步兼容适配，或提高最低协议版本；只给枚举加 since 注解不等于完成整条业务兼容。

原来的 `ExtensionType`、`generic` RPC、`GENERIC(99)` 消息/通知和 `GenericPayload` 没有注册的业务实现，
已在零号基线移除。明确的新 ID 和版本窗承担演进职责，不另建 opaque payload 逃生入口。

## 数据随版本怎样处理

```mermaid
flowchart TD
    Start["启动本客户端安装"] --> Marker["读取本地 major 标记"]
    Marker --> Same{"与客户端协议 major 比较"}
    Same -->|相同| Migrate["保留凭据、草稿和发件箱；运行 SQLite 事务迁移"]
    Same -->|客户端更高| Reset["持久记录重置目标 → 清本安装数据 → 重新登录"]
    Same -->|客户端更低| Stop["拒绝降级打开；保留数据"]
    Reset --> Ready["写入新 major 完成标记"]
    Migrate --> Open["打开本地工作区"]
    Ready --> Login["登录并重建服务器投影"]
```

- **客户端 minor**：保持数据库文件名。Android 使用 SQLDelight driver 的升级回调；JVM 使用
  `PRAGMA user_version` 和事务包裹的 SQLDelight `.sqm` 迁移。未标记的旧 JVM 库按既有 schema 1 认领后
  逐步迁移，不直接冒充最新格式。零号首次认领时允许补齐历史上未事务化创建的 schema 1，之后不再
  重跑建表定义。失败回滚数据和版本，遇更高 schema 拒绝降级，不能自动删库。
- **客户端 major**：在凭据、SQLite 和草稿 owner 打开前执行；Desktop/Headless 持有数据目录进程锁。
  重置只清本安装管理的数据，保留目录认领与锁。先落 `reset:<major>` 再删除，中断后可继续，完成才记
  `ready:<major>`；更老客户端不接管半完成的数据。服务器返回不同 major 本身不会触发清理。
- **服务端**：普通部署保留 PostgreSQL、RocksDB、文件和 dataset identity。PostgreSQL 用从 0 起的
  `schema_migrations` 顺序台账，在同一事务内提交 DDL 与完成记录；首条迁移放宽遥测协议 ID 的旧字节约束，
  保留原行和 dataset。未来跨存储布局变化仍须提供明确迁移与恢复步骤，epoch 预检不代替迁移。跨 major 的协议编号
  重整也不自动授权删除服务端资料，重置需要明确实例、范围和影响。

## 零号基线的切换边界

初始 `0.0.0` 以完整现行契约建立协议 `0.0`。发行前的开发包、临时零号包和私有试验契约不属于本版
兼容来源；本次经用户确认重新初始化的测试实例不提供旧资料的原地迁移，使用者需换用本版客户端，
按新实例重新注册、登录。切换前自行保存本地草稿、待发消息与附件原件；不能仅凭相同展示版本混用旧包。

这次初始化只用于建立首个公开开发者预览基线。基线建立后，普通升级继续保留数据；既有发行记录、
独立分发契约与安装序号均按前述规则保护，不能把初始清理当作日后再次清库、降号或覆盖版本的授权。

Android 的展示版本重置为 `0.0.0`，零号安装序号为 `1`。Android 系统不会把它当作已安装的旧开发包
`versionCode=1000008` 的升级：旧开发安装需要单独处理换装，不能以静默清数据掩盖安装降级。
新预览基线之后，正式发行的 Android 安装 code 递增；私有内测 snapshot 可沿用当前 code 手动覆盖。
展示版本、协议版本与 Desktop revision 分别按前述规则推进。

macOS 的 JDK `jpackage` 同样要求包版本首段为正数。Compose DMG 将构建计数 `b` 映射为
`(b / 1000000 + 1).((b / 1000) % 1000).(b % 1000)`，零号为 `1.0.0`；Finder 的系统包元数据
可能显示此安装编号，应用内关于页、运行画像、发行清单和分发文件名仍显示 `0.0.0`。
Conveyor 的 `app.version` 读取统一展示版本，不使用这项仅供 jpackage 的映射；
正式发行的 `app.revision` 由根构建计数加一提供，避免 Conveyor 22.1 拒绝全零安装版本；snapshot 使用
完整 first-parent 提交数加根构建号再加一，同一展示版本下不能用浅克隆或重写已分发历史制造另一个修订号。
因此零号预览的 Linux 包版本为 `0.0.0-1`，Windows 与 macOS 的安装版本为 `0.0.0.1`，
应用内展示版本仍为 `0.0.0`。同一展示版本不得归零 Desktop 修订号来复用已分发包；正式推进展示版本时，
新版本可重新使用由根构建号推导的末位 revision。
