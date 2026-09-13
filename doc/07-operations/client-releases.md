# 客户端发布与更新体系（统一发布注册中心）

本文是 TeamTalk 客户端发行、更新与运维管控的权威文档。统一发行流程入口见
[releasing.md](releasing.md)；桌面交叉打包细节见 [desktop-cross-build.md](desktop-cross-build.md)。

## 1. 体系总览

2026-09 起客户端发布从「Conveyor 静态更新站点 + SFTP 直写」迁移为**服务端发布注册中心**：

```
构建机（CI/本机）                TeamTalk 服务端（单体）                 客户端
┌────────────────┐   multipart  ┌──────────────────────────┐   check/manifest  ┌──────────┐
│ ./gradlew release ├──────────►│ POST /api/v1/client/…    │◄─────────────────►│ 桌面壳    │
│  密封 bundle    │  发布令牌    │  发布注册中心（DB+对象仓） │   文件级增量下载   │ bootstrap │
└────────────────┘             │  管理台 /admin#/releases  │                  │ tt-agent  │
                               └──────────────────────────┘                  └──────────┘
```

- **发布注册中心**（服务端 `application/clientrelease`）：`client_release` /
  `client_release_file` / `client_channel` 三张表 + 内容寻址文件仓
  `data/release-store/<sha前2位>/<sha>`。发布行不可变（snapshot 同身份原地覆盖），
  通道（stable/preview/snapshot）是独立指针，停用/回滚只改指针或状态。
- **覆盖面**：desktop（mac 双架构 / windows-amd64 / linux-amd64）、android、
  headless（tt-agent / tt / tt-mcp）。schema 预留 ios/server。
- **版本语义**：`gradle.properties` 仍是唯一事实源。desktop 负载构建号 = desktopRevision
  （正式 = buildNumber+1；snapshot = 提交历史推导，保证通道内单调）；android =
  versionCode（buildNumber+1）；headless = releaseBuildNumber。

## 2. 公开端点（只读，与旧静态下载同级信任）

| 端点 | 用途 |
|---|---|
| `GET /api/v1/client/updates/check?client=&platform=&arch=&channel=&version=&build=&shellAbi=` | 更新检查；返回 UP_TO_DATE / UPDATE_AVAILABLE / SHELL_UPDATE_REQUIRED / CHANNEL_DISABLED + 发布信息 |
| `GET /api/v1/client/releases/{id}/manifest.json` | 负载文件清单（path/sha256/size） |
| `GET /api/v1/client/files/{sha256}` | 内容寻址制品（Range 断点续传、immutable 缓存） |
| `GET /api/v1/public/downloads` | 中文下载页数据源 |
| `GET /downloads` | 中文「下载与更新」页（注册中心数据，风格与首页一致） |
| `GET /downloads/android.json`、`/downloads/TeamTalk-android.apk` | **兼容层**：注册中心优先，旧收据目录兜底（存量 Android 客户端无感） |
| `/downloads/desktop/**` | 冻结的 Conveyor 遗留站点（只读保留，存量桌面客户端链接不断） |

更新检查不比较语义化版本：服务端下发目标（version+build），客户端服从指令——
**回滚=把通道指针切回旧版**，客户端会按指令降级。

## 3. 发布通道与运维管控

管理台「客户端发布」页（`/admin#/releases`）能力：

- **上传发布包**（zip：`release.json` + `payload/` + `bundle/<file>` + `installers/`）；
  与 CI 的 `ClientReleasePublisher` 同一端点、同一格式。
- **启停/删除**：停用可指定回退目标；删除仅限已停用且无通道引用（清理未共享对象）。
- **通道切换/回滚**：每 端×平台×架构×通道 一个指针；清空指针 = 该通道停发。
- **通道 kill-switch**：`enabled=false` 后该端点所有客户端收到 CHANNEL_DISABLED，
  不提示不更新（重大故障总闸）。
- 全部 mutation 走管理鉴权 + 审计台账。

通道语义沿用 T030：stable/preview 发布不可变；**snapshot 同 version+build 可覆盖发布**
（内测刷包），覆盖后旧内容寻址对象不受影响。

## 4. 发布流程（构建侧）

```
./gradlew release -PreleaseTargets=site          # 需 TEAMTALK_CLIENT_RELEASE_TOKEN
./gradlew buildRelease                           # 仅本地密封目录（验证用）
./gradlew release -PreleaseMode=snapshot -PreleaseTargets=site   # 内测快照
```

`site` 目标现在经 HTTP API 上传（`ClientReleasePublisher`），不再 SFTP 直写下载目录；
服务端自身部署仍是 SFTP（`deployServer`，见 deployment.md）。发布令牌在服务端
`CLIENT_RELEASE_PUBLISH_TOKEN` 环境变量配置（≥16 字符，哈希存储），CI 走
Secret `CLIENT_RELEASE_PUBLISH_TOKEN`。

桌面产物（单机交叉打包，详见 desktop-cross-build.md）：mac 双架构 zip、
Windows setup.exe + 便携 zip、Linux deb + tar.gz、四目标 payload.zip。

## 5. 桌面更新模型（壳/负载分离）

```
安装目录（只读，极少更新）              用户目录（更新器唯一写入区）
TeamTalk.app/Contents/                 ~/.teamtalk-client/<appId>/versions/
├─ runtime/          JBR 21            ├─ current.properties   指针（原子切换）
├─ app/bootstrap.jar 零依赖引导器       ├─ <build>/payload.properties + 文件树
└─ app/seed-payload.zip 首装种子        └─ .staging-*（更新暂存，崩溃残留可清理）
```

- bootstrap（`client/desktop-bootstrap`）解析指针 → 校验 → URLClassLoader 装载
  `TeamTalkMain`；dev/裸 JVM 运行无壳上下文，自更新入口自动隐藏。
- 更新器（`client/shared` 的 `com.virjar.tk.shared.update`）：check → 本地清单 diff
  → **只下载变化文件** → 全量 sha256 校验（失败整体回退）→ 暂存落位 →
  `current.properties` 临时文件+rename 原子切换 → 提示重启。任意旧版本直达最新，
  无增量链维护。
- 壳 ABI：bootstrap 带 `shellAbi`；负载 `minShellAbi` 更高时转 SHELL_UPDATE_REQUIRED
  （下载新首装包 = 全量升级）。
- 入口：桌面「设置 → 通用 → 检查更新」（仅打包形态显示）。

## 6. 无头 / CLI 升级

```
tt-agent upgrade [--channel stable|preview|snapshot] [--server-url <url>] [--prefix <dir>]
```

check → 全量 bundle 下载（ETag=sha256 校验）→ 复用 `upgrade-bundle` 原子切换；
systemd 场景重启服务后生效。`--server-url` 或 `TK_SERVER_URL` 必填之一。

## 7. 迁移说明（从 Conveyor）

- 存量桌面客户端内嵌 Sparkle/AppInstaller/apt 更新器：旧站点目录冻结保留，
  检查更新静默重试不崩溃；**需手动全量下载新包一次**进入新体系。
- 存量 Windows（MSIX）用户：新装后请在「设置→应用」卸载旧版。
- 存量 Android：`android.json` 契约未变，无感。
- 服务端升级后注册中心为空：`android.json`/APK 自动回落旧收据目录；
  首次 `release -PreleaseTargets=site` 后注册中心接管。
- 未做（边界）：字节级补丁（jar 已压缩，收益低）、manifest 数字签名
  （HTTPS+sha256 基线，ed25519 为后续加固项）、iOS（schema 预留）。

## 8. 验收要点

- `check` 三态 + 通道禁用/回滚/降级指令（`ClientReleaseRegistryTest`）。
- manifest/制品 Range 与 404 诚实性；android.json 兼容与兜底。
- 管理端鉴权 + 审计；上传哈希；snapshot 覆盖；stable 不可变 409。
- 更新器增量语义（未变文件零下载）与损坏回退（`DesktopUpdaterTest`）。
- 桌面壳冒烟：headless 启动到 Compose 阶段（种子提取/类加载全链路），
  GUI 窗口渲染需真机验收（见 deployment-acceptance.md 增补）。
