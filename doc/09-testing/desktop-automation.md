# Desktop 自动化与视觉验收

Desktop 开发构建在应用进程内启动一个只监听 `127.0.0.1:18080` 的测试 HTTP 服务。它导出 Compose 语义树，并通过语义动作完成点击、输入、按键和截图。测试操作因此针对真实 Desktop 客户端，而不是另写一套测试 UI。截图直接读取 Compose 的 Skia 渲染帧，不依赖系统录屏权限，也不受窗口遮挡或 macOS Space 影响。

生产打包关闭该能力，并从发布产物中移除测试服务实现。

即使只监听 loopback，开发服务仍使用 4 个固定 worker、64 个等待槽和最大 1 MiB 的请求体；饱和时
在接收线程施加背压，停止服务会同时回收执行器与窗口引用。测试工具不能依赖无界线程或无界输入来
掩盖脚本并发错误。

## 启动与探测

```bash
./gradlew :client:desktop:run
curl http://127.0.0.1:18080/ping
curl http://127.0.0.1:18080/semantics
```

### 实例令牌与僵尸实例防护（自动化验收必读）

每次进程启动会生成一个随机实例令牌（可用 `-Ptk.desktop.instanceToken=xxx` 显式指定，
经 `-Dtk.desktop.instance.token` 传入）。`/ping` 响应体与所有响应的
`X-Instance-Token` 头都携带该值，`/ping` 同时返回 `pid`。

它解决的问题：残留的 Desktop 进程同时持有数据目录 FileLock（新实例弹
"Another instance is already running"）和 18080 端口——验收工具会继续与僵尸实例对话，
把旧代码误当成新代码。注意真实主类是 `com.virjar.tk.desktop.TeamTalkMain`；
`pkill -f "com.virjar.tk.desktop"` 匹配不到任何进程，是错误的清理模式。

客户端构建完成后再启动验收实例。运行中的 JVM 直接读取工作区 JAR；如果期间为了 Android 或测试
重新构建了公共 `app` / `shared`，必须再次重启 Desktop，不能继续混用已加载的旧类与被覆盖的新 JAR。
已有按钮动作抛错时，`/click` 返回失败；不能把异常退化为 `focus-action` 并当作按钮已经执行。

自动化验收统一使用确定性的重启脚本（清理 → 启动 → 令牌确认 → 超时诊断）：

```bash
scripts/desktop-acceptance.sh        # 杀旧实例 + 启动 + 等待新实例令牌就绪
scripts/desktop-acceptance.sh kill   # 只清理不启动
```

脚本退出码：0=就绪；1=超时（附诊断输出）；3=检测到旧令牌仍在响应（僵尸实例）。
手工清理：`pkill -9 -f "com.virjar.tk.desktop.TeamTalkMain"`，再确认 `lsof -nP -iTCP:18080 -sTCP:LISTEN`
无占用。

当前固定端点如下：

| 方法 | 端点 | 作用 |
|---|---|---|
| GET | `/ping` | 检查测试服务是否启动 |
| GET | `/semantics` | 读取窗口及 Popup 的合并语义树 |
| GET | `/window-state` | 读取窗口及 AWT、Compose、Skia 各层 placement、位置和尺寸 |
| GET | `/find` | 按 `testTag` 或文本查询节点 |
| GET | `/wait` | 在有限时间内等待节点出现 |
| GET | `/debug` | 查看节点可点击与父节点动作信息 |
| GET | `/screenshot` | 获取指定窗口 Skia PNG；`mode=screen` 获取窗口所在物理屏幕 PNG |
| POST | `/click` | 单击节点或坐标 |
| POST | `/doubleclick` | 在节点或坐标派发一次原生鼠标双击 |
| POST | `/window-activate` | 请求操作系统将目标窗口置为真实前台，用于验收前台门禁 |
| POST | `/window-fullscreen` | 请求窗口进入、退出或切换全屏 |
| POST | `/longclick` | 触发长按语义动作 |
| POST | `/rightclick` | 触发右键交互 |
| POST | `/scroll` | 在节点或坐标处滚动真实滚轮（`direction=up\|down`，`amount`=格数；可滚动容器自动化必用） |
| POST | `/input` | 设置可编辑节点文本 |
| POST | `/set-progress` | 通过 Compose `SetProgress` 语义设置 Slider/进度值 |
| POST | `/keypress` | 派发 ESCAPE、ENTER 等按键 |

所有端点都可使用 `window` 查询参数；省略时操作 `main`。

`/doubleclick` 用于自绘标题栏等必须走真实指针链路的窗口手势。它在一个请求内完成两次点击，避免
两次网络请求超过操作系统的双击阈值；该端点使用 Robot，macOS 首次运行可能需要辅助功能权限。
窗口缩放验收必须同时读取 `/window-state`：`Maximized` 表示占满菜单栏和 Dock 之外的可用区域，
不能用截图尺寸猜测，也不能把 `Fullscreen` 当作通过。

macOS 原生全屏还必须验证完整尺寸链。用绿色按钮真实进入后，等待 `placement=Fullscreen`，并断言
`rootPane`、`contentPane`、`composePanel`、`skiaLayer` 和 `skiaCanvas` 都与 `screen` 的宽高一致；
随后通过 `/window-fullscreen?action=exit` 退出，确认普通窗口的位置和尺寸精确恢复。`/window-fullscreen`
用于确定性地驱动状态与退出恢复，不替代绿色按钮进入路径的真实验收。黑边属于原生窗口与绘制层
尺寸不同步，必须使用 `/screenshot?window=main&mode=screen` 检查系统屏幕；普通 `/screenshot` 读取
Skia 帧，不能单独证明原生窗口边缘。`mode=screen` 使用系统屏幕捕获，macOS 首次运行可能需要授予
“屏幕与系统录音”权限。

## 状态驱动的操作循环

可靠的 UI 验收由重复的短循环构成：

```text
读取当前语义状态
  → 判断当前窗口和目标节点
  → 执行一个操作
  → 等待目标状态
  → 重新读取并断言
  → 在关键状态截图
```

可以把稳定路径封装成可复用用例，但每一步仍要用状态断言推进。不要把十几个点击和固定休眠串成一个“盲跑”脚本。

## 定位策略

优先级如下：

1. `testTag`；
2. 唯一且稳定的语义属性；
3. 文本，仅用于探索或没有稳定标识的临时界面；
4. 坐标，只有在语义动作确实不可用时作为诊断性回退。

`/semantics` 保持输出紧凑的合并语义树；显式 `testTag` 的 `/find`、`/wait` 与动作端点在合并树未命中时
会继续查找同一窗口的未合并树。这样好友头像等位于 clickable 行内部的状态节点仍可被稳定断言，同时
不会扩大常规语义快照。脚本侧使用 `find_test_tag` / `wait_for_test_tag`，不要通过扫描合并 JSON 猜测这类
嵌套节点是否存在。

语义 `OnClick` / `SetText` 可在窗口不是系统前台时成功执行。涉及“仅真实前台聊天才发送正在
输入或已读”的验收，先调用 `/window-activate`，并要求返回 `active=true`。该端点只请求操作系统前台，
不会修改应用内的前台判断或绕过业务门禁。

坐标依赖窗口位置和缩放比例，Robot 回退还可能需要系统辅助权限，因此不能成为正式验收的主路径。
服务端会按窗口的屏幕 transform 把 Retina 语义像素转换为 AWT 逻辑点；调用方应直接使用语义树返回的
bounds，不能自行再乘除缩放倍率。完整选择器见[测试选择器参考](../10-reference/test-selectors.md)。

## 多窗口模型

| 窗口 | ID | 说明 |
|---|---|---|
| 主窗口 | `main` | 会话、通讯录、聊天、群设置抽屉、用户资料弹窗、个人设置模态 |
| 文档工作台 | `documents` | 从主窗口拉出的企业文档独立窗口；关闭或收回后状态回到主窗口 |
| 媒体画廊 | `media-gallery` | 从聊天媒体卡打开的图片/视频独立窗口；图片、视频控制和全屏操作都使用该 ID |
| 任务窗口 | `sub-<SubScreen>` | 搜索用户、建群、消息搜索、好友申请等 |

打开子窗口后，后续输入、点击、截图和 ESC 都应携带同一个 `window` 参数。群设置是主窗口右侧临时抽屉，用户资料和个人设置是主窗口模态弹窗（个人设置内的编辑资料、修改密码、设备管理、黑名单是同一模态的内部子视图），它们仍属于 `main`，不注册新的窗口 ID。

## 关键交互断言

视觉相似不能替代行为断言。以下交互应同时检查容器和退出路径：

- 打开群设置后，`chat.inspector` 出现，聊天输入区域仍存在；
- 静默认证后的大媒体库扫描期先出现 `main.sessionResources.loading`，然后在同一原生窗口收敛为
  `main.home`；两个状态的 `/window-state` 位置、尺寸和原生绘制层必须一致。构造失败时显示
  `main.sessionResources.error`，修复原因后点击 `main.sessionResources.retry` 不重建窗口即可恢复；
- 点击 `chat.inspector.dismissArea` 后抽屉消失，聊天仍保留；
- 打开用户资料后，`profile.overlay`、`profile.dismissArea` 和 `profile.dialog` 都只出现在 `main`；
  屏幕截图中只能看到主窗口的一组 macOS 红黄绿按钮，资料卡只有一个业务标题和一个 X。点击卡片本身
  不关闭，点击遮罩、X 或按 ESC 后模态消失且焦点返回原任务；
- 输入多行 Markdown 后可读回完整文本，Enter 与换行快捷键符合平台约定；
- 文件发送时出现上传或下载状态，完成后状态收敛；Chat/Document 的待处理列表必须保持有界并可滚动，
  上传中分别通过 `chat.asset.cancel.{assetId}` / `documents.asset.cancel.{assetId}` 取消，失败时分别通过
  `chat.asset.retry.{assetId}` / `documents.asset.retry.{assetId}` 就地重试，同时保留
  `chat.asset.remove.{assetId}` / `documents.asset.remove.{assetId}` 移除；
- 取消或移除富资产后，先断言对应 `pending`、`progress`、`cancel/retry/remove` 节点及 Markdown 内部 URI 消失，
  再离开并重开原会话/文档，确认引用仍未出现；即使上传稍后返回 `READY` 也不得复活节点。Desktop 真实
  验收已经覆盖聊天与文档的上传中取消和重开无引用；Android 必须另走真机验收，不能由本结论替代；
- 就地重试门禁只停止 `gradle/deployment.json` 指向的 TeamTalk 测试服务触发 FAILED，不关闭宿主机网卡、
  Wi-Fi、代理或 DNS。FAILED 行必须同时出现“重试”和“移除”；快速双击 retry 后只允许一个 attempt 进入
  PREPARING/UPLOADING。恢复目标服务后应在原 `assetId` 上进入 READY，随后发送消息或保存文档，离开并
  重进后资产仍可见。Desktop 与小米 Android 的聊天和文档都已通过该流程；Android 还必须断言上传期间的
  同 owner access token 轮换不会把成功回执改写为 FAILED，transport failure 的自动精确重放最多一次；
- 文档树中点击 `documents.node.{nodeId前8}` 打开该文档正文，点击
  `documents.tree.toggle.{nodeId前8}` 只展开或折叠子文档；父文档的正文不因它拥有子节点而不可达；
- 窗口关闭、ESC 返回和任务栈返回不会残留不可见语义节点。
- 双击 `app.titleBar.drag.left/right` 后窗口在 `Floating` 与 `Maximized` 间切换，搜索框双击不改变窗口状态。
- 打开图片或视频后，后续断言、点击与截图都携带 `window=media-gallery`；切换
  `media.gallery.previous/next` 时页码同步变化，截图中只能出现当前页媒体，非方形图片完整适配而不裁剪。
- 拉出文档工作台后，`window=documents` 的 macOS 屏幕截图只有一行融合标题栏：红黄绿按钮与应用文档
  标题同层且互不遮挡，不得再出现一条写有 `TeamTalk 文档` 的系统灰色标题栏。
- 清空目标视频缓存后打开画廊，先观察 `media.gallery.video.downloadProgress`；最终文件完成精确大小校验
  和原子发布前不得出现 `media.gallery.video.surface`。完成后只停止 `gradle/deployment.json` 指向的
  TeamTalk 测试 unit，或使用目标端点/客户端 transport 范围的故障夹具；宿主机网卡、Wi-Fi、系统网络、
  代理、DNS 和防火墙始终保持不变。服务不可达时再次打开同一视频应直接命中本地缓存，不产生新的
  `.part` / `.partial` 文件或下载请求；验收后恢复目标服务并复验健康状态。
- 视频就绪后，`media.gallery.video.controls`、进度条、播放/暂停和时间信息同时可达；点击画廊或视频控制区的
  全屏入口后以 `/window-state?window=media-gallery` 断言 `Fullscreen`，并以 `mode=screen` 与系统截图确认
  macOS 物理屏没有只渲染部分内容；只看 Java/Skia 的 placement 不能作为全屏通过证据。至少覆盖两轮
  Max↔Full、稳定后立即退出、快速反转/重复操作、系统原生退出和进入中关闭重开。用 `/set-progress` 确认
  视频可跳转并能从结尾重新播放，暂停时拖动后当前时间也必须立即更新。ESC 先退出全屏、再次 ESC 才关闭画廊；重复打开、
  关闭后原生播放器文件句柄和解码资源应回到基线。
- macOS 本地媒体发布门禁使用一段横屏和一段竖屏视频：完成真实上传、播放/暂停、seek 和全屏尺寸链后，
  交替切换至少 12 次并以当前 Desktop PID 的精确媒体路径 `lsof` 断言始终只有一个 FD；每段视频再连续
  开关 4 次，关闭后等待可观察的 FD 归零；再覆盖进入画廊后立即关闭和音频播放后关闭。
  这些场景必须通过运行中的真实 Desktop 验证，不能以单元测试代替。
  该开发构建门禁不能标记签名、公证安装包已通过。

## 截图闭环

截图用于确认层级、间距、颜色、溢出、窗口尺寸和动画关键状态。建议至少保留：

- 操作前的基准状态；
- 功能主体状态；
- 临时层打开状态；
- 错误或空状态；
- 操作完成状态。

截图前先用语义树确认目标状态，避免把加载中、旧窗口或其他应用遮挡的画面误判为结果。截图是验收证据，不是唯一断言来源。

## Android 对应方式

Android 使用 `uiautomator2` / adb 操作真实 Debug APK，继续复用共享组件的 `testTag` 作为 resource id。系统输入法、文件选择器、媒体权限和后台恢复属于 Android 独有边界，应在真机上验证；Desktop 的内置服务不能替代这些场景。
