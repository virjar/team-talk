# 认证与错误

## 1. 认证类型

连接先完成[协议窗口协商](versioning.md#一次连接怎样协商)，成功后才发送凭据。建议升级仍可继续认证，
明确不兼容则直接进入强制升级表面；网络失败不能清凭据或冒充协议拒绝。

AUTH 的 `authType`：

| 值 | 流程 | 必需字段 |
|---:|---|---|
| 0 | 用户名/密码登录 | username、password、deviceId |
| 1 | 注册并登录 | username、password、name、deviceId |
| 2 | refresh token 自动登录 | refreshToken、deviceId |

所有流程都携带设备信息。认证成功后服务端返回 uid、access token、当前 refresh token、过期时间和
当前权威 `datasetId`；
密码登录/注册会签发新的完整凭据，refresh 认证则回传请求中的稳定 refresh bearer；
随后由已经打开对应 dataset 本地缓存的 EventProcessor 通过独立
`SYNC_REQUEST(lastEventId, datasetId)` 开始增量事件同步。
已有持久凭据的客户端先安装 dormant refresh owner，但此时不解析 DNS、不建立
TCP/TLS 且不发送 AUTH；只在固定账号的 LocalCache/ClientSession 完整发布后才开始远端
refresh。连接、认证或同步 deadline 不得淘汰仍在打开的持久本地工作区。若 cursor
不属于当前 dataset + 账号，服务端通过携带当前 datasetId 的 `SYNC_RESET` 要求客户端在同一身份连接
内通过 `SyncRpc` 加载当前权威 checkpoint，原子替换紧凑服务器投影并从
`baseEventId` 继续事件 tail；这不是认证失败，也不触发强制升级。

密码登录和注册是一次性认证材料，只允许一个 TCP 传输尝试。首次连接失败，或 AUTH 已发送但在
AUTH_RESP 前断线时，客户端必须原子终结该 exact attempt、释放用户名/密码 payload、停止该 owner
的自动重连，并显示可重试的登录网络错误。`authType=2` 的稳定 refresh 与不带认证的普通连接继续使用
有界退避重连。旧 attempt 的迟到断线/清理只能消费自己的 lease，不能清除后继登录。

注册 `name` 是必填显示名，最多 100 个字符，空白不会回退为 username。`deviceId`
是 1–100 个 ASCII 字符的安全安装标识，只能包含英文字母、数字、`-` / `_` / `.`，且不能是
`.` 或 `..`。`deviceName` / `deviceModel` 可缺省，提供时各不超过 200 个字符且不能包含
PostgreSQL `text/varchar` 无法表示的 U+0000；其他数据库可表示的 Unicode 文本仍可使用；
`deviceFlag` 只允许 0（未知/SDK）、1（Android）、2（Desktop）。codec 先以 UTF-8 byte budget
阻断过大分配，再校验实际字符上限；SDK 和服务端仍各自重复完整业务校验。

## 2. Token 模型

token 是服务端签发的随机值，不是 JWT：

- access token 用于当前连接和 HTTP 上传认证。
- refresh token 是用于重建认证的设备级稳定 bearer，首次签发后保持固定 90 天绝对期限。成功使用后
  只轮换 access、推进设备 credential epoch，并回传同一个 refresh token；旧 access 与旧连接立即失效。
  因此服务端提交后丢失 AUTH 响应时，客户端仍可用原 refresh 安全重试，期限不会滑动延长。
- token 与 uid/deviceId 绑定。
- 服务端 PostgreSQL 只保存 token 的 SHA-256、类型、有效期和签发时的用户/设备 credential epoch；
  明文 token 只在签发响应中出现，不能从服务端存储恢复。
- `deviceId` 是安装级稳定标识；密码登录、注册和 refresh 必须复用同一个值，认证成功会刷新设备登记与最后登录时间。
- 同一账号同一设备的新密码登录会替换完整 pair；refresh 保留唯一 refresh hash 与原始期限、替换唯一
  access。两者都会严格推进设备 credential epoch；事务提交后、AUTH 成功前先发布设备 fence，延迟
  到达的旧 access 或连接不能反向接管。
- 登出按发起会话认证时的设备 credential epoch 做 compare-and-revoke：若同一设备已经完成更新的登录或
  refresh，迟到的旧登出只终止旧会话，不能撤销新 credential pair。真正命中当前代次时才推进设备
  epoch 并删除该设备凭据。踢设备也推进设备 epoch；封禁账号、管理员重置密码和用户自助改密都推进
  用户 epoch。事务提交后服务端以权威 epoch 建立连接 fence，使旧 token 和旧连接都不能重新生效。
- 自助改密的数据库事务提交后，发起连接先退出实时/认证集合，只允许写完本次成功 RPC 响应，随后立即关闭；
  其他旧会话在提交后的 fence 阶段关闭。客户端必须使用新密码重新登录。
- 解除封禁不回退用户 epoch，因此不会恢复封禁前的 token。

客户端持久化 refresh token；access token 只属于活动用户会话。稳定 refresh 仍是高价值 bearer，一旦
泄露可在绝对到期、改密、封禁或设备撤销前重复换取 access，因此日志、错误提示和截图不能输出 token。

## 3. 认证响应

AUTH_RESP code：

| code | 含义 | 客户端行为 |
|---:|---|---|
| 0 | 成功 | 创建/恢复 ClientSession |
| 1 | 认证失败 | 清凭据并进入登录 |
| 2 | 协议版本不支持 | 兼顾未协商的旧客户端；双端停止重连，持久记录精确协议拒绝并显示强制升级，禁止工作区，保留数据 |
| 3 | 服务维护 | 显示服务不可用，可延迟重试 |
| 4 | 设备被封禁 | 清凭据并提示管理员处理 |
| 5 | 连接或设备过多 | 停止当前尝试并提示设备限制；保留已有离线账号与本地数据 |

AUTH_RESP 只进入认证状态机与类型化失败流，不发布到 SDK 的通用 `packets` 广播；响应内 access/refresh
bearer 因而不会被无关的消息、机器人或 UI collector 观察。
成功响应必须在线协议边界携带 lowercase canonical UUID `datasetId`，失败响应必须省略该字段；成功但
缺失、失败却携带或格式不 canonical 都是协议损坏，连接在进入认证/同步状态机前关闭。

认证尝试在进入 BCrypt/数据库前若命中全局、直连来源、规范化账号、操作、在途任务或计数桶容量上限，
统一返回 code 3 与固定的临时不可用原因；这不是 refresh 凭据失效，客户端不得因此清除持久身份。服务端
只把 TCP/HTTP connector 看到的直接 socket peer 用作来源，不信任客户端 payload 或代理转发头。

登录时未知用户与密码错误返回同一外部文案，并在服务端各执行一次同成本密码验证工作；封禁或服务
身份也不能通过跳过 CPU 工作形成快路径。密码 UTF-8 编码最多 72 字节，避免 BCrypt 对更长输入只比较
相同前缀而产生两个“不同密码”对应同一 verifier。

账号只有在完成有效密码证明后，才可能因持久设备 16 槽或 ClientRegistry 的 16 条身份连接上限收到
code 5；无效密码与未知用户名仍返回相同 code 1，不能用容量响应枚举某账号是否存在。code 5 使用固定
文案，不携带 uid、设备 ID、当前数量或数据库细节。同 deviceId 的更新登录替换旧连接并复用一个槽，
不会因账号正好满载而误拒；新 deviceId 只有在 16 个持久设备全部 active 时才拒绝，存在 revoked 槽时
由服务端事务性回收最旧 revoked 记录。

## 4. 连接与用户状态

网络错误不等于认证失效：

- 已有持久账号的断网、超时、连接重置：保留 UserSession 与 LocalCache，ImClient 使用 durable refresh
  重连并自动认证，页面继续显示本地数据和明确的离线状态。
- 用户新提交的密码登录/注册若在 AUTH_RESP 前发生网络失败：服务端不伪造成功或撤销其他账号事实，
  客户端立即释放一次性口令、停止该尝试的后台重连并留在登录页，由用户显式重试。
- `SERVER_MAINTENANCE`、连接数限制以及客户端本地凭据确认写入失败：本次 AUTH 连接终止并撤销旧
  Bearer，但已有持久账号继续挂载 LocalCache，以离线状态留在应用内；这些结果不能清 refresh token。
  冷启动的 refresh 只能在本地会话发布后开始，因此立即返回的可重试 AUTH 失败也必须观察到该本地 owner。
- 服务端明确拒绝 refresh credential、设备被封禁、HTTP 401 或主动登出：销毁 ClientSession；前
  三者属于权威认证撤销，主动登出属于用户指令，均结束当前身份并回到登录流程。
- 只有结构有效的 TeamTalk AUTH 序言携带了不支持的版本号时，服务端才返回 code 2；坏 magic、截断帧、连接超时和普通断网不能提升为强制升级。

SDK 的 `send()` 和 RPC 在未认证状态必须失败，不能在身份未知时静默排队。

## 5. 错误分层

| 层 | 表达 | 示例 |
|---|---|---|
| 参数/SDK | `IllegalArgumentException` 或 Outcome validation | 非法用户名、任意附件 URL |
| 连接 | exception / connection state | DNS、TLS、TCP reset、ACK timeout |
| 认证 | AUTH_RESP code / AuthExpired | token 失效、设备封禁 |
| RPC 业务 | RESPONSE status + payload/reason | 无权限、对象不存在、状态冲突 |
| 协议 | close connection + fault | 未知 TYPE、坏长度、字段错位 |

客户端 Repository 把这些映射成稳定 Outcome：成功、认证失效、超时、网络和业务错误。ViewModel 可以
决定如何展示，但不能吞掉认证失效或把所有异常都显示成“网络错误”。

## 6. 校验职责

共享 `AuthRules` 是 username、password、displayName 和全部设备字段的唯一业务规则源。注册/登录 UI
可以提前显示错误，但 AuthController 必须在退役旧持久身份之前对完整提交再校验；局部可判定的
空白/越界/非法设备值不得跨过身份替换边界。校验与当前认证表面 generation 的首次有效消费必须
原子完成；重复点击、迟到 IME、工作区发布前遗留的页面动作以及 logout 前旧动作均无权清理当前
凭据或启动认证。可重试传输失败只能签发新的表面 generation，服务端权威拒绝仍清理凭据并回到
全新的登录表面。ImClient/ImBot 作为 SDK 边界再验证，服务端在
分流认证类型、BCrypt 或创建用户前继续复验，因为客户端不可信。

新增错误时要回答：它属于哪一层、是否可重试、是否终止用户会话、是否需要稳定 status code，以及
日志应记录哪些不含秘密的上下文。
