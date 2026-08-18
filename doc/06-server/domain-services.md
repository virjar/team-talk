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

## 3. Chat / Member

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

## 4. Message

发送顺序：

1. 校验 sender 是当前认证用户且属于 Chat。
2. 规范化 MessageBody 与 Attachment。
3. 校验群禁言、消息类型和业务限制。
4. 在 FileStore 验证全部附件。
5. 按 clientMsgId 查询幂等结果。
6. 分配 serverSeq 并写 MessageStore。
7. 更新索引与各成员 Conversation。
8. 推送 MESSAGE_RECV 与 CONVERSATION_UPDATED。
9. 返回 ACK。

编辑只允许原发送者修改可编辑的文字消息；撤回允许发送者或有权限的管理员；转发产生新消息并
重新走目标会话权限和附件校验。

## 5. Conversation

ConversationService 维护用户视角状态：

- list/sync 返回合并后的会话快照。
- setDraft、setPin、setMute 只影响当前用户。
- markRead 用 max 合并 readSeq，更新自己所有设备并向会话成员同步 peer waterline。
- deleteConversation 删除收件箱视图，不删除 Chat 或消息。

`ensureConversations` 是建群、加人和邀请加入的强制步骤，不是可选修复。

## 6. Device / Presence

DeviceService 列出当前用户设备并踢除指定 deviceId。不能踢其他用户设备，当前设备自踢应有明确
连接关闭语义。

Presence 由 ClientRegistry 的连接计数派生：首个设备上线广播 online，最后一个设备下线广播
offline。它是瞬时状态，不进入长期离线事件。

## 7. Admin

管理员能力与普通用户领域调用分开认证。封禁用户需要同时影响后续登录和现有连接；不能只更新
管理表而让活动 token 继续无限使用。
