# 故障排查

## 1. 客户端连不上或反复重连

1. 从客户端构建信息确认 serverUrl/tcpAddress 和 commit。
2. 检查 DNS、TLS 与 TCP 5100 可达性。
3. 查看 ConnectionState：CONNECTING、CONNECTED、SYNCHRONIZING、AUTHENTICATED、AUTH_FAILED 语义不同。
4. 服务端查 AUTH/CLOSE trace 和协议版本。
5. AUTH_FAILED 不应重试；普通网络断开才进入指数退避。

常见原因：客户端构建指向旧实例、TLS 证书错误、TCP 防火墙、协议版本不一致、token 被踢。

## 2. 登录后数据为空

- 确认 ClientSession 和按 uid 的本地数据库已创建。
- 检查 `sync_cursor` 中的 lastEventId 是否异常领先或未单调落盘。
- 若停在 SYNCHRONIZING，检查 SYNC_REQUEST / SYNC_BATCH / SYNC_RESET / SYNC_READY、批次投影异常和同步超时。
- 若反复收到 SYNC_RESET，检查本地事务是否成功清空 projection/cursor/inbox，以及服务端是否错误拒绝 0；
  同一连接第二次 RESET 会按协议主动断开。
- 查 EventProcessor 是否解码/写库失败且未推进游标。
- 主动 list/sync 是否能恢复 Conversation/Contact。
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
- 运行 ProtoRoundTripTest 和实际消息 payload 解码。
- 测试阶段可清空不兼容历史；不能把刚发送的新消息误判为旧数据。

## 5. 文件消息失败

- 上传响应是否得到 canonical path。
- 消息是否错误保存完整 URL 或第三方 URL。
- FileStore 元数据、主对象和缩略图是否同时存在。
- size/contentType 是否与权威元数据一致。
- access token、成员权限和服务端附件校验日志。
- 客户端下载时是否用当前 serverUrl 解析 path。

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

运行中的 `:desktop:run` 可能占用构建和测试资源。先在原终端 Ctrl-C，再执行 Gradle。避免
`pkill -f gradle`，它会杀死所有匹配进程。沙箱环境出现 FileLock 或 SocketException 时，需要给予
Gradle cache 和本机进程通信权限，而不是删除项目缓存。

## 10. 收集问题材料

至少提供：commit/build time、平台、目标实例、时间范围、uid/deviceId、复现步骤、相关
clientMsgId/chatId/eventId、客户端 fault、服务端 trace 和不含秘密的截图。没有这些关联键的“偶尔
收不到”很难定位。
