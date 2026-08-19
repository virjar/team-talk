# 持久化

## 1. 存储分工

| 数据 | 存储 | 原因 |
|---|---|---|
| 用户、组织、机器人授权、设备、好友、群、成员、会话、申请、邀请、同步事件、群文件、文档与修订 | PostgreSQL | 关系、约束、事务和查询 |
| 消息正文、幂等索引与投影 outbox | RocksDB | 按 chat/seq 顺序读写、单批原子 KV |
| token | 独立 RocksDB | 随机 token 快速查询和删除 |
| 文件小对象与元数据 | RocksDB | 本地嵌入、低运维成本 |
| 大文件 | 文件系统 | 避免 KV 大 blob 放大 |
| 消息全文索引 | Lucene | 分词、相关性和高亮 |
| 客户端本地数据 | SQLite/SQLDelight | 离线和 StateFlow 观察 |

## 2. PostgreSQL 关系

### users / devices

users 保存身份和资料；devices 保存用户设备、状态与最后活动。token 本体不应直接作为普通关系表
字段输出到管理查询。

### chats / group_chats / group_members

chats 保存共同身份与类型；group_chats 保存群扩展；group_members 保存成员角色和加入状态。禁言
可以使用成员字段或独立表，但权限查询必须得到单一结果。

### friends / friend_applies

friends 以有向双行表达双方视角，备注属于各自记录。friend_applies 保存申请方向、token 和状态。

### conversations

主键概念是 `(uid, chatId)`。保存 lastSeq、readSeq、peerReadSeq、draft、pin、mute 和版本。单调字段
更新使用 max/条件写，避免乱序事件倒退。

### sync_events

按 uid 和自增 event ID 保存 NotifyType 与 payload bytes。认证按 `id > lastEventId` 升序分页。事件
保留期和清理必须大于客户端合理离线窗口，并与 seq 缺口恢复配合。

### organization_units / organization_memberships

organization_units 保存单根层级、负责人和可选稳定部门群 ID；organization_memberships 保存直接部门
归属、职位与主部门标记。同一用户的唯一主部门由 Repository 写入事务收敛。群成员表只是组织事实的
投影，不能反向编辑组织关系。

### automation_bots / automation_bot_grants

automation_bots 关联服务 User，只保存 webhook token 哈希、状态和最后调用时间；明文 token 不落库。
automation_bot_grants 以 `(botId, chatId)` 唯一，作为可发送群的权限事实。group_members 中的机器人行
是可修复投影，服务启动按 grant 重放。

### group_file_entries / group_file_versions / group_file_audits

group_file_entries 保存群文件目录树、逻辑名称、当前 Attachment、revision 和当前内容版本。根目录用
稳定 parentKey 参与同级名称唯一约束；软删除时释放名称键，允许重新创建同名条目。

group_file_versions 只追加不可变 Attachment 快照，`(entryId, version)` 唯一。group_file_audits 与每次
创建、追加版本、重命名、删除在同一 PostgreSQL 事务提交，只记录动作与有限摘要，不保存文件正文。
物理二进制仍在 FileStore；数据库版本表是下载引用和群空间配额的事实源。

### document_spaces / document_space_grants

document_spaces 保存空间元数据、创建者所有权与归档状态。document_space_grants 以
`(spaceId, principalType, principalId)` 唯一，保存用户或组织部门的角色以及是否包含下级部门；它不
复制部门成员，实时有效角色由领域服务计算。

### document_nodes / document_content_revisions

document_nodes 同时保存目录树、文档当前 Markdown 快照、revision 和创建/修改身份；文件夹正文为 null。
parentId 必须指向同空间文件夹，循环约束由领域服务在写入前检查。删除只改变 status 并推进 revision。

document_content_revisions 只追加每次成功保存的标题与完整 Markdown 快照，
`(documentId, revision)` 唯一。更新在锁定 document_nodes 当前行的同一事务内完成 revision 条件写和
修订插入，避免两个并发保存都成功。完整快照简化恢复与验收，但会增加存储；增量压缩、保留期和管理
员审计属于生产化后续设计。

## 3. MessageStore

消息主键按 chatId 前缀和 big-endian serverSeq 编码，使 RocksDB 范围扫描天然按序：

```text
[chatId bytes][serverSeq 8B BE] → Message bytes
```

另有 clientMsgId 幂等索引指向 chatId/serverSeq。分配 seq、写消息和写幂等索引需要保持可恢复顺序；
重复请求必须返回已存在消息。

MessageStore 在同一 RocksDB `WriteBatch` 中写入消息、clientMsgId 索引和待投影记录：

```text
[0x02][chatId bytes][serverSeq 8B BE] → Message bytes
```

Lucene、Conversation 和 `sync_events` 都完成后删除该记录。幂等重试和服务启动会扫描并补偿未完成项；
重放可以产生重复 Notify，客户端按 at-least-once 契约 upsert。

## 4. 派生数据

Lucene 索引、会话预览、缩略图和部分计数都是派生数据：

- 派生写失败需要 fault 日志和重建/补偿方式。
- 搜索结果不能反向成为消息权威。
- 重建工具读取 MessageStore，不从客户端缓存回灌。
- 健康检查应区分“索引不可用”和“消息已丢失”。

## 5. 一致性与事务

PostgreSQL 事务只覆盖关系表；它不能原子覆盖 RocksDB/Lucene。跨存储流程必须用业务顺序保证：

1. 权威消息与 outbox 原子写成功。
2. 幂等更新可重建索引和会话投影。
3. 持久化同步事件并推送在线设备。
4. 清除 outbox 并对外返回成功。

如果第 2 或第 3 步失败，outbox 保留并由重试/重启补偿。不能在权威写入前推送事件，也不能在
补偿未完成时把重复 `clientMsgId` 直接解释为完整成功。

## 6. 生命周期

当前正式发布前允许清空测试数据处理不兼容结构。生产化前必须补齐：

- 明确的数据库迁移版本。
- RocksDB key/version 迁移策略。
- sync_events 与孤儿文件清理策略。
- 备份、恢复和一致性校验工具。

这些未完成项集中维护在[功能状态](../10-reference/feature-status.md)和
[路线图](../10-reference/roadmap.md)，不混入当前 schema 描述。
