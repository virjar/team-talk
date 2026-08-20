# 认证与错误

## 1. 认证类型

AUTH 的 `authType`：

| 值 | 流程 | 必需字段 |
|---:|---|---|
| 0 | 用户名/密码登录 | username、password、deviceId |
| 1 | 注册并登录 | username、password、name、deviceId |
| 2 | refresh token 自动登录 | refreshToken、deviceId |

所有流程都携带设备信息和 `lastEventId`。认证成功后服务端返回 uid、access token、新 refresh token
和过期时间，再开始离线事件补发。

## 2. Token 模型

token 是服务端保存的随机值，不是 JWT：

- access token 用于当前连接和 HTTP 上传认证。
- refresh token 用于重建认证，会在成功使用后轮换。
- token 与 uid/deviceId 绑定。
- `deviceId` 是安装级稳定标识；密码登录、注册和 refresh 必须复用同一个值，认证成功会刷新设备登记与最后登录时间。
- 踢设备或登出删除 token，使后续认证立即失败。

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
