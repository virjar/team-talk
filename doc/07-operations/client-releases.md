# 客户端发布与更新体系（统一发布注册中心）

本文是 TeamTalk 客户端发行、更新与运维管控的权威文档。统一发行流程入口见
[releasing.md](releasing.md)；桌面交叉打包细节见 [desktop-cross-build.md](desktop-cross-build.md)。

## 1. 体系总览

客户端发布由**服务端发布注册中心**管理：

```
构建机（CI/本机）                TeamTalk 服务端（单体）                 客户端
┌────────────────┐   multipart  ┌──────────────────────────┐   check/manifest  ┌──────────┐
│ ./gradlew release ├──────────►│ POST /api/v1/client/…    │◄─────────────────►│ 桌面壳    │
│  密封 bundle    │  发布令牌    │  发布注册中心（DB+对象仓） │   文件级增量下载   │ bootstrap │
└────────────────┘             │  管理台 /admin#/releases  │                  │ tt-agent  │
                               └──────────────────────────┘                  └──────────┘
```

- **发布注册中心**（服务端 `infra/clientrelease`）：`client_release` /
  `client_release_file` / `client_channel` 三张表 + 内容寻址文件仓
  `data/release-store/<sha前2位>/<sha>`。发布行与文件清单不可变，
  通道（stable/preview/snapshot）是独立指针，停用/回滚只改指针或状态。
  通道晋级不会重写安装包：已有 Desktop 客户端更新后继续订阅请求时的通道；首次安装仍订阅包内构建通道。
  因此把 snapshot 指向 stable 不能替代正式安装包的构建与验收。
- **覆盖面**：desktop（mac 双架构 / windows-amd64 / linux-amd64）、android、
  headless（tt-agent / tt / tt-mcp）。schema 预留 ios/server。
- **版本语义**：`gradle.properties` 仍是唯一事实源。desktop 负载构建号 = desktopRevision
  （正式 = buildNumber+1；snapshot = 提交历史推导，区分同展示版本的源码构建）；android =
  versionCode（buildNumber+1）；headless = releaseBuildNumber。`buildIdentity` 记录展示版本与完整源码 SHA；同号 snapshot
  仍能被识别为新的更新，不能只比较 version/build。

## 2. 公开端点（只读，与旧静态下载同级信任）

| 端点 | 用途 |
|---|---|
| `GET /api/v1/client/updates/check?client=&platform=&arch=&channel=&version=&build=&shellAbi=&buildIdentity=` | 更新检查；返回 UP_TO_DATE / UPDATE_AVAILABLE / SHELL_UPDATE_REQUIRED / CHANNEL_DISABLED + 发布信息 |
| `GET /api/v1/client/releases/{id}/manifest.json` | 负载文件清单（path/sha256/size） |
| `GET /api/v1/client/files/{sha256}` | 内容寻址制品（Range 断点续传、immutable 缓存） |
| `GET /api/v1/public/downloads` | 首页下载区的数据源：当前通道制品与最近版本记录 |
| `GET /`（`#download` 区域） | 首页直接展示六个目标的真实下载按钮；优先选择有可下载制品的 stable、preview、snapshot，可切换通道；支持 HEAD |
| `GET /css/home.css`、`GET /js/downloads.js`、`GET /js/qrcode.min.js` | 首页固定资源，支持 HEAD；由 Ktor 直接提供，不依赖反向代理静态映射 |
| `GET /downloads/android.json`、`/downloads/TeamTalk-android.apk` | **兼容层**：注册中心接管前兼容旧收据；接管后停用/缺文件返回 404 |
| `/downloads/desktop/**` | 冻结的 Conveyor 遗留站点（只读保留，存量桌面客户端链接不断） |

独立下载页 `/downloads`、`/downloads/`、`/downloads/index.html` 已移除，GET/HEAD 均返回 404；
目录中遗留的旧 HTML 不会重新公开，安装包、Android 收据和 Conveyor 更新路径不受影响。
首页及 CSS/JS 优先使用安装根目录 `static/` 中的对应文件，缺失时读取包内 `static/` 资源。
固定资源均声明 UTF-8 与 `Cache-Control: no-cache`；未知路径不会回退到首页。
Android 卡片本地生成二维码，内容与所选通道的 APK 下载按钮地址一致；没有可下载安装包时不显示二维码。
二维码不调用外部服务，生成库的来源、许可与重建命令见[二维码资源说明](../../scripts/vendor/home-qrcode.md)。
Windows/macOS 卡片长期保留默认折叠的“安装被拦截？”说明，提示当前未签名/未公证状态，并链接
[微软安装说明](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/publish-first-app#step-6-handle-smartscreen-for-new-apps)与
[Apple 指南](https://support.apple.com/zh-cn/102445)。说明针对单次安装，不要求关闭系统整体防护；
组织策略阻止、恶意软件或损坏提示需分别处理，不能把所有阻止都当作可跳过的来源提示。
macOS 另提供终端方式：确认来源后，对已放入“应用程序”的公版应用运行
`xattr -dr com.apple.quarantine "/Applications/TeamTalk.app"`，清除该应用的下载隔离属性后再打开。
自定义安装名称或位置时替换路径；这不会补上签名或公证，不需要进入恢复模式、关闭 SIP 或全局禁用 Gatekeeper。

更新检查不比较语义化版本：服务端下发目标（version+build+buildIdentity），客户端服从指令——
**回滚=把通道指针切回旧版**，客户端会按指令降级。

## 3. 发布通道与运维管控

管理台「客户端发布」页（`/admin#/releases`）能力：

- **上传发布包**（zip：`release.json` + `payload/` + `bundle/<file>` + `installers/`）；
  与 CI 的 `ClientReleasePublisher` 同一端点、同一格式。
- **启停/删除**：停用可指定回退目标；删除仅限已停用且无通道引用。内容对象保留，不在删除记录时物理回收，以免删除并发导入已复用的文件。
- **通道切换/回滚**：每 端×平台×架构×通道 一个指针；清空指针 = 该通道停发。
- **通道 kill-switch**：`enabled=false` 后该端点所有客户端收到 CHANNEL_DISABLED，
  不提示不更新（重大故障总闸）。
- 全部 mutation 走管理鉴权 + 审计台账。

以 stable/preview 上传的发布行，其相同版本与构建号不接受不同字节；snapshot 的新 `buildIdentity` 新建发布行，
激活时仅切换通道指针。相同上传原字节可幂等重试，不会重新启用已停用的通道或覆盖人工回滚。
Desktop 客户端继续跟随自己查询的通道；把 preview 发布晋级到 stable 不会把已有 stable 桌面客户端改订 preview。
显式切换通道可以选择已有发布，不会将 snapshot 的上传身份改写为正式发行。

## 4. 发布流程（构建侧）

```
./gradlew release -PreleaseTargets=site          # 需 TEAMTALK_CLIENT_RELEASE_TOKEN
./gradlew buildRelease -PreleaseMode=snapshot     # 开发期仅本地密封目录（不冻结协议）
./gradlew release -PreleaseMode=snapshot -PreleaseTargets=site   # 内测快照
```

`site` 目标现在经 HTTP API 上传（`ClientReleasePublisher`），不再 SFTP 直写下载目录；
服务端自身部署仍使用 SSH/rsync（`deployServer`，见 deployment.md）。发布令牌在服务端
`CLIENT_RELEASE_PUBLISH_TOKEN` 环境变量配置（≥16 字符，进程内仅持有用于校验的摘要），CI 走
Secret `CLIENT_RELEASE_PUBLISH_TOKEN`。

桌面产物（单机交叉打包，详见 desktop-cross-build.md）：mac 双架构 zip、
Windows setup.exe + 便携 zip、Linux deb + tar.gz、四目标 payload.zip。

## 5. 桌面更新模型（壳/负载分离）

```
安装目录（只读，极少更新）              用户目录（更新器唯一写入区）
TeamTalk.app/Contents/                 ~/.teamtalk-client/<appId>/versions/
├─ runtime/          JBR 21            ├─ current.properties   指针（原子切换）
├─ app/bootstrap.jar 零依赖引导器       ├─ <内容摘要>/payload.properties + 文件树
└─ app/seed-payload.zip 首装种子        └─ .staging-*（更新暂存，崩溃残留可清理）
```

- bootstrap（`client/desktop-bootstrap`）解析指针 → 校验 → URLClassLoader 装载
  `TeamTalkMain`；dev/裸 JVM 运行无壳上下文，自更新入口自动隐藏。
- 更新器（`client/shared` 的 `com.virjar.tk.shared.update`）：check → 本地清单 diff
  → **只下载变化文件** → 全量 sha256 校验（失败整体回退）→ 暂存落位 →
  `current.properties` 临时文件+rename 原子切换 → 提示重启。任意旧版本直达最新，
  无增量链维护。
- 更新会话保留展示给用户的完整发布。点击更新时再次检查通道；目标已切换、被停用或改为需要新壳时，
  要求重新检查，不会静默安装另一个发布。增量大小由本地文件比对得出，不使用包含安装器的发布总大小。
- 重启复用应用正常退出入口，等待草稿与会话资源关闭；新壳等待旧 PID 退出后才启动，避免争抢单实例锁。
- 新安装包的种子只在该种子首次出现时接管；后续启动保留应用内已更新的负载。相同构建号也不会删除运行目录。
- 壳 ABI：bootstrap 带 `shellAbi`；负载 `minShellAbi` 更高时转 SHELL_UPDATE_REQUIRED
  （下载新首装包 = 全量升级）。
- 入口：桌面「设置 → 通用 → 检查更新」（仅打包形态显示）。

## 6. 无头 / CLI 升级

```
tt-agent upgrade [--channel stable|preview|snapshot] [--server-url <url>] [--prefix <dir>]
```

当前包校验 → check → 全量 bundle 下载（核对内容地址 SHA-256 与包内 buildIdentity）→ 复用 `upgrade-bundle` 原子切换；
默认跟随包内通道，`--channel` 可显式选择。systemd 场景重启服务后生效。`--server-url` 或 `TK_SERVER_URL` 必填之一。
在线升级、离线安装和 doctor 使用同一 `BundleFacts` 清单解析；安装过程仍在复制后完整校验，
复制得到的文件与首次校验事实不符时，保留原 `current`。旧包未记录通道时默认 `stable`。

无头客户端默认从当前安装包读取通道。若将 snapshot/preview 包晋级后供 stable 更新，
升级后的默认通道也会随包改变；需要固定订阅的脚本应每次显式传入 `--channel stable`。

## 7. 迁移说明（从 Conveyor）

- 存量桌面客户端内嵌 Sparkle/AppInstaller/apt 更新器：旧站点目录冻结保留，
  检查更新静默重试不崩溃；**需手动全量下载新包一次**进入新体系。
- 存量 Windows（MSIX）用户：先退出旧版并安装新包，核对原账号、资料与草稿后再卸载旧版；
  既有受控包使用非虚拟化数据路径，更早虚拟化目录不得直接删除。
- 存量 Android：`android.json` 契约未变，无感。
- 服务端升级后注册中心为空：`android.json`/APK 自动回落旧收据目录；
  首次按对应发行模式上传后注册中心接管；之后停用不会重新暴露旧收据中的安装包。
- 未做（边界）：字节级补丁（jar 已压缩，收益低）、manifest 数字签名
  （HTTPS+sha256 基线，ed25519 为后续加固项）、iOS（schema 预留）。

## 8. 验收要点

- `check` 更新状态 + 通道禁用/回滚/降级指令（`ClientReleaseRegistryTest`）。
- manifest/制品 Range 与 404 诚实性；android.json 兼容与兜底。
- 管理端鉴权 + 审计；上传哈希；同号 snapshot 新身份与旧 manifest 保留；原字节重试；不同字节冲突 409。
- 更新器增量语义（未变文件零下载）与损坏回退（`DesktopUpdaterTest`）。
- 桌面壳冒烟：headless 启动到 Compose 阶段（种子提取/类加载全链路），
  GUI 窗口渲染需真机验收（见 deployment-acceptance.md 增补）。
