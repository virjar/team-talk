# 数据库 Schema — PostgreSQL（Exposed）

> 全部 11 张表。源码 `server/.../infra/db/Tables.kt`。
> 迁移方式：`SchemaUtils.createMissingTablesAndColumns`（启动时自动建缺失表/加缺失列，SERIALIZABLE 事务）——加列安全，改类型/删列不支持。

---

## users — 用户主表（LongIdTable）

| 列 | 类型 | 约束/默认 |
|----|------|----------|
| uid | varchar(36) | **unique**（8 位 base62 短码） |
| username | varchar(50) | **unique** |
| name | varchar(100) | |
| phone | varchar(20) | nullable, unique |
| zone | varchar(10) | `+86` |
| password_hash | varchar(100) | BCrypt |
| avatar | varchar(500) | nullable |
| sex | integer | 0 |
| short_no | varchar(20) | nullable, unique（预留） |
| status | integer | 1 |
| role | integer | 0 |
| created_at / updated_at | long | |

## devices — 登录设备

uid(index) · device_id · device_name? · device_model? · device_flag(0) · last_login(0) · created_at
Unique: `(uid, device_id)`

## chats — 会话/群主表

chat_id(36) **unique** · chat_type(1=私聊 2=群) · max_seq(0，原子水位线) · status(1) · created_at/updated_at

## group_chats — 群扩展（Table，PK=chat_id FK→chats）

name(200,"") · avatar(500)? · creator(36) · notice(500,"") · muted_all(false) · updated_at

## group_members — 群成员

chat_id(index) · chat_type(2) · uid(index) · role(0=成员/1=管理员/2=群主) · nickname(100)? · status(1) · joined_at
Unique: `(chat_id, uid)`

## group_member_mutes — 禁言记录

chat_id(index) · uid · operator_uid · expires_at · created_at（无唯一约束，生效判定取最新未过期记录）

## conversations — 每用户每会话的收件箱状态（**多设备同步核心**）

| 列 | 类型 | 语义 |
|----|------|------|
| uid | varchar(36) | index；**每用户一行**（同一 chat 每成员各一行） |
| chat_id | varchar(36) | |
| chat_type | integer | |
| last_msg_seq | long(0) | 高水位线（服务端权威） |
| last_message | varchar(500)? | 预览文本 |
| last_message_type | integer(0) | |
| read_seq | long(0) | **该用户已读水位线（只增不减）** |
| peer_read_seq | long(0) | **对方已读水位线**（markRead 时对其他成员写，取 max） |
| is_muted / is_pinned | bool(false) | |
| draft | varchar(500)? | 纯客户端状态的服务端镜像 |
| version | long(0) | 乐观锁 + SYNC 增量游标 |
| updated_at | long | |

Unique: `(uid, chat_id)`

**不变量**：
- `unreadCount = max(0, last_msg_seq − read_seq)` 下发时计算，不落库
- 行必须在建群/加人时 `ensureConversation` 预创建（历史断裂：曾只在首条消息时创建，导致空聊天 markRead no-op → readSeq 丢失）
- markRead/peerReadSeq 一律取 max（可合并水位线，见 [设计理念 §8](../00-overview/design-philosophy.md#8-已读未读--可合并的单调水位线)）

## friends — 好友关系（有向存储，成对插入）

uid(index) · friend_uid · remark(100)? · status(1=正常/2=拉黑) · version(0) · created_at
Unique: `(uid, friend_uid)` —— A↔B 两行

## friend_applies — 好友申请

from_uid(index) · to_uid(index) · token(36) **unique**（接受/拒绝凭证） · remark(200)? · status(0=pending/1=accepted/2=rejected) · created_at/updated_at

## group_invite_links — 邀请链接

token(36) **unique** · chat_id(index) · creator_uid · name(200,"") · max_uses(0=无限) · use_count(0，**原子自增**) · expires_at(0=永久) · revoked_at(0=有效) · created_at

## sync_events — 事件同步队列（**离线补发核心**）

| 列 | 类型 | 语义 |
|----|------|------|
| id | auto-increment long | **eventId**（NOTIFY 回传 + 客户端游标） |
| uid | varchar(36) | index；每接收者一行 |
| event_type | integer | NotifyType.code |
| payload | binary | 契约 payload 编码字节 |
| created_at | long | **7 天 TTL**（过期不再补发） |

---

## 分布式/多端语义要点

1. **conversations 按 (uid, chat_id) 存储**：同一会话每个成员独立行——未读/已读/草稿天然按人隔离
2. **peer_read_seq 写在观察者行上**：A markRead 时更新 B 行的 `peer_read_seq`（B 观察"A 读到哪"），而非集中存 A 的状态——查询路径与 CONVERSATION_UPDATED 下发一致
3. **events 双索引模型**：sync_events(事件流,纵向补发) + message RocksDB chatSeq(消息体,横向窗口)——事件丢失可补、消息按 seq 任意窗口拉取，互为兜底
