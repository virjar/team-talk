# 日志体系

> TeamTalk 客户端日志体系：从 logback 迁移到自研轻量方案，
> 按用途分级（非 severity），HTTP 上传替代 TCP，Crash 持久化保证不丢。

## 目录

- [1. 设计决策](#1-设计决策)
- [2. 日志分级](#2-日志分级)
- [3. AppLog API](#3-applog-api)
- [4. 本地文件（LocalLogFile）](#4-本地文件locallogfile)
- [5. HTTP 上传（HttpLogUploader）](#5-http-上传httploguploader)
- [6. Crash 持久化（CrashDumper）](#6-crash-持久化crashdumper)
- [7. TkLogger 桥接](#7-tklogger-桥接)
- [8. 第三方库日志处理](#8-第三方库日志处理)
- [9. 服务端接收](#9-服务端接收)
- [10. 为什么移除 logback](#10-为什么移除-logback)

---

## 1. 设计决策

### 不用 severity 级别

传统日志用 debug/info/warn/error，但在客户端语义不明确：
- RPC 失败是 error 还是 info？
- 重连失败是 warn 还是 error？

severity 混淆了"问题严重程度"和"上传策略"两个维度。TeamTalk 按用途分类，直接对应上传策略。

### netty 日志完全丢弃

netty 是异步框架，框架本身日志对排查几乎无价值——异常都是业务代码使用不当。不绑 SLF4J 实现，第三方库日志 NOP 静默丢弃。业务异常通过拦截点手动收集。

### HTTP 替代 TCP 上传

TCP 上传存在设计悖论：最需要日志的 TCP 故障场景恰恰上传不了。HTTP 通道独立于 TCP 连接状态，更可靠。

---

## 2. 日志分级

| 级别 | 语义 | 上传策略 | 场景 |
|------|------|---------|------|
| `trace` | 业务流程日志 | Demo: 自动上传；正式: 仅本地 | 连接、鉴权、发消息、收通知 |
| `fault` | 故障/异常日志 | 始终触发上传（debounce 3s） | crash、未捕获异常、非预期状态 |
| `snapshot` | 状态快照 | 仅用户触发反馈时 | 会话列表、连接状态 dump |

### 上传策略按版本区分

| 场景 | Demo 版本 | 正式版本 |
|------|----------|---------|
| 日常 trace | 定时自动上传（5min） | 仅本地文件 |
| fault/异常 | 触发强制上传 | 触发强制上传 |
| 用户反馈 | — | 手动打包上传最近日志 |

---

## 3. AppLog API

```kotlin
object AppLog {
    fun trace(tag: String, msg: String)
    fun fault(tag: String, msg: String, throwable: Throwable? = null)
    fun snapshot(tag: String, msg: String)
}
```

### 使用示例

```kotlin
// 业务流程日志
AppLog.trace("ImClient", "TCP connected to $host:$port")
AppLog.trace("AuthController", "Login success: uid=$uid")

// 故障日志
AppLog.fault("ImClient", "Auth failed: code=$code", exception)
AppLog.fault("Crash", "Uncaught exception", throwable)

// 状态快照（反馈功能）
AppLog.snapshot("Debug", "Conversations: ${conversations.size}, state=$connectionState")
```

### 内部结构

```
AppLog.trace/fault/snapshot
  │
  ├── platformLog()          → 本地文件（Desktop）/ logcat（Android）
  │
  └── traceBuffer/faultBuffer → LogBuffer（环形缓冲区）
                                   │
                                   └── HttpLogUploader
                                         ├── fault → trigger()（debounce 3s）
                                         └── trace → 定时 5min 批量
```

---

## 4. 本地文件（LocalLogFile）

Desktop 平台用极简 FileWriter 替代 logback：

```kotlin
internal object LocalLogFile {
    // 按天单文件
    private val logDir = File("~/.teamtalk/desktop/logs")
    
    fun append(level: String, tag: String, msg: String, throwable: Throwable?) {
        writer.println("$timestamp|$level|$tag|$msg")
        throwable?.printStackTrace(writer)
        writer.flush()
    }
    
    // 自动清理 7 天前的日志
    fun cleanOldLogs(maxDays: Long = 7)
}
```

### 日志格式

```
2026-06-21T10:30:00.123|trace|ImClient|TCP connected to im.virjar.com:5100
2026-06-21T10:30:01.456|fault|AuthController|Auth failed: AUTH_FAILED
  at com.virjar.tk.client.AuthController.handleAuthResponse(AuthController.kt:45)
  ...
```

`|` 分隔，便于检索。不用 JSON（stacktrace 内嵌 JSON 丑）。

---

## 5. HTTP 上传（HttpLogUploader）

替代旧的 TCP LogUploader，解决 TCP 断连悖论。

### 触发条件

| 条件 | 行为 |
|------|------|
| fault 日志写入 | debounce 3s 后批量上传（避免连续异常打满 HTTP） |
| 定时（5min） | 批量上传 trace（Demo 版本） |
| 用户手动触发 | 打包上传最近日志（正式版本反馈功能） |
| 应用启动 | 优先上传上次 crash 的 pending 日志 |

### 上传流程

```
1. 从 traceBuffer 和 faultBuffer drain 出日志文本
2. 合并（TRACE 段 + FAULT 段）
3. GZIP 压缩
4. HTTP POST /api/client-logs
   Headers: Content-Type: application/gzip, X-Device-Id: xxx
5. 失败 → 落本地 pending 文件（CrashDumper.flushPending）
```

### 鉴权

当前无鉴权（与文件上传一致）。TODO：后续补充设备签名。

---

## 6. Crash 持久化（CrashDumper）

进程死亡前做原子写，下次启动优先上传。

### 原子写

```kotlin
fun flushPending(content: String) {
    tmpFile.writeText(content)    // 先写 .tmp
    tmpFile.renameTo(pendingFile) // 再 rename（同文件系统内原子操作）
}
```

### 平台差异

**Desktop（JVM 单进程）：**
```kotlin
Thread.setDefaultUncaughtExceptionHandler { _, e ->
    AppLog.fault("Crash", "Uncaught exception", e)
    crashDumper.flushToPending()           // 原子写
    crashDumper.tryUploadSync(3000)        // 同步上传尝试（3s 超时）
}
// 进程未死，有 3-5 秒窗口做同步上传
```

**Android（主进程崩溃无法网络）：**
```kotlin
Thread.setDefaultUncaughtExceptionHandler { _, e ->
    AppLog.fault("Crash", "Uncaught exception", e)
    crashDumper.flushPending(...)          // 原子写
    // 进程即将死亡，不能做网络请求
}
// 下次启动 → uploadPending() 优先上传
```

---

## 7. TkLogger 桥接

shared 模块（ImClient/RpcClient）的日志通过 TkLogger 接口输出，由宿主控制去向。

```kotlin
// shared 模块内部使用
private val logger = TkLoggerFactory.get("ImClient")
logger.trace("TCP connected")
logger.fault("Connection error", cause)
```

```kotlin
// 客户端注入（Desktop/Android 启动时）
TkLoggerFactory.install { name -> AppLogTkLogger(name) }

// AppLogTkLogger 把 TkLogger 调用转发到 AppLog
class AppLogTkLogger(private val name: String) : TkLogger {
    override fun trace(msg: String) = AppLog.trace(name, msg)
    override fun fault(msg: String, t: Throwable?) = AppLog.fault(name, msg, t)
}
```

---

## 8. 第三方库日志处理

### netty / ktor

不绑 SLF4J 实现 → NOP（静默丢弃）。理由：netty 异步框架的日志对排查无价值，异常都是业务代码使用不当。

### 异常拦截点

不依赖第三方库自己的日志，而是在业务代码的关键位置拦截异常：

| 拦截点 | 代码位置 | 说明 |
|--------|---------|------|
| JVM UncaughtException | Desktop Main.kt | 所有未捕获异常 |
| Android UncaughtException | TeamTalkApp.kt | crash 持久化 |
| CoroutineExceptionHandler | ImClient.scope | 协程未捕获异常 |
| Netty exceptionCaught | ImClient pipeline | 业务 handler 异常 |

---

## 9. 服务端接收

```kotlin
// server/.../api/ClientLogRoutes.kt
post("/api/client-logs") {
    val deviceId = call.request.header("X-Device-Id") ?: "anonymous"
    val raw = call.receiveStream().readBytes()
    // GZIP 解压（失败则当明文）
    val text = GZIPInputStream(raw.inputStream()).bufferedReader().readText()
    clientLogStore.store(deviceId, text)
}
```

存储路径：`$dataRoot/client-logs/{uid}/{deviceId}/{date}.log`

---

## 10. 为什么移除 logback

### 问题

logback 写的 `app.log` 是死路径（无人读取），但拖累了整个依赖链：

| 组件 | 大小 | 用途 | 客户端需要？ |
|------|------|------|------------|
| logback-classic + logback-core | ~900K | 写本地 app.log | ❌ |
| slf4j-api | ~65K | 日志门面 | △ 第三方库传递依赖 |
| java.xml 模块 | 15M | logback SAX 解析 | ❌ |

### 收益

| 指标 | 移除前 | 移除后 |
|------|--------|--------|
| logback jar | ~900K | 0 |
| java.xml 模块 | 15M | 0 |
| jlink modules 总大小 | 29M | ~14M |
| 日志上传可靠性 | TCP（断连即失） | HTTP（独立于 TCP） |

slf4j-api 保留（第三方库传递依赖），不绑实现 = NOP。
