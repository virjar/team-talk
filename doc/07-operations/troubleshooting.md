# 故障排查

## 1. 客户端连不上或反复重连

1. 从客户端构建信息确认 serverUrl/tcpAddress 和 commit。
2. 检查 DNS、防火墙和 `tcpAddress` 的实际端口；5100 只是默认值。
3. 检查服务端 `TCP_HOST/TCP_PORT`，以及健康响应中的 `tcp` 项；TLS 模式的该项必须完成叶证书
   pin 的真实 handshake，而不只是 socket connect。
4. 核对只启用 TLS 1.2/1.3，证书 SAN 覆盖客户端连接的主机/IP。默认信任模式检查系统 WebPKI 证书链；
   私有固定信任模式核对客户端构建的公共 PEM 与服务器当前证书，并检查有效期。不要关闭证书校验排障。
5. 查看 ConnectionState：CONNECTING 包含 TCP/TLS 建连，CONNECTED 只在 handshake 后发布，
   SYNCHRONIZING、AUTHENTICATED、AUTH_FAILED 另有独立语义。握手前不应出现 AUTH。
6. 服务端查 AUTH/CLOSE trace 和协议版本。
7. AUTH_FAILED 不应重试；普通网络或 TLS 失败才进入既有指数退避。

常见原因：客户端构建指向旧实例、证书链/hostname/SNI 错误、TCP 端口或防火墙错误、协议版本不一致、
token 被踢。非 loopback 连接没有明文回退；严格字面量 loopback 开发/测试地址也只有在未配置公共证书时
才允许明文。检查最终配置可运行 `./gradlew writeDeploymentConfig` 并读取非敏感快照；私有 clone 应有
自己的 `buildSrc/deployment-local/Deployment.kt`，不要只看主仓库的默认公版配置。

## 2. 登录后数据为空

- 确认 ClientSession 和按 uid 的本地数据库已创建。
- 检查 `sync_state` 中的 datasetId 是否与 AUTH_RESP 一致，lastEventId 是否异常领先或未单调落盘。
- 若停在 SYNCHRONIZING，检查 SYNC_REQUEST / SYNC_BATCH / SYNC_RESET / SYNC_READY、`SyncRpc`
  checkpoint 页、批次投影异常和同步超时。
- 若反复收到 SYNC_RESET，检查 checkpointId 是否属于当前连接、所有 section 是否收齐，以及本地
  expected dataset + cursor CAS 是否成功。安装成功应把 cursor 直接设为 `baseEventId` 并拉取 tail，
  不应清空 Bot inbox 或从 0 重放；同一连接第二次 RESET 会按协议主动断开。
- 若旧 cursor 突然失效，查 `sync_streams.compacted_through`、`TEAMTALK_SYNC_EVENT_RETENTION_DAYS`
  与回收日志。只能删除已完成进程内推送尝试且过期的连续前缀，删行和 floor 必须同一事务完成。
- 查 EventProcessor 是否解码/写库失败且未推进游标。
- 权威 list 与持久事件重放是否能恢复 Conversation/Contact。
- 若经历过破坏性结构调整，先备份并核对布局、迁移与数据集身份；只有得到当前任务对明确范围的清理授权，
  才能清理相应测试资料，不能把用户资料为空当作默认重建数据库的理由。

不要只在 UI 层加“重新请求”掩盖游标或契约错误。

### 本地资料保留与只读诊断

先退出对应客户端，保留完整安装数据根；Android 导出应包含 `databases/` 中的主库及 WAL/SHM/journal，
并一同保留 `no_backup/` 中的独立文档草稿和附件源。使用
[`tt-agent doctor --cache-root`](../05-clients/headless.md) 检查 Desktop/headless 安装根，Android 导出根另传
`--cache-layout android`。此模式不读取 agent 凭据，不启动客户端，不改原库或执行恢复。

报告的 namespace、数据库族、schema 和队列计数用于区分当前可读取事实与 UNKNOWN。
坏库、缺表、未知 schema、读取限制或复制期间源变化不能记作零；`uninspected` 列出的独立文档草稿/操作
和附件 spool 未被诊断。即使所有已读计数为零，也不能据此删除资料。数据库副本的稳定检查不保证在线
原子快照，完整边界见[只读本地资料诊断](../03-architecture/client-and-sdk.md#只读本地资料诊断)。

损坏隔离后，替代库只重建服务端可回拉投影。尚有隔离副本的账号会保留可能仍被旧库引用的附件源，
并暂停孤儿源扫描删除；容量满时新导入会明确失败。不要删除隔离库或 spool 来绕过限制，现有诊断入口
不提供救援、放弃或 compaction。

需要保全隔离资料时，使用[`export-quarantine` 与 `verify-cache-archive`](../05-clients/headless.md)
将指定隔离库、同账号替代库、附件源和独立文档资料复制到新的私有目录。JVM 先退出客户端并保留现存
`.lock`；Android 先取得完整应用数据导出，再指定 `--cache-layout android`。
复制失败留下的无清单目录属于未完成输出，应单独保留并明确处置，不在该目录重试覆盖。
校验成功不等于修复完成或源可删除；归档可能包含可靠命令凭据，不能当脱敏诊断附件上传。

确定放弃隔离事实时，使用[`discard-quarantine`](../05-clients/headless.md)，提供所选隔离路径、完整归档
及其 `manifestSha256` 确认值。JVM 先退出对应安装的客户端；Android 仅修改离线导出副本，不清理手机。
该入口只删除与归档原字节一致的隔离文件，保留归档、替代库、共享附件源和独立文档资料；共享资料的
正常更新不阻断操作。中断后保留原归档并用相同命令续跑，选定范围内新增文件或字节变化时停止，不手工扩大删除范围。
`ARCHIVE_FLUSH_FAILED` 表示删除前的归档刷盘失败，本次尚未删除源文件；检查归档文件与承载目录的
访问权限、存储状态及刷盘支持后，保留同一归档重试，不能跳过此步骤删除源。
最后一个隔离副本删除后，下次启动恢复正常附件源回收，旧库独占来源可能被回收。Android 的其他隔离族
与未知后缀不属于删除范围，保留它们后保护可能继续生效，不应为解除保护而手工删除。
不再使用的账号 namespace 使用独立的[`export-namespace` / `discard-namespace`](../05-clients/headless.md#账号-namespace-保全与放弃)
入口；明确核对部署指纹、datasetId 与 uid，先保全并完整校验，再确认原始清单摘要放弃该 owner 的全部
数据库代、隔离副本、附件源和独立文档资料。它与只删隔离副本的命令范围不同，不由零计数或文件年龄自动触发。
当前凭据仍引用目标或状态无法确认时拒绝，不要删除凭据绕过；headless 的部署指纹与 uid 保护所有 dataset。
目标 namespace 在导出后新增或修改资料时须重新保全，不能继续使用旧摘要；部分删除中断则保留原归档和
命令，只对原字节一致的剩余子集续跑，不自动回滚。归档刷盘失败时不能跳过保全直接删除源。
Android 仅处理停止进程后的完整导出目录，共享文档 preferences 只归档不删除；凭据、其他账号、媒体、
telemetry 与未知 legacy 不清理。归档用途不匹配时换用对应命令，不改清单伪造用途。
需要救回单个聊天的完整草稿时，使用[草稿救援预览与导入](../05-clients/headless.md#单聊天草稿救援)。
来源可为 JVM/Android 归档，目标仅为同部署、dataset 与 uid 的现存健康 JVM 库；不恢复待发消息、
业务命令、Bot 队列或独立文档资料。预览确认失效时重新预览；目标非空或有可靠工作时先保留并核对现有
内容，不清库绕过拒绝。源链不可读、身份不符或有未确认提交依赖时保留归档，不改清单或重造发送身份。
导入后须读取当前服务器草稿并处理冲突，带本机源的附件须手动重试，不会自动发送。
保全、显式放弃与单聊天救援各有独立范围，不能据此宣称其他可靠事实已修复或重放。

归档中已提交发送的单条消息使用[单条 outgoing 救援](../05-clients/headless.md#单条-outgoing-救援)。
先核对预览的 `willResumeSending`，再确认清单摘要与两个目标时钟；活跃状态会在认证后自动续发原
身份与内容，终态失败保持停止。目标已有同身份消息、当前草稿或可靠工作时拒绝，不手删事实绕过。
原已接受消息可通过幂等重放恢复 ACK；首次发送仍可能被当前权限或过期附件拒绝，导入不保证送达。
明确失败后走现有失败恢复，未知结果不改 clientMsgId；原归档保留，不由该命令恢复 Bot 或业务队列。

需要恢复独立文档的未保存标签时，使用 [Desktop 文档草稿救援](../05-clients/desktop.md#独立文档草稿救援)。
先用 `list-document-draft-rescue` 列出标签标识，再按所选 `tab-<recoveryId>` 预览；列举不证明标签可恢复。
确认归档清单与目标状态摘要后导入，只接受 JVM 来源及同 owner 的健康当前目标库，目标文档 namespace
必须为空或已规范删除。源 manifest 有待确认创建、删除、归档，归档任一账号库有待确认移动/改名，
或数据库缺失、旧 schema、不可读时拒绝，不只核对空替代库。
不要删除记录或改写清单绕过拒绝。恢复保留本稿和旧服务器 revision，打开不自动保存；显式保存遇到
远端更新时进入冲突选择，附件仍须存在且可用。此命令不恢复文档操作、未完成上传或 Android 来源，
原库与归档保持不变。

存在一条冻结文档创建请求时，使用 [Desktop 可靠文档创建救援](../05-clients/desktop.md#可靠文档创建救援)
同时恢复它与 creating 标签；普通草稿入口的 pending 拒绝不能靠删除命令绕过。先列举、预览，再确认
清单与目标状态摘要。确认后正常客户端可在当前可见空间内重放原 ID/原请求，后继草稿仍待手动保存。
多个创建、其他结构操作或不完整配对均拒绝；空间撤权/归档后待办可能保留，首次附件失效也可能失败。
保留原归档与意图，不因未看到文档就更换身份重建，也不把无正文 ACK 当作最新正文读取结果。

冻结空间请求与其直属文档需要一起恢复时，使用 [Desktop 空间创建救援](../05-clients/desktop.md#空间创建救援)，
以 `space-command-<UUID>` 列举、预览并确认两项摘要。唯一空间创建与完整同空间依赖一起导入，
其他空间普通草稿不带入；多空间创建、嵌套 creating 依赖和其他结构操作拒绝，不拆掉依赖强行导入。
启动后只有当前空间可见或重放得到当前投影，才续发冻结子文档；归档/失权返回空投影时保留子文档待办。
已接受空间的当前名称不回滚，未提交草稿和后继修改不自动保存，不把导入成功当作所有创建完成。

健康的当前 Desktop/headless 账号库需要释放 SQLite 空闲页时，先退出该安装下的客户端，再使用
[`tt-agent compact-cache`](../05-clients/headless.md) 指定诊断报告中的一条数据库相对路径。
该入口原地 `VACUUM`，保留可靠事实；锁占用、隔离副本、非当前 schema/epoch、安装 major 不兼容和空间
不足会拒绝执行，不应删除锁或资料来绕过。Android 导出布局支持诊断、保全导出和显式放弃隔离副本，
不接受该 CLI 压缩入口。

Android 应用内从[设置 → 本地存储](../05-clients/android.md#本地存储整理)整理当前账号数据库。应用保留
登录信息，先保存草稿并暂停会话，再执行独立维护；完成后“返回应用”即可恢复，无需重新输入密码。
整理时不能进入聊天或退出登录，可以切换到系统后台，Activity 重建不重复任务。普通失败保留资料，按
提示处理存储空间、数据库占用或隔离副本等原因后重试。Android 数据库族或逻辑库超过 64 MiB 时会在
整理前拒绝；释放手机其他文件不会降低数据库自身大小，不应反复重试或删除可靠资料绕过上限。
这一上限限制原生临时库内存占用，不保证设备内存永不不足。若提示会话或维护句柄关闭失败，使用页面的
“关闭应用”后重新打开，不能仅退出页面绕过；此操作不清除登录信息或数据库。
完整性或版本检查失败不能靠清库解决，checkpoint 未完成也不表示资料应删除。该入口不删除独立草稿或
附件源，不代替救援导入。

## 3. 消息显示发送成功但对端没有

按 clientMsgId 查询：

1. 发送者是否收到 ACK，ACK code 是否成功。
2. MessageStore 是否存在 chatId/serverSeq。
3. sync_events 是否为双方写入 MESSAGE_RECV/Conversation。
4. 对端 eventId 是否处理并写 LocalCache。
5. 如果 ACK 成功但权威消息不存在，这是服务端成功语义缺陷，优先修服务端。

## 4. 新消息导致本地解码错误

- 对比 messageType 与 body registry。
- 确认 `RICH_TEXT` 的 messageType 与 body wire 是否一致。
- 检查本地旧缓存是否来自破坏性变更前。
- 运行 `:protocol:protocol:jvmTest` 中的现行协议/消息体契约测试，并核对实际消息 payload 解码。
- 保留不兼容历史并先核对协议/存储版本，提供迁移或匹配版本；不能清空数据绕过错误，也不能把刚发送的新消息误判为旧数据。

## 5. 文件消息失败

- 上传响应是否得到 canonical path。
- 消息是否错误保存完整 URL 或第三方 URL。
- FileStore 元数据、主对象和缩略图是否同时存在。
- size/contentType 是否与权威元数据一致。
- access token、成员权限和服务端附件校验日志。
- 客户端下载时是否用当前 serverUrl 解析 path。
- 远程 serverUrl 是否为 HTTPS；文件传输不跟随重定向，3xx 不能作为成功或协议切换使用。

## 6. 搜索无结果

先区分业务无结果与索引不可用：确认消息权威存储存在，再查 Lucene 文档和 plainText 派生。编辑、
撤回后结果异常通常是索引更新问题；大范围缺失可以重建索引。

## 7. 未读或已读倒退

- Conversation 是否在加群时预创建。
- lastSeq/readSeq 是否按 max 合并。
- markRead 是否更新服务端并推送自己其他设备。
- 乱序 CONVERSATION_UPDATED 是否覆盖了更新水位。
- 本地缓存合并是否把旧快照当成全量替换。

## 8. Desktop 自动化操作无效

- 确认内置服务监听 `127.0.0.1:18080`。
- 查询 `/semantics`，不要猜坐标。
- 图标优先 testTag/contentDescription。
- 独立任务窗口必须传 `window=sub-*`。
- Retina 下语义 bounds 与 AWT Robot 坐标密度可能不同；优先语义 action。
- ESC 使用窗口级按键接口，不依赖输入框焦点。

## 9. Gradle 无法启动或 Desktop 重复实例

运行中的 `:client:desktop:run` 可能占用构建和测试资源。先在原终端 Ctrl-C，再执行 Gradle。避免
`pkill -f gradle`，它会杀死所有匹配进程。沙箱环境出现 FileLock 或 SocketException 时，需要给予
Gradle cache 和本机进程通信权限，而不是删除项目缓存。

若旧 Desktop 因“Existing private directory permissions are not 0700”无法启动，新客户端会在校验当前
用户所有权、安全父链与 ACL 后，将只额外开放读取/遍历的根目录（如 `0755`）收紧为 `0700`，保留资料；
空目录仍按正常流程建立 marker，非空无 marker 的目录不会被自动接管。

若仍出现“cannot open its private data directory”，先区分：平台默认用户目录被其他用户拥有或可写、
父链包含符号链接、macOS 扩展 ACL 向其他主体授予访问/修改权限、已有 TeamTalk 根没有 marker、Windows
ACL 向 Everyone/Users 授予当前目录或继承到新子项的修改权限。启动器不改 owner、不递归改文件权限、
不清 ACL，也不会删除旧数据或探测安装目录旁的旧数据根。停止对应客户端并备份当前数据目录，再按
[Desktop 私有数据目录](../05-clients/desktop.md#11-私有数据目录)核对实际路径、marker 和权限；
不要通过把目录改成 0777 或给 Everyone 完全控制来绕过检查。

## 10. 组织永久容量耗尽

“组织节点历史记录已达到上限”或“部门群持久投影记录已达到上限”表示 20,000 个永久槽位已耗尽，
不是普通活动节点配额。归档行和负投影保护迟到任务不复活权限，不得通过手工单行删除、TTL 或宽泛 cleanup 腾出槽位。
先保留现有权威资料并评估容量迁移；只有在当前任务明确授权该实例完整数据丢失后，才可按[破坏性空数据部署](deployment.md#破坏性空数据部署)
从空数据集重建；不要手工先清一侧或两侧，否则完整安装门禁会正确拒绝继续。
正式发布前若需原地回收，必须先引入可证明不会复活旧 revision 的 generation-aware compactor。

## 11. 收集问题材料

至少提供：commit/build time、平台、目标实例、时间范围、uid/deviceId、复现步骤、相关
clientMsgId/chatId/eventId、客户端 fault、服务端 trace 和不含秘密的截图。没有这些关联键的“偶尔
收不到”很难定位。
