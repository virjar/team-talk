# 认证体系

> Token 方案设计、认证流程、连接管理。

## Token 方案

随机 token + RocksDB 存储（非 JWT）：

```
Token 生成: SecureRandom.nextBytes(32) → Base64Url 编码 → 64 字符字符串
Token 存储: RocksDB ColumnFamily "tokens"
  Key:   token (String)
  Value: { uid, deviceId, deviceFlag, createdAt, expiresAt }
  TTL:   30 天自动过期
```

**优势**：
- Token 是纯随机数，无法猜测或伪造
- 无"超级密钥"泄漏风险（JWT 的 secret 泄漏可伪造任意用户 token）
- 踢设备 = 删除对应 token 记录，立即生效

**为什么不用 JWT**：
- JWT 的无状态优势在 IM 场景不适用（IM 需要主动踢设备，必须维护 token 状态）
- JWT secret 泄漏风险高（一个密钥控制所有用户）
- JWT payload 体积大（含 base64 编码的 JSON claims）

---

## 认证流程

```
1. 客户端建立 TCP 连接
2. 服务端发送 MAGIC + VERSION（握手）
3. 客户端发送 AUTH 包:
   - 注册: { type=REGISTER, username, password, name, deviceId, deviceName, deviceModel }
   - 登录: { type=LOGIN, username, password, deviceId, deviceName, deviceModel }
   - token 刷新: { type=REFRESH, refreshToken, deviceId }
4. 服务端验证后返回 AUTH_RESPONSE:
   - 成功: { code=OK, uid, accessToken, refreshToken, expiresIn }
   - 失败: { code=ERROR, reason }
5. 后续操作通过 INVOKE 调用
```

### Token 刷新（one-time-use）

Token refresh 是**一次性**的：服务端删旧 token、生成新 token 对。客户端必须在 `handleAuthResponse` 成功后更新 `pendingAuth.refreshToken`，否则重连时会使用已消费的旧 token → AUTH_FAILED → 掉线循环。

```kotlin
// ImClient.handleAuthResponse
fun handleAuthResponse(response: AuthResponsePayload) {
    // 必须更新 pendingAuth，否则重连用旧 token
    pendingAuth = pendingAuth?.copy(refreshToken = response.refreshToken)
    // ...
}
```

---

## 连接管理

- 单用户多设备支持：每个设备独立 token，独立 TCP 连接
- 踢设备：通过 NOTIFY 推送给被踢设备，设备收到后断开连接
- 心跳：PING 15s 间隔，readerIdle 45s 超时（详见 [README.md §7 心跳机制](README.md#7-心跳机制)）

---

## 三级认证状态

客户端维护三级认证状态，各自独立管理：

| 层级 | 持有者 | 内容 | 生命周期 |
|------|--------|------|---------|
| 用户层 | `UserSession` | uid, username, refreshToken | 认证成功→退出登录 |
| 连接层 | `ImClient` | TCP channel, pendingAuth | 连接建立→断开 |
| 持久化层 | `TokenStore` | uid + refreshToken 写本地存储 | 跨进程重启 |

三级状态解耦使得：
- TCP 断线重连不丢失用户登录态（UserSession 还在）
- token 刷新不影响 TCP 连接（ImClient 独立管理 pendingAuth）
- 进程重启后从 TokenStore 恢复（自动登录）
