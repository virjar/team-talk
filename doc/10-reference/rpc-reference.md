# RPC 参考

TeamTalk 使用 Kotlin interface 作为 IDL。`@RpcService("name")` 定义字符串 `serviceId`，KSP 生成 Contract、客户端 Proxy 和服务端 Stub。默认 `methodId` 按声明顺序从 1 分配。

## 兼容性规则

1. 已发布的 `serviceId` 和 `methodId` 不得改变含义。
2. 新方法追加在 interface 末尾；确需重排时用 `@RpcMethod(id)` 锁定旧编号。
3. 方法必须是 `suspend`，参数和返回类型必须属于生成器支持的协议类型。
4. 修改 IDL 后必须同时实现服务端方法、客户端仓储封装和契约测试。
5. 注册、登录和 refresh 属于 TCP AUTH 握手，不属于下列 RPC。
6. 普通消息发送使用 MESSAGE / MESSAGE_ACK，不通过 `message` RPC。

源文件位于 `protocol/src/commonMain/kotlin/com/virjar/tk/rpc/def/`。

## auth

| ID | 方法 | 参数 | 返回 | 说明 |
|---:|---|---|---|---|
| 1 | `logout` | `refreshToken: String?` | `Unit` | 吊销当前 refresh token |
| 2 | `updatePassword` | `oldPassword`, `newPassword` | `Unit` | 校验旧密码并更新 |

## user

| ID | 方法 | 参数 | 返回 | 说明 |
|---:|---|---|---|---|
| 1 | `getProfile` | `targetUid: String?` | `User` | 空 uid 表示当前用户 |
| 2 | `updateProfile` | `user: User` | `Unit` | 服务端以认证 uid 为准 |
| 3 | `search` | `keyword: String` | `List<User>` | 用户搜索 |

## contact

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `list` | — | `List<Contact>` |
| 2 | `apply` | `targetUid`, `remark?` | `ContactApply` |
| 3 | `accept` | `token` | `ContactApply` |
| 4 | `reject` | `token` | `ContactApply` |
| 5 | `delete` | `friendUid` | `Unit` |
| 6 | `setRemark` | `friendUid`, `remark?` | `Unit` |
| 7 | `blacklist` | `targetUid` | `Unit` |
| 8 | `removeFromBlacklist` | `targetUid` | `Unit` |
| 9 | `listApplies` | — | `List<ContactApply>` |
| 10 | `listBlacklist` | — | `List<Contact>` |

好友关系和黑名单权限必须由服务器判断。客户端列表是投影，不能作为能否发送或查看资料的权威依据。

## chat

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `createPersonal` | `targetUid` | `Chat` |
| 2 | `createGroup` | `name`, `avatar?`, `memberUids` | `Chat` |
| 3 | `get` | `chatId` | `Chat` |
| 4 | `update` | `chatId`, `name?`, `avatar?`, `notice?` | `Unit` |
| 5 | `delete` | `chatId` | `Unit` |
| 6 | `addMembers` | `chatId`, `uids` | `Unit` |
| 7 | `removeMembers` | `chatId`, `targetUid` | `Unit` |
| 8 | `getMembers` | `chatId` | `List<Member>` |
| 9 | `transferOwner` | `chatId`, `newOwnerUid` | `Unit` |
| 10 | `setRole` | `chatId`, `targetUid`, `role` | `Unit` |
| 11 | `muteMember` | `chatId`, `targetUid`, `durationSeconds` | `Unit` |
| 12 | `unmuteMember` | `chatId`, `targetUid` | `Unit` |
| 13 | `muteAll` | `chatId` | `Unit` |
| 14 | `unmuteAll` | `chatId` | `Unit` |
| 15 | `createInviteLink` | `chatId`, `name`, `maxUses`, `expiresAt` | `String` token |
| 16 | `listInviteLinks` | `chatId` | `List<InviteLink>` |
| 17 | `revokeInviteLink` | `token` | `Unit` |
| 18 | `joinByInvite` | `token` | `Chat` |
| 19 | `getInviteInfo` | `token` | `InviteLink` |

群权限以服务端 Member role 为准：成员不能执行管理操作；管理员不能越过群主管理群主或同级规则；群主退出前必须转让或解散。

## message

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `getHistory` | `chatId`, `fromSeq`, `limit` | `List<Message>` |
| 2 | `search` | `chatId`, `keyword`, `limit` | `List<Message>` |
| 3 | `revoke` | `chatId`, `serverSeq` | `Unit` |
| 4 | `edit` | `msg: Message` | `Unit` |
| 5 | `forward` | `srcChatId`, `srcSeq`, `targetChatId` | `Message` |
| 6 | `markRead` | `chatId`, `readSeq` | `Unit` |

`getHistory(fromSeq = 0)` 从最新位置取倒序窗口。搜索、历史、编辑、撤回和转发都必须校验调用者属于相关会话。发送新消息的幂等键是 `clientMsgId`，成功 ACK 返回服务器分配的 seq。

## conversation

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `list` | — | `List<Conversation>` |
| 2 | `sync` | `afterVersion` | `List<Conversation>` |
| 3 | `setDraft` | `chatId`, `draft?` | `Unit` |
| 4 | `setPin` | `chatId`, `pinned` | `Unit` |
| 5 | `setMute` | `chatId`, `muted` | `Unit` |
| 6 | `delete` | `chatId` | `Unit` |

会话是“用户对聊天的视图”，因此草稿、置顶、静音和 readSeq 属于用户维度，而不是 Chat 的全局属性。

## device

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `listDevices` | — | `List<Device>` |
| 2 | `kickDevice` | `deviceId` | `Unit` |

`kickDevice` 显式使用 `@RpcMethod(2)`。踢出设备同时吊销该设备凭证并关闭活跃连接。

## 状态与错误

| status | 语义 | 客户端处理 |
|---:|---|---|
| 0 | 成功 | 解码 result |
| 400 | 参数或业务规则拒绝 | 显示可理解业务错误，不自动重试 |
| 401 | 认证失效 | 结束当前会话并回到登录流程 |
| 500 | 服务内部错误 | 记录 correlation 信息，显示通用错误 |
| 504 | 客户端等待响应超时 | 作为超时处理；写操作需考虑服务端是否已执行 |

错误文案不是稳定协议；需要跨语言或程序化处理的场景应逐步增加稳定错误 code，而不是解析中文 message。
