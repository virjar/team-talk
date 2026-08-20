# 认证与错误

## 1. 认证类型

AUTH 的 `authType`：

| 值 | 流程 | 必需字段 |
|---:|---|---|
| 0 | 用户名/密码登录 | username、password、deviceId |
| 1 | 注册并登录 | username、password、name、deviceId |
| 2 | refresh token 自动登录 | refreshToken、deviceId |

所有流程都携带设备信息。认证成功后服务端返回 uid、access token、新 refresh token 和过期时间；
随后由已经打开本地缓存的 EventProcessor 通过独立 `SYNC_REQUEST(lastEventId)` 开始增量事件同步。
客户端的自动登录看门狗将连接/身份认证和事件同步分开：前者为 12 秒有界等待，
进入 `SYNCHRONIZING` 后改用 35 秒无进度窗口，每次持久化 cursor 前进都会续期。长 replay
不能被身份认证的固定计时主动断开。若 cursor 不属于当前账号，服务端通过 `SYNC_RESET` 要求
客户端在同一身份连接内清空服务器投影并从 0 重放；这不是认证失败，也不触发强制升级。

## 2. Token 模型

token 是服务端签发的随机值，不是 JWT：

- access token 用于当前连接和 HTTP 上传认证。
- refresh token 用于重建认证；成功使用后 access/refresh 整对轮换，旧 access 与旧 refresh 同时失效。
- token 与 uid/deviceId 绑定。
- 服务端 PostgreSQL 只保存 token 的 SHA-256、类型、有效期和签发时的用户/设备 credential epoch；
  明文 token 只在签发响应中出现，不能从服务端存储恢复。
- `deviceId` 是安装级稳定标识；密码登录、注册和 refresh 必须复用同一个值，认证成功会刷新设备登记与最后登录时间。
- 同一账号同一设备的新登录或 refresh 会严格推进设备 credential epoch，替换此前 pair，只保留最新
  access/refresh token；事务提交后、AUTH 成功前先发布设备 fence，延迟到达的旧认证不能反向接管连接。
- 登出失效当前设备 credential；踢设备推进设备 epoch，封禁账号、管理员重置密码和用户自助改密都推进用户 epoch。事务提交后
  服务端以新 epoch 建立连接 fence，使旧 token 和旧连接都不能重新生效。
- 自助改密的数据库事务提交后，发起连接先退出实时/认证集合，只允许写完本次成功 RPC 响应，随后立即关闭；
  其他旧会话在提交后的 fence 阶段关闭。客户端必须使用新密码重新登录。
- 解除封禁不回退用户 epoch，因此不会恢复封禁前的 token。

客户端持久化 refresh token；access token 只属于活动用户会话。日志、错误提示和截图不能输出 token。

## 3. 认证响应

AUTH_RESP code：

| code | 含义 | 客户端行为 |
|---:|---|---|
| 0 | 成功 | 创建/恢复 ClientSession |
| 1 | 认证失败 | 清凭据并进入登录 |
| 2 | 协议版本不支持 | 停止重连；Android 显示不可取消的强制升级弹窗，确认后退出应用 |
| 3 | 服务维护 | 显示服务不可用，可延迟重试 |
| 4 | 设备被封禁 | 清凭据并提示管理员处理 |
| 5 | 连接过多 | 停止当前尝试并提示设备限制 |

登录时未知用户与密码错误应返回同一类外部文案，避免用户名枚举。

## 4. 连接与用户状态

网络错误不等于认证失效：

- 断网、超时、连接重置：保留 UserSession，ImClient 重连并自动认证。
- AUTH_FAILED、设备被踢、主动登出：销毁 ClientSession、清 token、停止重连。
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

共享 `AuthRules` 让 SDK 和服务端使用一致的用户名、密码等结构规则。客户端前置校验提供即时反馈；
服务端仍必须重复执行规则和唯一性/权限检查，因为客户端不可信。

新增错误时要回答：它属于哪一层、是否可重试、是否终止用户会话、是否需要稳定 status code，以及
日志应记录哪些不含秘密的上下文。
