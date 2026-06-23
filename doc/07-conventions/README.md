# 编码规范与约束

> TeamTalk 的编码约束，从实际踩坑中提炼。违反约束会导致已知的 bug 类型重现。

## 目录

- [1. 服务端禁止 println](#1-服务端禁止-println)
- [2. RPC Payload 编码约束](#2-rpc-payload-编码约束)
- [3. 状态管理约束](#3-状态管理约束)
- [4. Compose 状态约束](#4-compose-状态约束)
- [5. 代码共享约束](#5-代码共享约束)
- [6. 文件大小约束](#6-文件大小约束)

---

## 1. 服务端禁止 println

### 规则

服务端代码（`server/src/main/`）必须用 SLF4J（`LoggerFactory.getLogger(Xxx::class.java)`），禁止 `println()`。

### 为什么

`println` 输出到 stdout，不经过日志框架，无法被 logback 配置控制，且在生产环境产生不可控的 I/O。

### 例外

Environment 在 logback 初始化前加载（logback 的 LOG_DIR 依赖 Environment 设置的系统属性），此处不能用 SLF4J，用 `System.err.println`（stderr 不经过 logback）。

### CI 检查

`scripts/check-println.sh` 扫描 server 代码，发现 `println` 则构建失败。

---

## 2. RPC Payload 编码约束

### 规则

1. **`encodePayload { }` 和 `withPayload { }` 必须严格配对**：字段数量、顺序、类型完全一致
2. **优先用已有 IProto data class** 作为 payload（`ProtoCodec.encode()` / `decode()` 自动处理）
3. **新增 RPC 方法必须加 ProtoRoundTripTest**
4. **简单 payload（1-3 个字段）允许手写**，但需注释标注字段顺序

### 为什么

这是最易出 wire format 错位 bug 的地方。历史上出过 3 次：
- ChatRouteHandler.UPDATE：客户端发 4 String，服务端按 Chat 9 字段解码
- ClassCastException：`?: 0` 编译为 Integer 而非 Long
- ReplyBody.content：服务端旧版未部署新字段

### 正确示例

```kotlin
// 客户端（4 个字段，顺序：chatId, name, avatar, notice）
val payload = ProtoCodec.encodePayload {
    writeString(chatId)      // 1
    writeString(name)        // 2
    writeString(avatar)      // 3
    writeString(notice)      // 4
}

// 服务端（必须严格配对）
ProtoCodec.withPayload(payload) {
    val chatId = readString()!!   // 1
    val name = readString()       // 2
    val avatar = readString()     // 3
    val notice = readString()     // 4
}
```

---

## 3. 状态管理约束

### 规则

1. **服务端是唯一真相源**：客户端 LocalCache 是缓存，永远以服务端的 seq 为准
2. **多状态源合并必须用字段级策略**：不能"整体替换"，特别是 unreadCount/readSeq/draft
3. **已读位置只前进不后退**：`readSeq = maxOf(local, remote)`
4. **草稿是纯客户端状态**：本地非空草稿优先于服务端

### 为什么

5 次状态覆盖类 bug 的根因都是"整体替换"而非"字段级合并"。详见 [03-client/README.md 第六章](../03-client/README.md#6-状态合并策略)。

---

## 4. Compose 状态约束

### 规则

1. **窗口级状态由外部持有并显式重置**：不要在 Screen 内部 `remember` 状态依赖外部条件
2. **`remember` 跨可见性切换保留状态**：`Window(visible = false)` 不销毁 composition
3. **需要重建时用 `key()` 强制**：`key(screen) { SubWindow(...) }`

### 为什么

Compose 的 `remember` 设计是保持状态稳定，但 IM 场景下"退出登录"和"窗口隐藏"的语义不同。退出后 loading/registerPage 状态必须重置，否则下次进入时残留。

### 正确模式

```kotlin
// ✅ 外部持有 + 显式重置
var loginLoading by remember { mutableStateOf(false) }
LaunchedEffect(connectionState) {
    if (connectionState == DISCONNECTED) {
        loginLoading = false      // 重置 loading
        showRegister = false      // 重置注册页
    }
}

// ✅ key() 强制重建
key(windowScreen) {
    SubWindow(screen = windowScreen, ...)
}
```

---

## 5. 代码共享约束

### 规则

1. **commonMain 零平台依赖**：不能用 `java.io.File`、`java.net.URL` 等 JVM 专属 API（除非只有 JVM target 且标注）
2. **平台差异用 expect/actual 或注入模式**：不要在 commonMain 写 `if (platform == "android")`
3. **媒体能力用 ChatMediaConfig 收敛**：不要给 ChatPanel 加分散的 lambda 参数

### 共享边界

| 共享 | 不共享 |
|------|--------|
| UI Screen Composable | 导航（Android NavHost / Desktop 三栏） |
| ViewModel | 媒体选择（ActivityResult / FileDialog） |
| Repository | 数据库驱动（AndroidSqliteDriver / JdbcSqliteDriver） |
| LocalCache 接口 + 逻辑 | 日志输出（logcat / FileWriter） |

---

## 6. 文件大小约束

### 规则

- **单文件建议不超过 500 行**：超过时优先考虑按职责拆分
- 不是硬性限制，仅在职责边界明显时拆分（避免无意义拆分增加导航成本）

### 例外

- `TestHttpServer.kt`（619行）：测试基础设施，不参与生产构建，可接受
- `architecture.md`（700+行）：文档，不适用
