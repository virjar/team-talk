# iOS

iOS 使用 `client/ios` 平台壳，复用 `app` 的业务页面、ViewModel 和编辑器，以及 `shared` 的
ClientSession、SQLite、同步与可靠发送。Swift 仅提供启动入口、系统生命周期和 APNs 回调。
最低系统为 iOS 16，包含 iPhone/iPad；当前是待验收源码，不属于已发行 0.0.4 的制品范围。

## 源码与平台边界

| 入口 | 职责 |
|---|---|
| `client/ios/AppleApp/TeamTalkApp.swift` | SwiftUI 入口、前后台、通知权限、APNs token 与点击 |
| `client/ios/src/iosMain/.../IosApp.kt` | 启动、认证、版本门禁、账号清理和会话发布 |
| `IosSessionUi`、`IosNavigation` | 会话资源与手机页面栈，通知和搜索携带精确聊天身份 |
| `IosHomeScreen`、`IosChatScreen`、`IosFeatureScreens` | 五个一级栏目、聊天、账号及群管理页面 |
| `IosWorkspaceHosts`、`IosDocumentDraftPersistence` | 文档/任务工作台、文件材料、文档草稿持久化 |
| `IosNativeMedia`、`IosVoiceRecorder`、`IosMediaResources` | 系统选择器、权限、录制、文件分享、本地播放与资源退役 |
| `client/shared/src/iosMain` | Network.framework TCP/TLS、URLSession HTTP、Keychain、Native SQLite、私有文件 |
| `client/app/src/iosMain` | 剪贴板、主题、字符分类、拼音及返回处理 |
| `client/richeditor/src/iosMain` | 受控上游编辑器的 iOS 实现，归属见 `FORK.md` |

网络仍使用同一个二进制协议。`PacketFrames` 在公共层校验和编解码帧，JVM/Android 的 Netty 与
iOS 的 Network.framework 只负责各自的传输。iOS 不另建 HTTP 业务 API，不另存一套消息状态。
账号数据按 deployment/dataset/uid 隔离；认证凭据和设备 ID 使用本安装 bundle 身份下的 Keychain。
数据库先判断版本和物理完整性，未知/更高版本保留原文件并报错；确认损坏才走可恢复的替换流程。
连接生命周期和附件 spool 策略位于 shared common；媒体预算与导入协调位于 app common。
iOS 只适配 Network.framework、文件/摘要、UIKit 选择和媒体能力。文档草稿的编码与持久提交在后台单写者执行，
后台切换及账号清理通过明确的持久化屏障衔接。
详细所有权见[客户端与 SDK](../03-architecture/client-and-sdk.md)。

媒体继续完整认证下载到私有缓存后播放；图片/音视频只使用本地路径。系统选择结果复制到账号暂存区，
聊天内嵌资产进入既有持久 spool 后可随草稿恢复。图片展示按视口采样，解码最长边不超过 2048 像素，
分享仍使用原始文件。系统预览和分享保留附件名称，在账号暂存区创建独立副本，关闭或取消后清理。
关闭会话先停止录放、取消导入和下载并释放借用者，再退役 SDK 与账号文件。
用户通过系统分享实际导出到外部的副本由用户控制，不属于本地账号清理的范围。

会话菜单支持本机「标为未读」；聊天头部菜单进入「会话设置」，复用共享的发起群聊与本机清空聊天记录。
清空水位、未读标记与消息投影由同一 SDK 管理，旧历史消息不会在补拉后重新出现；这些入口仍需设备交互验收。

## 开发与构建

Xcode 配置生成由 buildSrc 的 `GenerateIosXcodeConfiguration` 任务负责，仍通过
`:client:ios:generateXcodeConfiguration` 生成既有 xcconfig、Info.plist 和图标路径。
架构检查扫描所有模块的 main 源集，关闭 iOS 构建也不会跳过 iOS 源码边界。

`enableIos` 默认是 `false`：Gradle 不包含 `:client:ios`，协议、SDK、测试夹具、共享 UI 和编辑器也不注册
iOS Native target。Android/Desktop、SDK 和服务端的常用构建与测试照常使用，不要求 Apple 工具链。
准备开发 iOS 的 Mac 在仓库根目录的 `local.properties` 中加入以下配置，保留原有其他配置，再重新同步 Gradle：

```properties
enableIos=true
```

此文件已被 Git 忽略。删除该项或设为 `false` 可关闭；命令行 `-PenableIos=true` / `-PenableIos=false`
优先于本地文件，可临时覆盖。以下 iOS 命令均假定已启用。本地使用 Xcode 时也应保留这个开关，因为
Xcode 的构建阶段会另行调用 Gradle。CI 仅在 macOS 的 `ios` job 设置
`ORG_GRADLE_PROJECT_enableIos=true`，同时传给直接调用和 Xcode 内嵌的 Gradle；该 job 还通过
`GRADLE_OPTS=-Dorg.gradle.workers.max=2` 统一限制两处调用的 worker 并发。

需要 JDK 21、完整 Xcode 及对应 iOS 平台组件，以及仓库指定的 Gradle Wrapper。
Kotlin/Native 编译器首次执行时自动下载。Kotlin 2.3.21 的
[官方兼容表](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)仍列 Xcode 26.0；
使用 Xcode 26.3 时须验证实际构建，当前已验证范围见本页末尾。
Compose 1.11 不再提供 iOS x86_64 UI 依赖；模拟器应用使用 Apple Silicon。
SDK/协议单独保留 `iosX64`，方便具备相应 Xcode 的 Intel 主机运行无 UI 测试；这不等于支持 Intel 模拟器 UI。

建议在内存和磁盘余量充足的 Mac 上启用。Native 编译、链接、缓存生成以及模拟器可能使用多个进程，
总内存不能只看 Gradle 的 `-Xmx`。首次验证可给 Gradle 命令加 `--max-workers=2` 限制并行，
并分开执行测试与整包构建，再按实际资源调整；这不是固定的硬件容量门槛。

首次打开 Xcode 后完成初始化，并在 Settings → Components 中完成对应 iOS 平台的安装。
`xcodebuild -checkFirstLaunchStatus` 成功、iPhone SDK 目录存在，并不代表平台组件已全部就绪。
若构建目标提示 `iOS 26.2 is not installed`，先在上述页面完成 iOS 26.2 平台安装，再重试原命令；
Intel 主机选择支持 Intel 的 Universal 组件。此时构建在目标选择阶段停止，尚未进入 Kotlin/Swift 编译，见
[Apple 组件说明](https://developer.apple.com/documentation/xcode/downloading-and-installing-additional-xcode-components)。

在仓库根目录执行：

```bash
./gradlew :client:ios:generateXcodeConfiguration
open client/ios/TeamTalk.xcodeproj
```

工程的构建阶段调用 `embedAndSignAppleFrameworkForXcode`，无需 CocoaPods。
在 Apple Silicon 主机选择 `TeamTalk` scheme 与 iPhone/iPad 模拟器后运行，命令行构建入口：

```bash
xcodebuild -project client/ios/TeamTalk.xcodeproj -scheme TeamTalk \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath client/ios/build/DerivedData \
  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build
```

Intel 主机可以交叉编译 iPhone arm64 框架和应用。生成配置、链接框架，再构建无签名真机整包：

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --max-workers=2 :client:ios:generateXcodeConfiguration :client:ios:linkDebugFrameworkIosArm64
xcodebuild -project client/ios/TeamTalk.xcodeproj -scheme TeamTalk \
  -configuration Debug -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath client/ios/build/DerivedDataDevice \
  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build
```

Xcode 路径按实际安装位置调整。此命令验证编译、链接和打包；安装到设备运行仍需要签名。

`generateXcodeConfiguration` 从同一 `DeploymentConfig.client` 生成 bundle ID、名称和版本；同时从
仓库品牌 SVG 生成不透明 AppIcon。生成内容位于 `client/ios/build/generated`，不手工维护。
切换部署配置后先重新执行此任务，再打开工程，以便 Xcode 在读取 build settings 时取得新身份。
Swift 与 Kotlin 使用同一安装身份，私有版的 `iosBundleId` 默认是其 `applicationId + ".ios"`。
公版与私有版使用独立 bundle ID，凭据、数据库、主题和推送 topic 因而分开。

真机签名：将 `client/ios/Config/Signing.example.xcconfig` 复制为同目录的 `Signing.local.xcconfig`，
填写所属 Apple Developer Team。Xcode 的 Signing & Capabilities 中选择该 Team，为实际 bundle ID
配置 Push Notifications。本地签名配置被 Git 忽略，私钥和 provisioning profile 不进入仓库。
Debug 默认 `aps-environment=development`，Release 默认 `production`；实际签名配置须与之匹配。
归档通过 Xcode Product → Archive，后续按组织选定的 TestFlight/App Store 或其他获准渠道分发。
对归档或导出后最终签名的 `.app` 执行 `/bin/sh client/ios/scripts/verify-signing.sh /path/to/TeamTalk.app`，
校验实际签名里的 `aps-environment` 与应用注册环境一致；重新签名或导出 IPA 后应解包复验。
不一致时使用正确的 `APNS_ENVIRONMENT` 重新构建，不能只修改已签名的 plist。
现有发布注册中心的 iOS 平台枚举用于描述制品，不负责绕过 Apple 签名安装或应用内下载 IPA。

HTTP 使用 URLSession 的系统证书验证与 ATS；公开部署使用 HTTPS。私有 TCP 证书使用部署配置的
显式证书及目标主机验证，不接受无条件信任。配置明确选用 HTTP 时，生成器只对该服务的精确主机
生成 ATS 例外，不包含子域或全局放行；iOS 17 起直连 IP 也使用该精确例外。
局域网连接由系统授权提示管理。

## 前后台与 APNs

前台 TCP 保持既有协商、认证、心跳和权威同步。进入后台捕获聊天/文档输入，停止前台交互与播放；
操作系统可挂起进程，客户端不声明常驻后台网络。回到前台沿现有连接恢复和同步补齐，
不把“进入后台”当作用户退出，也不清除待发送事实。

APNs 使用系统可见通知。服务端复用持久通知任务，按账号设置、群静音、已读及消息撤回处理，
不会因 TCP 暂时仍在线就认定后台 iPhone 无需通知。payload 使用通用通知内容，携带精确
deployment/dataset/uid/chatId，客户端验证范围后打开相应会话；消息正文仍从权威同步获取。
没有 silent push 保活或依赖通知必达的同步分支。

APNs 注册需要待发布协议 0.4 的 `device/4`；旧 `device/3` 及已发行协议保持原布局。
登录已发布的旧服务端时，设备标识按协商结果使用既有 UNKNOWN；新服务端使用 IOS。
HTTP 遥测沿用既有 UNKNOWN 枚举并以 `osName=iOS`、`distribution=ios` 标明平台，
避免旧服务端拒绝持久批次；不会在上传重试时修改批内容或批身份。
服务端按 bundle ID、sandbox/production 和安装设备保存注册，410 无效 token 只移除对应注册世代，
不误删随后更新的 token。客户端认证后登记当前 token，轮换或重新登录后再次登记。
部署配置及 `.p8` 的管理见[配置](../07-operations/configuration.md)。未开通 APNs 的部署仍可前台使用，
后台系统通知则需要真实的 Team、Key ID、bundle ID 和签名设备。

`PrivacyInfo.xcprivacy` 声明本实现使用的应用私有文件时间、磁盘空间、单调时钟与 UserDefaults 的原因。
应用商店的数据使用说明须依据实际私有部署和最终依赖填写，不能从该 API manifest 推导“没有收集数据”。

## 验证与交付边界

只需要检查 Kotlin 源码时，可以先执行：

```bash
./gradlew --max-workers=2 :client:ios:compileKotlinIosArm64 \
  :protocol:protocol:compileTestKotlinIosArm64 \
  :client:shared:compileTestKotlinIosArm64 :client:app:compileTestKotlinIosArm64 \
  :client:ios:compileTestKotlinIosArm64
```

在完整 Apple Silicon/Xcode 环境运行 Native 测试：

```bash
./gradlew --max-workers=2 :protocol:protocol:iosSimulatorArm64Test \
  :client:shared:iosSimulatorArm64Test :client:app:iosSimulatorArm64Test \
  :client:ios:iosSimulatorArm64Test
```

Intel 主机可在支持 x86_64 的已安装 iOS runtime 上执行协议和 SDK 测试：

```bash
./gradlew --max-workers=2 :protocol:protocol:iosX64Test :client:shared:iosX64Test
```

需要指定模拟器时，用 `xcrun simctl list devices available` 查找 UDID，再为各测试任务追加
`--device <UDID>`；共享 UI 没有 `iosX64Test` 目标。

CI 增加同样的 Native 测试及无分发签名的模拟器应用构建；不会自动上传 TestFlight，也不会使用私有服务。
Native 测试覆盖公共 wire、真实 Native SQLite 损坏恢复/版本保留及升级迁移、清空会话后旧消息不复活、
spool 分块与租约退役、遥测目录分页回收、HTTP 工作准入与关闭。
编译或这些测试成功仍不能代替以下完整客户端验收：

1. 首次登录、离线重开、退出再登录、跨账号通知拒绝、公私版并存和封禁后的清理恢复。
2. iPhone/iPad 中文键盘、富文本输入、裸网址与富文本粘贴、切换页面、文档冲突处理、任务和群管理；
   核对标为未读、发起群聊和清空聊天记录，重开后清空水位与未读标记保持一致。
3. 断开被测应用与测试服务的连接，编辑草稿/排队发送，再恢复网络和进程，核对发送身份与资料保留。
4. 照片/文件选择、相机/麦克风拒绝后重试、录音中断、大图展示、附件完整下载后播放/分享，
   中文长文件名保留、取消分享，以及分享中切换账号后的资源释放。
5. 签名真机锁屏及后台收到 APNs、点击定位、token 轮换、静音/已读/撤回不误通知，恢复前台后补齐消息。
6. 覆盖安装保留 Keychain、SQLite、草稿及未确认意图；使用同批签名制品记录系统版本、设备与未覆盖项。

测试不关闭宿主网络，不修改全局代理/DNS/路由；故障隔离仅作用于测试应用或明确的测试端点。
当前已通过 iOS arm64 全部产品源码及协议/SDK/共享 UI 测试源码编译，包含真实 loopback TCP 的
分片、合帧与关闭后晚帧测试。协议、SDK、共享 UI、Android、Desktop、Headless、构建工具测试，
以及 APNs/OEM、认证、数据库迁移和发布注册中心的服务端定向回归均通过；管理台生产构建、
架构边界、协议基线和相对开发基线的发行历史检查也通过。Windows 专属启动用例在 macOS 上跳过。

Intel 主机使用 Xcode 26.3 / iPhoneOS 26.2 SDK，已通过 iPhone arm64 Debug framework 链接、
Swift 壳的 typecheck 和无签名 iPhone 应用整包构建；在 iOS 18.1 x86_64 runtime 上，
协议 221 项和 SDK 153 项 Native 测试全部通过。
本轮新增的 iOS 草稿文件持久化测试已通过 ARM64 测试源码编译，并纳入 Apple Silicon CI 的
`:client:ios:iosSimulatorArm64Test`；它们未在这台 Intel 主机执行。
默认关闭、本地启用、命令行覆盖和 CI 环境变量启用均已验证；关闭时 Android/Desktop 编译、
JVM 回归、架构和协议基线检查通过。共享 UI 的模拟器运行、签名与 APNs 真机验收尚未完成；
当前不把 iOS 标为已发布或已通过设备验收。
