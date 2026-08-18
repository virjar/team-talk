# 开发环境指南

## 前置依赖

- JDK 17+
- Docker / Docker Compose（需要启动本地服务端时）
- Android SDK（构建 Android 时）

## 两种开发回路

### 业务功能：连接 Demo

客户端始终连接 `gradle/profiles/demo.json` 定义的 Demo。这是默认的功能开发与验收路径：

```bash
./gradlew :desktop:runDemo
./gradlew :android:assembleDebug
./gradlew :server:demoTest
```

`runDemo` 会启动 Desktop 测试 HTTP 服务（`127.0.0.1:18080`），AI 可读取语义树、触发操作并截图。具体流程见 [AI 驱动测试](../../07-testing/ai-workflow.md)。

### 服务端内部调试：本地运行

开发数据库、存储引擎或协议边界时，可启动本地服务端：

```bash
docker compose up -d
./gradlew :server:run
./gradlew :server:test
```

本地服务端不是客户端的默认目标。它用于断点调试与确定性回归，不作为跨客户端业务验收的证据。

## 常用构建命令

```bash
./gradlew :shared:jvmTest
./gradlew :app:desktopTest
./gradlew :server:test
./gradlew :desktop:compileKotlinDesktop
./gradlew :android:compileDebugKotlin
./gradlew :desktop:packageReleaseDistributionForCurrentOS
./gradlew :android:assembleRelease
```

Desktop 和 Android 产物都固化 Demo 地址，不再有 flavor 或服务器切换 UI。

## 数据与日志

本地服务端数据默认位于项目根目录 `data/`，包含 PostgreSQL 卷、RocksDB、Lucene、文件存储与日志。Desktop 开发运行使用 `data/desktop`。

常用日志：

- 服务端：`data/logs/teamtalk.log` 和 `data/logs/traces/trace.log`
- Desktop：客户端数据目录下的 `logs/`
- Android：`adb logcat`

## IDEA 调试

- 服务端入口：`com.virjar.tk.ApplicationKt`
- Desktop 入口：`com.virjar.tk.MainKt`

从 IDE 直接运行 Desktop 时，请确保 JVM 参数与 `runDemo` 一致；日常功能验收优先使用 Gradle 任务，避免配置漂移。

## 常见问题

- Demo 用例失败：先确认 `im.virjar.com:5100` 可达，再查看 `server/build/reports/tests/demoTest/`。
- Desktop 无法启动：确认没有另一个实例占用数据目录或 18080 端口。
- 本地 PostgreSQL 连接失败：使用 `docker compose ps` 和服务端日志检查。
