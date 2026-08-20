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

认证成功后，客户端等待 LocalCache 与 EventProcessor 就绪，再用本地 `sync_cursor` 中的
`lastEventId` 发起显式分页同步。服务端按 ID 升序返回有界批次；客户端只有在整条事件投影成功并
单调保存游标后才请求下一批。最终的二次查空、`SYNC_READY` 与实时连接注册受同一用户事件门闩
保护。语义是 at-least-once：可能重复，不能丢失；完整快照通过 upsert 或稳定键删除收敛。

`lastEventId` 是“该账号已经持久投影完成的事件凭证”，不是客户端可任意填写的全局序号。除初始值
`0` 外，服务端只接受仍存在且归属当前账号的事件 ID；伪造、损坏或串账号的高游标触发显式
`SYNC_RESET`，不能通过一次空查询直接进入实时态并永久跳过后续事件。客户端在同一连接内原子
清空服务器投影、草稿 outbox、无头 inbox 与 sync cursor，同步清空 StateFlow/消息窗口，再以 0
重新请求。独立的文档草稿 store 不在清理范围内。清理失败、同步页与 RESET 重叠或重复 RESET
一律断开，重连后从最后一个完整本地事务状态重试。

当前 RESET 通过从 0 重放完整历史自愈错误游标，但尚没有独立权威快照/checkpoint bootstrap，
所以服务端仍不按 TTL 过滤，也不物理删除 `sync_events`。这是开发期的正确性取舍：事件表会
无界增长，但重置不会从残缺历史重建出貌似成功的投影。正式上线前必须先补全量状态基线，再配置
保留期与清理。

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
  → message 与 attachment→chat 反向索引原子写入
  → message 只保存 relative path
  → receiver 用 access token + serverUrl + path 下载
```

上传成功但尚未发送消息的文件可能暂时成为孤儿；清理策略属于运维生命周期，不应通过允许任意 URL
来规避。上传者在消息发送前可以读取自己的对象；发送后，当前会话成员经反向索引获得读取权限。
退出或被移除后，服务端按实时成员资格拒绝新的下载。附件完整契约见
[消息与附件](../04-protocol/messages-and-attachments.md)。

## 7. 群文件生命周期

```text
HTTP upload → FileStore Attachment（仅上传者可读）
  → groupFile.createFile / addVersion
  → PostgreSQL 原子写条目、不可变版本、审计
  → AttachmentReferences 合并群文件与消息引用
  → 当前群成员通过同一文件端点下载
```

当前客户端不把群文件写入 LocalCache，也没有持久化 Notify；页面打开、目录切换和修改后主动拉取。
这意味着另一设备修改后，已打开的列表要手动刷新才能看到。它是明确的部分能力，不应被描述成实时
同步；后续需要稳定事件、离线投影与搜索索引后才能进入本地优先模型。

## 8. 文档生命周期

```text
client listSpaces
  → server 合并 owner、用户 grant、实时部门 membership/grant
client listNodes(spaceId, parentId)（仅目录摘要，不传全部 Markdown）
client getDocument(spaceId, documentId)（当前完整快照）
client updateDocument(title, markdown, expectedRevision)
  → server 重算空间有效角色
  → PostgreSQL 锁定文档当前行并比较 revision
  → 原子更新当前快照 + 追加不可变 DocumentRevision
  → 返回新 revision
```

修订列表只传标题、版本、字符数和编辑元数据；用户选择具体版本后才按需读取完整 Markdown。恢复历史
版本沿用正常 update 流程，因此仍受最新 revision 冲突保护。

空间授权不复制部门成员；每次访问都使用当前 OrganizationMember 关系。当前文档投影不进入 LocalCache，
也不发布持久化 Notify。页面在打开和本地修改后重新拉取，其他设备的修改需要手动刷新才能发现；若
两个成员从同一 revision 保存，只有先到达者成功，失败者本地编辑内容不应被清空。后续增加实时事件
或离线编辑时，必须先定义缓存投影、权限撤销、缺口恢复和合并语义。

## 9. 故障语义

| 故障 | 正确行为 |
|---|---|
| ACK 超时 | 本地消息标记失败/可重试；用同一 clientMsgId 重发 |
| NOTIFY 重复 | upsert 幂等，游标继续推进 |
| NOTIFY 解码或写库失败 | 记录 fault，不推进游标并关闭连接；重连后显式同步重试 |
| TCP 断开 | 保留用户层与本地缓存，指数退避重连 |
| AUTH_FAILED | 停止重连，清 token，销毁 ClientSession |
| 历史存在 seq 缺口 | 按 serverSeq 主动拉取历史修复 |
| 搜索索引缺失 | 从消息权威存储重建 Lucene，不修改消息 |
| 文档 revision 冲突 | 拒绝覆盖并保留本地草稿；刷新或复制内容后由用户决定 |

## 10. 一致性边界

TeamTalk 不提供跨 PostgreSQL、RocksDB 与 Lucene 的分布式事务。写入顺序和恢复手段必须保证：

- PostgreSQL/RocksDB 权威数据先于通知成功。
- Lucene 是可重建派生索引。
- Conversation 与事件允许通过幂等更新修复。
- MessageStore 以持久化 outbox 记录未完成的跨存储投影，启动和幂等重试都会恢复。
- 客户端不因短暂派生数据缺失伪造权威成功。
