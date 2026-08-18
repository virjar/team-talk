# 领域服务

## 1. User / Auth

### 注册

服务端再次执行 AuthRules，检查用户名/手机号唯一性，生成短 uid，并用 BCrypt 保存密码哈希。uid
冲突重试必须有上限和安全随机源。

### 登录与 refresh

未知用户和密码错误使用相同外部错误。认证成功创建/更新 Device，签发随机 access/refresh token，
注册连接并从 lastEventId 开始补发。refresh token 成功使用后轮换。

### 资料更新

只能更新允许的个人字段；uid、用户名等身份键不能由普通资料接口覆盖。更新后向用户所有设备发送
USER_UPDATED。

## 2. Contact

- `apply`：不能申请自己、已有好友或被策略禁止的用户。
- `accept`：验证申请接收者和 token，在事务中形成双方关系。
- `reject`：只改变申请状态，不创建关系。
- `delete`：删除双方关系，并分别发送双方视角的 CONTACT_DELETED。
- 备注属于关系所有者，不更新对方 User。
- 黑名单在建立关系和发送交互时参与权限判断。

Contact 事件的 payload 视角必须明确；服务端不能把 A→B 的 Contact 原样发给 B。

## 3. Organization

OrganizationService 维护单根无环目录、用户多部门归属和唯一主部门。负责人必须是有效用户，并在
启用部门群时自动成为直属成员。删除负责人归属前必须先变更负责人。

部门群以 OrganizationUnit.groupChatId 是否存在为启用状态。收敛算法计算当前节点及全部后代成员，
再合并 RequiredChatParticipants（当前为获授权机器人），调用 ChatService 的受管群管理入口补齐成员、
移除多余成员并维护 Conversation。普通群 RPC 遇到受管群时拒绝成员或生命周期变更。

跨表流程采用“先组织事实、后幂等投影”：节点移动和成员变更提交后立即收敛，应用启动再重放全部
启用节点。单个失败会记录 unitId，不阻止其他部门恢复。

## 4. Chat / Member

### 私聊

创建私聊前验证两人关系与已有会话，避免同一成员组合重复生成多个私聊容器。

### 群聊

创建者成为群主，初始成员去重并验证存在。创建 Chat、Member 与各成员 Conversation 后再发送
CHAT_CREATED。

### 权限

| 动作 | 群主 | 管理员 | 成员 |
|---|---|---|---|
| 修改普通群资料 | 是 | 按当前规则 | 否 |
| 邀请成员 | 是 | 是 | 按群策略 |
| 移除普通成员 | 是 | 是 | 否 |
| 设置管理员 | 是 | 否 | 否 |
| 转让群主 | 是 | 否 | 否 |
| 解散群 | 是 | 否 | 否 |
| 退出群 | 先转让/解散 | 是 | 是 |

最终矩阵以 ChatService 当前实现和 RPC 验收为准。权限变化必须在一个操作内更新存储并发完整群快照。
退出只失效当前成员关系，解散才把 Chat 标记为非活跃；两者不能共享一个含糊的“删除群”用例。

## 5. Message

发送顺序：

1. 校验 sender 是当前认证用户且属于 Chat。
2. 规范化 MessageBody 与 Attachment。
3. 校验群禁言、消息类型和业务限制。
4. 在 FileStore 验证全部附件。
5. 按 clientMsgId 查询幂等结果。
6. 分配 serverSeq，原子写入 MessageStore、幂等索引、附件到会话反向索引和待投影 outbox。
7. 幂等投影到 Lucene、Conversation 和持久化同步事件。
8. 投影全部成功后清除 outbox，再返回 ACK。

若第 7 步中断，相同 `clientMsgId` 重试或服务端重启会继续补齐投影，不能只返回旧 seq。

编辑只允许原发送者修改可编辑的文字消息；撤回允许发送者或有权限的管理员；转发产生新消息并
重新走目标会话权限和附件校验。

## 6. Conversation

ConversationService 维护用户视角状态：

- list/sync 返回合并后的会话快照。
- setDraft、setPin、setMute 只影响当前用户。
- markRead 用 max 合并 readSeq，更新自己所有设备并向会话成员同步 peer waterline。
- deleteConversation 删除收件箱视图，不删除 Chat 或消息。

`ensureConversations` 是建群、加人和邀请加入的强制步骤，不是可选修复。

## 7. Bot

BotService 为每个通知应用创建 UserRole.BOT 服务账户。该账户的随机密码不返回，UserService 登录路径
也显式拒绝 BOT/SYSTEM。应用 token 使用 256-bit 随机值，数据库只保存 SHA-256。

授权是 `(botId, chatId)` 白名单事实。grant 只接受群聊，并把服务身份加入群；revoke/disable 先撤销
发送权，再移出群。发送使用由 `botId + chatId + idempotencyKey` 派生的 clientMsgId，随后进入正常
MessageService，因此重试、历史、搜索、同步和部门群保留规则都与普通消息一致。

## 8. Device / Presence

DeviceService 列出当前用户设备并踢除指定 deviceId。不能踢其他用户设备，当前设备自踢应有明确
连接关闭语义。

Presence 由 ClientRegistry 的连接计数派生：首个设备上线广播 online，最后一个设备下线广播
offline。它是瞬时状态，不进入长期离线事件。

## 9. Admin

管理员能力与普通用户领域调用分开认证。封禁用户需要同时影响后续登录和现有连接；不能只更新
管理表而让活动 token 继续无限使用。
