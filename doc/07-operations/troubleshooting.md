# 故障排查

## 1. 客户端连不上或反复重连

1. 从客户端构建信息确认 serverUrl/tcpAddress 和 commit。
2. 检查 DNS、防火墙和 `tcpAddress` 的实际端口；5100 只是默认值。
3. 检查服务端 `TCP_HOST/TCP_PORT`，以及健康响应中的 `tcp` 项；TLS 模式的该项必须完成叶证书
   pin 的真实 handshake，而不只是 socket connect。
4. 核对只启用 TLS 1.2/1.3、证书链受系统 WebPKI 信任，并覆盖客户端用于 hostname 校验和 SNI 的主机。
5. 查看 ConnectionState：CONNECTING 包含 TCP/TLS 建连，CONNECTED 只在 handshake 后发布，
   SYNCHRONIZING、AUTHENTICATED、AUTH_FAILED 另有独立语义。握手前不应出现 AUTH。
6. 服务端查 AUTH/CLOSE trace 和协议版本。
7. AUTH_FAILED 不应重试；普通网络或 TLS 失败才进入既有指数退避。

常见原因：客户端构建指向旧实例、证书链/hostname/SNI 错误、TCP 端口或防火墙错误、协议版本不一致、
token 被踢。非 loopback 连接没有明文回退；只有严格字面量 loopback 开发/测试地址允许明文。

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
- 测试环境若经历过破坏性结构调整，清理对应测试数据库后重验。

不要只在 UI 层加“重新请求”掩盖游标或契约错误。

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

若启动前出现“cannot open its private data directory”，先区分：平台默认用户目录被其他用户拥有或可写、
父链包含符号链接、macOS 扩展 ACL 向其他主体授予访问/修改权限、已有 TeamTalk 根没有 marker、Windows
ACL 向 Everyone/Users 授予当前目录或继承到新子项的修改权限，或旧
安装目录与新根形成未知双根冲突。启动器不会自动改 owner/权限，也不会删除旧数据。停止所有新旧客户端，
备份两处目录，再按[Desktop 私有数据目录](../05-clients/desktop.md#11-私有数据目录)核对默认路径、receipt
和旧树门禁；不要通过把目录改成 0777 或给 Everyone 完全控制来绕过检查。

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
