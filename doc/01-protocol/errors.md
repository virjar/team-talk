# RPC 错误码体系

> RPC RESPONSE 的 status 字段定义错误码，客户端通过 `AppError` 映射处理。

## 错误码定义

| Status | 语义 | 服务端触发场景 | 客户端处理（AppError 映射） |
|--------|------|--------------|-------------------------|
| `0` | 成功 | 正常返回 | 解析 payload |
| `400` | 业务参数错误 | `IllegalArgumentException`（如"不能和自己创建私聊"、"群名不能为空"） | `AppError.Business(400, msg)` |
| `401` | 认证失效 | token 过期/无效（通常在 ImAgent 层踢连接，而非 RPC 返回） | `AppError.AuthExpired` → 触发登出，**停而非重试** |
| `500` | 服务端内部错误 | 未捕获的 `Exception`（DB 异常、NPE 等），日志记录完整 stack | `AppError.Business(500, "Internal error")` |
| `504` | 请求超时 | 客户端 `RpcClient.invoke` 内部 10s 超时，构造 `ResponsePayload(504, ...)` | `AppError.Timeout` |

## 客户端网络层错误

非 RPC status，由 `outcome { }` 构造器捕获：

- `IllegalStateException("Not connected")` → `AppError.Network`
- `CancellationException`（连接断开）→ `AppError.Network`
- 其他 `Throwable` → `AppError.Unknown`

## 核心原则

**认证失效停而非重试**：401/AuthExpired 不在 Repository 层重试，向上传播触发 `ClientSession.close()` → 回到登录页。不在 token 过期时自动重试业务请求——避免无限重试循环。

**相关代码**：`app/.../AppError.kt`、`shared/.../client/RpcClient.kt`
