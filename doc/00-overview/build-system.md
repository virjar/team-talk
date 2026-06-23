# 构建系统与 Profile 体系

> TeamTalk 的多渠道构建通过 Profile 系统实现。Profile 定义了服务端地址、TCP 连接、部署配置等，
> 让同一套代码可以构建出连接不同环境的客户端和服务端产物。

## 什么是 Profile

Profile 是一个构建渠道定义。每个 Profile 包含：

| 字段 | 说明 | 示例 |
|------|------|------|
| `name` | 渠道名称，用于 Gradle 任务命名 | `dev` / `demo` / `mycompany` |
| `serverUrl` | HTTP 服务端地址 | `https://im.virjar.com` |
| `tcpAddress` | TCP 连接地址（host:port） | `im.virjar.com:5100` |
| `allowCustomServer` | 客户端是否显示服务端地址编辑 | `true`（开发）/ `false`（生产） |
| `deploy` | 部署配置（SSH 主机/用户/路径） | `{ "host": "im.virjar.com" }` |
| `ssl` | SSL 配置（HTTPS 端口） | `{ "port": 443 }` |

## Profile 定义格式

Profile 是 JSON 文件，放在 `gradle/profiles/` 目录：

```json
{
  "name": "demo",
  "serverUrl": "https://im.virjar.com",
  "tcpAddress": "im.virjar.com:5100",
  "allowCustomServer": true,
  "deploy": { "host": "im.virjar.com" },
  "ssl": { "port": 443 }
}
```

### 为什么用 JSON 而非 Kotlin 代码

此前 Profile 定义为 Kotlin 代码（`Profiles.kt` 中的 `listOf(...)`），享受编译器检查。但改为 JSON 的原因：

1. **私有化部署不修改源码**：外部部署直接加 JSON 文件，不需要修改 `Profiles.kt`
2. **配置本质是数据**：serverUrl/tcpAddress 是配置不是逻辑，JSON 是配置的行业惯例
3. **GitHub Actions 注入**：CI 可以通过 input 传入 JSON 字符串，构建前写入文件
4. **非技术人员友好**：运维人员可以编辑 JSON 不需要理解 Kotlin

### 为什么不用 Gradle 的 Build Flavor

Android 的 `productFlavors` 需要在 `build.gradle.kts` 中硬编码，同样面临"修改源码"问题。而且 Desktop 没有 Flavor 概念。Profile 系统统一覆盖 Android + Desktop + Server。

## 内置 Profile

| 文件 | 名称 | 用途 |
|------|------|------|
| `gradle/profiles/dev.json` | dev | 本地开发（localhost:8080） |
| `gradle/profiles/demo.json` | demo | 官方演示站（im.virjar.com） |
| `gradle/profiles/production.json` | production | 生产环境模板（your-server.com 占位） |

内置 Profile 入版本控制，作为模板和默认配置。

## 外部 Profile（私有化部署）

### 场景

你需要部署到自己的服务器 `im.mycompany.com`，不想修改上游源码。

### 方式一：本地构建

直接创建 JSON 文件放入 `gradle/profiles/`：

```bash
cat > gradle/profiles/mycompany.json << 'EOF'
{
  "name": "mycompany",
  "serverUrl": "https://im.mycompany.com",
  "tcpAddress": "im.mycompany.com:5100",
  "allowCustomServer": false,
  "deploy": { "host": "im.mycompany.com" }
}
EOF

./gradlew :android:assembleMycompanyRelease
./gradlew :desktop:packageReleaseDistributionForCurrentOS -Pprofile=mycompany
./gradlew deployServerMycompany
```

这个文件不提交到 Git（只存在于你的本地工作区或 fork）。

### 方式二：GitHub Actions 注入

在 GitHub Actions 的 Release workflow 中填入 `profile_json` 参数：

```json
{"name":"mycompany","serverUrl":"https://im.mycompany.com","tcpAddress":"im.mycompany.com:5100","deploy":{"host":"im.mycompany.com"}}
```

CI 在 checkout 后构建前，自动将这个 JSON 写入 `gradle/profiles/external.json`。构建完成后随 runner 销毁。**不存在于任何代码仓库，不需要 .gitignore。**

### 为什么不需要 .gitignore

外部 Profile 通过以下方式注入，从不进入代码仓库：
- 本地构建：放在工作区但不 commit
- GitHub Actions：运行时注入到临时目录，runner 销毁后消失

## Profile 如何注入构建

```
gradle/profiles/*.json
       │
       ▼
buildSrc/.../Profiles.kt: loadAllProfiles(dir)
       │ 从目录读取所有 .json 文件，解析为 BuildProfile 列表
       ▼
build.gradle.kts
       │ profileMap = profiles.associateBy { it.name }
       │ activeProfile = profileMap[-Pprofile]
       │ extra.set("allProfiles", profileMap)
       │ extra.set("activeProfile", activeProfile)
       ▼
android/build.gradle.kts / desktop/build.gradle.kts
       │ val allProfiles = rootProject.extra.get("allProfiles")
       │ 为每个 Profile 自动注册 build/upload/deploy/run 任务
       │ BuildConfig 注入 serverUrl/tcpHost/tcpPort
       ▼
客户端代码通过 BuildConfig 读取服务端地址
```

### -Pprofile 参数

```bash
# 使用 demo Profile（-Pprofile 指定，默认 dev）
./gradlew :desktop:run -Pprofile=demo

# 使用自定义 Profile
./gradlew :android:assembleMycompanyRelease -Pprofile=mycompany
```

默认值 `dev`（不设环境变量时）。

## 自动注册的 Gradle 任务

每个 Profile 自动生成以下任务（以 `demo` 为例）：

| 任务 | 说明 |
|------|------|
| `:desktop:runDemo` | 用 demo Profile 启动 Desktop（开发运行） |
| `:android:assembleDemoDebug` | 构建 demo Debug APK |
| `:android:assembleDemoRelease` | 构建 demo Release APK |
| `:desktop:packageReleaseDistributionForCurrentOS -Pprofile=demo` | 构建 demo Desktop 安装包（Desktop 打包任务名固定，通过 -Pprofile 选择） |
| `buildDemoRelease` | 聚合：构建 server + desktop + android 全部 demo 产物 |
| `deployServerDemo` | 部署服务端到 demo 配置的 SSH 主机 |
| `uploadDemoRelease` | 上传 demo 客户端到服务端 |

> **注意**：Desktop 打包任务是固定的 `packageReleaseDistributionForCurrentOS`，不带 Profile 前缀。
> Desktop 目前无源码级 flavor 隔离（F15 待实现），通过 `-Pprofile` 参数 + JVM 参数注入选择 Profile。
> Android 有真正的 `productFlavors`，任务名带 Profile 前缀（`assembleDemoRelease`）。

## CI/CD

### CI（ci.yml）

push/PR 时自动执行：
- 编译检查（shared + server + desktop）
- 服务端测试（`:server:test`）
- 协议编解码测试（`:shared:jvmTest` — ProtoRoundTripTest）
- 客户端测试（`:app:desktopTest`）

### Release（release.yml）

手动触发，支持以下输入：
- `build_profile`：Profile 名称（如 demo/production/mycompany）
- `profile_json`：外部 Profile JSON（私有化部署）

并行构建：
- Server 分发包（Linux）
- Desktop Linux deb / Windows msi / macOS dmg（arm64 + x86_64）
- Android APK

### Demo Smoke Test（demo-smoke.yml）

手动触发，连真实 demo 服务器跑协议级 E2E 冒烟测试。

## 如何添加新 Profile

### 内置 Profile（提交到代码仓库）

1. 创建 `gradle/profiles/myprofile.json`
2. 提交到 Git
3. `./gradlew ... -Pprofile=myprofile`

### 外部 Profile（不修改源码）

**本地**：创建 `gradle/profiles/myprofile.json`（不提交）

**GitHub Actions**：在 Release workflow 的 `profile_json` 参数填入 JSON

## 相关文件

| 文件 | 说明 |
|------|------|
| `gradle/profiles/*.json` | Profile 定义文件 |
| `buildSrc/.../profiles/BuildProfile.kt` | BuildProfile 数据模型 + JSON 解析 |
| `buildSrc/.../profiles/Profiles.kt` | 从目录加载所有 Profile |
| `build.gradle.kts` | Profile 系统初始化 + 环境变量选择 |
| `android/build.gradle.kts` | Android 任务自动注册 + BuildConfig 注入 |
| `desktop/build.gradle.kts` | Desktop 任务自动注册 |
| `.github/workflows/release.yml` | Release 构建（含外部 Profile 注入） |
| `.github/workflows/ci.yml` | CI 编译 + 测试 |
