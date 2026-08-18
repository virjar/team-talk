# 数据与同步

## 1. 消息发送生命周期

```text
Composer
  1. 生成 clientMsgId 与 MessageBody
  2. SDK 校验结构、附件路径和大小边界
  3. LocalCache 插入 SENDING
  4. ImClient 通过 MESSAGE 发送
Server
  5. 校验认证、成员、消息类型与附件存在性
  6. 按 clientMsgId 幂等查询
  7. 为 chat 分配 serverSeq
  8. RocksDB 原子写消息、幂等索引和投影 outbox
  9. 幂等更新 Lucene 与 Conversation
 10. 持久化 MESSAGE_RECV / CONVERSATION_UPDATED 事件并推送
 11. 清除 outbox，返回 MESSAGE_ACK
Client
 12. ACK 更新本地发送状态
 13. 回环 NOTIFY upsert 权威 Message 和 Conversation
```

第 5 步失败时不能返回成功 ACK。ImBot、Desktop 或 Android 只要收到成功，就应能把消息视为服务端
已接受；后续推送延迟属于同步问题，不是重新解释发送结果。

## 2. 幂等与顺序

`clientMsgId` 防止超时重试产生重复消息。服务端保存它到 `(chatId, serverSeq)` 的索引；重复发送
不再分配序列。如原请求留有未完成 outbox，重试会先补齐投影再返回原结果。

`serverSeq` 在单个 Chat 内单调递增，是历史分页、消息排序、缺口恢复和已读水位的共同坐标。跨 Chat
不提供全局消息顺序。

## 3. 事件同步

大多数领域变更使用同一模型：

```text
domain commit
  → NotifyContracts.assertContract
  → INSERT sync_events(uid, type, payload)
  → push to all online devices
```

认证请求携带 `lastEventId`。服务端按 ID 升序补发事件；客户端只有在成功处理后才保存新游标。
语义是 at-least-once：可能重复，不能丢失。事件 payload 使用完整快照，客户端用 upsert 或按稳定键
删除。

Presence 不持久化，因为离线期间的在线状态没有补发价值。当前实现只接收连接变化产生的实时事件，
登录后的好友在线快照尚未补齐；在快照能力完成前，重连不能被视为已经恢复了完整在线状态。

## 4. 会话与已读

每个成员在每个 Chat 中有自己的 Conversation。收到消息时服务端更新 `lastSeq`；用户阅读时提交
新的 `readSeq`：

```text
newReadSeq = max(storedReadSeq, requestedReadSeq)
unreadCount = max(0, lastSeq - newReadSeq)
```

服务端向同一用户设备推送 Conversation 更新，并向其他成员推送可展示的 peer read waterline。
水位单调合并，因此乱序和重复事件不会让“已读”倒退。

## 5. 群成员变化

建群、加人或通过邀请加入时，服务端必须在推送群事件前为新成员建立 Conversation。移除成员时，
成员不能继续发送或读取受保护历史；被移除者仍需收到足够事件清理本地群状态。

角色和禁言变化发送完整 Chat/Member 快照。权限判断只读服务端当前状态，不信任客户端缓存角色。

## 6. 附件生命周期

```text
HTTP upload
  → FileStore 写入并返回 canonical relative path
  → client 构造 Attachment
  → MESSAGE send
  → server 按 path 查询元数据并校验
  → message 只保存 relative path
  → receiver 用 serverUrl + path 下载
```

上传成功但尚未发送消息的文件可能暂时成为孤儿；清理策略属于运维生命周期，不应通过允许任意 URL
来规避。附件完整契约见[消息与附件](../04-protocol/messages-and-attachments.md)。

## 7. 故障语义

| 故障 | 正确行为 |
|---|---|
| ACK 超时 | 本地消息标记失败/可重试；用同一 clientMsgId 重发 |
| NOTIFY 重复 | upsert 幂等，游标继续推进 |
| NOTIFY 解码或写库失败 | 记录 fault，不推进游标，下次认证补发 |
| TCP 断开 | 保留用户层与本地缓存，指数退避重连 |
| AUTH_FAILED | 停止重连，清 token，销毁 ClientSession |
| 历史存在 seq 缺口 | 按 serverSeq 主动拉取历史修复 |
| 搜索索引缺失 | 从消息权威存储重建 Lucene，不修改消息 |

## 8. 一致性边界

TeamTalk 不提供跨 PostgreSQL、RocksDB 与 Lucene 的分布式事务。写入顺序和恢复手段必须保证：

- PostgreSQL/RocksDB 权威数据先于通知成功。
- Lucene 是可重建派生索引。
- Conversation 与事件允许通过幂等更新修复。
- MessageStore 以持久化 outbox 记录未完成的跨存储投影，启动和幂等重试都会恢复。
- 客户端不因短暂派生数据缺失伪造权威成功。
