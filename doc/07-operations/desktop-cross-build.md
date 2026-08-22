# Desktop 交叉打包调研

> 结论先行：**jpackage 官方不支持交叉打包**（OpenJDK 明确立场，见
> [JDK-8213087](https://bugs.openjdk.org/browse/JDK-8213087)：每种安装包格式必须在
> 目标 OS 上构建——MSI 依赖 WiX 的 candle/light.exe（仅 Windows），DMG 依赖 macOS）。
> 但存在成熟替代：**jlink 可交叉生成任意平台 runtime**，**launch4j 可在任意平台生成
> Windows exe**，**Hydraulic Conveyor 可以在单机上产出三平台完整安装包**。

## 现状

- `desktop/nativeDistributions` 声明 `Dmg / Msi / Deb`，走 jpackage
  （`packageDistributionForCurrentOS` 只出当前 OS 的包）。
- `release.yml` 用 GitHub Actions 三平台 matrix worker 生成全部产物——可用但发布
  流程重，本地无法一键出全套。

## 技术事实（可交叉 / 不可交叉）

| 环节 | 交叉支持 | 说明 |
|------|---------|------|
| Kotlin/JVM 编译产物（jar） | ✅ 天然跨平台 | 字节码一致，native 依赖按目标裁剪（现有 `stripSqliteNativeForRelease` 已按 OS 处理） |
| jlink runtime image | ✅ | 用**目标平台 JDK 的 jmods** 即可在任意主机产出目标平台 runtime（[验证讨论](https://www.reddit.com/r/java/comments/a652aj/jpackage_packaging_tool_build_0/)；[JReleaser 跨平台 jlink 示例](https://jreleaser.org/guide/latest/examples/jlink/cross-platform-jlink.html)、[Badass JLink 插件](https://badass-jlink-plugin.beryx.org/releases/2.17.0/)） |
| Windows exe（壳） | ✅ | [launch4j](https://launch4j.sourceforge.net/) 明确支持在 Linux/macOS 上生成 Windows exe |
| Windows MSI | ⚠️ 间接 | WiX 可在 Docker 里跑：[jkroepke/docker-wixtoolset](https://github.com/jkroepke/docker-wixtoolset/)（WiX 5 on Linux）；或 Wine 跑 jpackage.exe（脆弱，不推荐） |
| Linux deb/rpm | ✅ | Docker 容器内 fpm/dpkg，Mac 上直接可用 |
| macOS DMG | ⚠️ | 仅 macOS 主机（hdiutil）；Mac 本机天然满足 |
| 安装包（jpackage 整体） | ❌ | 官方不支持跨平台（上述 JDK 立场） |

## 方案对比

### 方案 A：Hydraulic Conveyor（推荐首选试验）

[Conveyor](https://conveyor.hydraulic.dev/21.1/package-formats/) 是专为 JVM/Compose
Desktop 设计的发布工具，**在一台机器上生成三平台的签名安装包**。注意其格式选择
与 jpackage 默认不同（实测核对官方文档）：

- Windows：**MSIX** + .appinstaller + ~500KB 安装 EXE + zip（**不出 MSI**；
  MSIX 为 Win10 1607+ 内置的现代格式，64KB 分块、支持增量更新与轻量容器化）
- macOS：**签名+公证+Sparkle 化的 zip**（Intel/ARM 分包、appcast、delta 增量；
  **官方明确不出 DMG**——理由是 DMG 挂载校验慢于下载、拖拽安装步骤多）
- Linux：**deb + tar.gz**（输出目录即 apt 仓库；rpm/snap/flatpak 是 roadmap）

内置：

- **自更新零应用代码、零驻留进程**（官方 "No code changes necessary"）：macOS 由
  打包期嵌入 .app 的 Sparkle 原生框架在 app 进程内完成（轮询站点 appcast → 后台
  下载校验 → 退出时覆盖）；Windows 由 OS 部署引擎执行（默认每 8h 检查
  .appinstaller，日志见 Event Viewer AppXDeployment-Server）；Linux 走 apt。
  可选 JVM/Electron/Native Update Control API 做主动控制（如 UI 内检查更新按钮），
  用户机器上不存在任何 Conveyor 运行时；
- 各平台 runtime 预制与 jlink 交叉（等价方案 B 的自动化版）；
- Windows 签名、macOS 公证（Apple 凭据进配置）；
- **自更新（Sparkle/MSIX 式 delta 更新）**——IM 客户端迟早需要；
- JetBrains 官方 Compose 文档推荐，有
  [KMP starter 模板](https://github.com/hydraulic-software/compose-multiplatform-starter)
  和[上手教程](https://dev.to/coltonidle/how-to-use-hydraulic-conveyor-with-kmp-compose-for-desktop-id4)。

成本与风险：

- **授权**：开源项目免费（需设 `app.vcs-url` 指向开源仓库 + 下载页展示 Conveyor
  标识）；闭源商用付费。TeamTalk 为开源项目（私有化部署不影响），**免费适用**；
- **纯本地工具，非云服务**（官方 FAQ「Why isn't Conveyor a service?」）：构建在
  本机执行，代码不经过 Hydraulic（"We never see your code"）；构建期会从公开
  CDN 下载目标平台 JDK 等并缓存（非 Hydraulic 专属服务）；
- **更新分发零依赖其服务器，且零新增进程**（项目硬约束：单进程收敛）：产物是
  纯静态目录（包 + 更新元数据 + 下载页），「just need somewhere that does static
  file hosting」——TeamTalk 服务端 JVM（Ktor）已在进程内 serve `static/downloads/`
  （`/downloads/{filename}`），把 Conveyor 输出目录放进该静态路由即得完整更新
  站点，不需要 nginx/CDN/任何新守护进程；客户端侧更新也不是进程（Sparkle 为
  app 内嵌框架、MSIX 走 Windows 系统机制、apt 走系统包管理器）；
- **格式取舍**：若坚持 MSI+DMG 形态则不满足（MSIX+zip 是其替代方案）；私有化
  IM 客户端的分发格式是自定选项，MSIX/zip 属可接受的现代形态；
- 与现有自定义打包任务（`compressRuntimeImage` 的 jlink `--compress=2`、字体裁剪、
  sqlite native 裁剪）语义重叠，需要迁移到 conveyor.conf 验证等价；
- 采用 = 发布链路换轨，建议先用 demo 产物验证一轮再决定。

### 方案 B：自研交叉链（gradle 任务化）

Mac 主机上一键产出全套：

1. `jlink --module-path <目标平台JDK>/jmods` ×3 → windows/linux/macos runtime
   （现有 `compressRuntimeImage` 已是 jlink 二次加工，扩展 target 参数成本低）；
2. Windows：launch4j 出 exe（含图标/JRE 指向）；MSI 需要再走 Docker WiX，或降级为
   `exe + zip` 分发；
3. Linux：Docker 跑 fpm 出 deb（或 tar.gz）；
4. macOS：本机 jpackage/hdiutil 出 DMG（现状不变）。

优点：零新增商业依赖、完全可控、与现有 gradle 打包任务同构。
缺点：安装器体验（开始菜单、MSI 元数据、签名公证）需自己补，长期维护成本高。

### 方案 C：保留 GitHub Actions，结构优化

把跨平台公共部分（编译、jar、jlink runtime）收敛到单个 job，三平台 worker 只做
jpackage 最后一步；或 `workflow_dispatch` 一键触发全套。不解决"本地出包"诉求，
但把 CI 时间和不一致风险降到最低。

## 建议

1. **先花半天试验 Conveyor**（`brew install hydraulic-software/tap/conveyor`）：
   现有 jar + runtime 喂给 conveyor.conf，验证 Mac 上能否出三平台包并能安装运行。
   通过则发布链路换轨，同时获得自更新能力。
2. Conveyor 不合适（license 或定制冲突）→ **方案 B**：项目已有 jlink 交叉的全部
   技术要素，Windows 侧接受 exe+zip（放弃 MSI）可把复杂度砍掉一半。
3. 无论选哪条，jar 与 native 裁剪层已经是跨平台的，沉没成本为零。

## 参考

- [JDK-8213087: jpackage 不支持跨平台（官方立场）](https://bugs.openjdk.org/browse/JDK-8213087)
- [SO: 多目标安装器构建方案汇总](https://stackoverflow.com/questions/58675893/is-there-a-way-to-build-installers-for-a-java-application-for-multiple-targets-o)
- [Kotlinlang Slack: Windows 构建机是否必需](https://slack-chats.kotlinlang.org/t/538710/are-windows-build-machines-necessary-to-build-msi-installers)
- [Hydraulic Conveyor 官方文档](https://conveyor.hydraulic.dev/3.0/)
- [JetBrains: Compose Native Distributions（提及 Conveyor 交叉构建）](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)
