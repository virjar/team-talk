# 开发环境指南

## 前置依赖

- JDK 17+
- Docker / Docker Compose（需要启动本地服务端时）
- Android SDK（构建 Android 时）

## 两种开发回路

### 业务功能：连接已配置服务器

客户端、安装包和远程测试都读取 `gradle/deployment.json`。主仓库默认连接公开实例；私有化 fork 修改该文件后，以下命令会自然切换到自己的服务器：

```bash
./gradlew :desktop:run
./gradlew :android:assembleDebug
./gradlew :server:acceptanceTest
```

`:desktop:run` 同时启动测试 HTTP 服务（`127.0.0.1:18080`），AI 可以读取语义树、执行操作并截图。具体流程见 [AI 驱动测试](../../07-testing/ai-workflow.md)。

### 服务端内部调试：本地运行

开发数据库、存储引擎或协议边界时，可以启动本地服务端：

```bash
docker compose up -d
./gradlew :server:run
./gradlew :server:test
```

本地服务端用于断点调试和确定性回归，不会自动改变客户端目标，也不作为跨客户端业务验收的证据。如需让客户端连接本地服务端，应修改 `gradle/deployment.json`，并在完成后恢复或提交适合当前 fork 的配置。

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

Desktop 和 Android 产物都会固化当前部署地址，不提供 flavor 或运行时服务器切换 UI。这样每个 fork 的安装包都能明确对应它自己的服务端。

## 数据与日志

本地服务端数据默认位于项目根目录 `data/`，包含 PostgreSQL 卷、RocksDB、Lucene、文件存储与日志。Desktop 开发运行使用 `data/desktop`。

- 服务端：`data/logs/teamtalk.log` 和 `data/logs/traces/trace.log`
- Desktop：客户端数据目录下的 `logs/`
- Android：`adb logcat`

## IDEA 调试

- 服务端入口：`com.virjar.tk.ApplicationKt`
- Desktop 入口：`com.virjar.tk.MainKt`

从 IDE 直接运行 Desktop 时，应设置与 `:desktop:run` 相同的 JVM 参数。日常功能验收优先使用 Gradle 任务，避免配置漂移。

## 常见问题

- 远程验收失败：确认 `deployment.json` 中的 HTTP/TCP 端点可达，再查看 `server/build/reports/tests/acceptanceTest/`。
- Desktop 无法启动：确认没有另一个实例占用数据目录或 18080 端口。
- 本地 PostgreSQL 连接失败：使用 `docker compose ps` 和服务端日志检查。
