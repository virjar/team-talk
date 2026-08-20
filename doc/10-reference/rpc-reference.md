# RPC 参考

TeamTalk 使用 Kotlin interface 作为 IDL。`@RpcService("name")` 定义字符串 `serviceId`，每个方法用 `@RpcMethod(id)` 显式声明 wire 编号，KSP 生成 Contract、客户端 Proxy 和服务端 Stub。

## 兼容性规则

1. 已发布的 `serviceId` 和 `methodId` 不得改变含义。
2. 每个方法必须声明唯一、正数的 `@RpcMethod(id)`；声明顺序不参与编号，已有编号不得复用。
3. 方法必须是 `suspend`，参数和返回类型必须属于生成器支持的协议类型。
4. 修改 IDL 后必须同时实现服务端方法、客户端仓储封装和契约测试。
5. 注册、登录和 refresh 属于 TCP AUTH 握手，不属于下列 RPC。
6. 普通消息发送使用 MESSAGE / MESSAGE_ACK，不通过 `message` RPC。

源文件位于 `protocol/src/commonMain/kotlin/com/virjar/tk/rpc/def/`。

## generic（预留）

通用 RPC 逃生入口固定为字符串 `serviceId="generic"`，方法号直接等于 `ExtensionType.code`。
当前 `ExtensionType` 刻意为空，服务端也没有注册 generic dispatcher，因此该入口不是可调用功能；
首个真实扩展必须同时补稳定编号、会话所有的 handler、权限与契约测试。禁止写回旧的数字
`ServiceId.GENERIC(99)`，也禁止因为当前没有调用方就删除这项 wire 预留。

## auth

`logout` 不接受客户端身份参数；服务端只使用当前已认证连接绑定的 uid/deviceId，完整撤销
该设备凭证与登记。凭据事务提交后，服务端先将连接移出实时投递集合，再发送成功响应并关闭连接。

| ID | 方法 | 参数 | 返回 | 说明 |
|---:|---|---|---|---|
| 1 | `logout` | 无 | `Unit` | 吊销当前认证设备的凭证并关闭本连接 |
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
| 9 | `listPendingApplies` | — | `List<ContactApply>`（收到且待处理，最新 100 条） |
| 10 | `listBlacklist` | — | `List<Contact>` |
| 11 | `listApplyRecords` | `beforeId`, `limit` | `List<ContactApplyRecord>`（双向历史，id 倒序） |
| 12 | `getPendingApply` | `targetUid` | `ContactApplyLookup`（两人间精确 pending） |

好友关系和黑名单权限必须由服务器判断。客户端列表是投影，不能作为能否发送或查看资料的权威依据。
`ContactApply` 是收件人处理动作的定向投影；`ContactApplyRecord` 是双向历史查询投影。申请处理
token 只向收到申请的一方返回，发出记录和已处理记录中的 token 始终为空。

## chat

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `createPersonal` | `targetUid` | `Chat` |
| 2 | `createGroup` | `name`, `avatar?`, `memberUids` | `Chat` |
| 3 | `get` | `chatId` | `Chat` |
| 4 | `update` | `chatId`, `name?`, `avatar?`, `notice?` | `Unit` |
| 5 | `delete` | `chatId` | `Unit`（仅群主解散；保留的旧方法名） |
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
| 20 | `leaveGroup` | `chatId` | `Unit` |

群权限以服务端 Member role 为准：成员不能执行管理操作；管理员不能越过群主管理群主或同级规则；
普通成员/管理员使用 `leaveGroup`，群主使用 `delete` 解散，单聊只允许删除自己的 Conversation 视图。

## message

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `getHistory` | `chatId`, `fromSeq`, `limit` | `List<Message>` |
| 2 | `search` | `chatId`, `keyword`, `limit` | `List<Message>` |
| 3 | `revoke` | `chatId`, `serverSeq` | `Unit` |
| 4 | `edit` | `msg: Message` | `Unit` |
| 5 | `forward` | `srcChatId`, `srcSeq`, `targetChatId` | `Message` |
| 6 | `markRead` | `chatId`, `readSeq` | `Unit` |

`getHistory(fromSeq = 0)` 从最新位置取倒序窗口。历史与搜索的 `limit` 必须在 1..10；该上限同时约束存储/索引扫描，并为最坏消息正文保留 16 MiB 响应帧余量。搜索、历史、编辑、撤回和转发都必须校验调用者当前仍属于相关会话。发送新消息的幂等键是 `clientMsgId`，成功 ACK 返回服务器分配的 seq。

## conversation

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `list` | — | `List<Conversation>` |
| 3 | `setDraft` | `chatId`, `draft?` | `Unit` |
| 4 | `setPin` | `chatId`, `pinned` | `Unit` |
| 5 | `setMute` | `chatId`, `muted` | `Unit` |
| 6 | `delete` | `chatId` | `Unit` |

编号 2 的旧 `sync(afterVersion)` 在发布前已删除并保留编号空洞：`version` 是单行会话版本，
不是用户全局游标，无法安全表达增量同步。客户端重放使用持久 `sync_events` 流，权威会话快照使用 `list`。

会话是“用户对聊天的视图”，因此草稿、置顶、静音和 readSeq 属于用户维度，而不是 Chat 的全局属性。

## device

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `listDevices` | — | `List<Device>` |
| 2 | `kickDevice` | `deviceId` | `Unit` |

`kickDevice` 使用稳定编号 `@RpcMethod(2)`。踢出设备同时吊销该设备凭证并关闭活跃连接。

## organization

| ID | 方法 | 参数 | 返回 | 说明 |
|---:|---|---|---|---|
| 1 | `listUnits` | — | `List<OrganizationUnit>` | 返回当前单组织的活动节点及各节点直属人数 |
| 2 | `listMembers` | `unitId`, `recursive` | `List<OrganizationMember>` | 直属或包含子树的成员 |

普通客户端只有读取能力；组织结构、成员归属和部门群启停通过独立管理 HTTP API 执行。
`OrganizationUnit.directMemberCount` 与 `listMembers(unitId, recursive = false)` 的直属成员口径一致，
根节点也不例外；子部门成员不重复计入。

## groupFile

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `list` | `chatId`, `parentId?` | `List<GroupFileEntry>` |
| 2 | `createFolder` | `chatId`, `parentId?`, `name` | `GroupFileEntry` |
| 3 | `createFile` | `chatId`, `parentId?`, `name`, `attachment` | `GroupFileEntry` |
| 4 | `addVersion` | `chatId`, `entryId`, `attachment`, `expectedRevision` | `GroupFileEntry` |
| 5 | `listVersions` | `chatId`, `entryId` | `List<GroupFileVersion>` |
| 6 | `rename` | `chatId`, `entryId`, `name`, `expectedRevision` | `GroupFileEntry` |
| 7 | `delete` | `chatId`, `entryId`, `expectedRevision` | `Unit` |

所有方法都按认证 uid 实时校验群成员。createFile/addVersion 只接受调用者自己上传且与 FileStore 元数据
完全匹配的 Attachment；expectedRevision 是条目级乐观锁，不能用 contentVersion 代替。

## document

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `listSpaces` | — | `List<DocumentSpace>` |
| 2 | `createSpace` | `name`, `description?` | `DocumentSpace` |
| 3 | `updateSpace` | `spaceId`, `name`, `description?` | `DocumentSpace` |
| 4 | `archiveSpace` | `spaceId` | `Unit` |
| 5 | `listGrants` | `spaceId` | `List<DocumentSpaceGrant>` |
| 6 | `upsertGrant` | `spaceId`, `principalType`, `principalId`, `role`, `includeDescendants` | `DocumentSpaceGrant` |
| 7 | `removeGrant` | `spaceId`, `principalType`, `principalId` | `Unit` |
| 8 | `listNodes` | `spaceId`, `parentId?` | `List<DocumentNode>` |
| 9 | `createFolder` | `spaceId`, `parentId?`, `name` | `DocumentNode` |
| 10 | `createDocument` | `spaceId`, `parentId?`, `title`, `markdown` | `Document` |
| 11 | `getDocument` | `spaceId`, `documentId` | `Document` |
| 12 | `updateDocument` | `spaceId`, `documentId`, `title`, `markdown`, `expectedRevision` | `Document` |
| 13 | `moveNode` | `spaceId`, `nodeId`, `parentId?`, `name`, `expectedRevision` | `DocumentNode` |
| 14 | `deleteNode` | `spaceId`, `nodeId`, `expectedRevision` | `Unit` |
| 15 | `listRevisions` | `spaceId`, `documentId` | `List<DocumentRevisionSummary>` |
| 16 | `getRevision` | `spaceId`, `documentId`, `revision` | `DocumentRevision` |
| 17 | `listRecentDocuments` | `limit` | `List<DocumentHomeItem>` |
| 18 | `listRecentlyCreatedDocuments` | `limit` | `List<DocumentHomeItem>` |

文档只以独立空间为权限根，不接受群 scope。所有调用按认证 uid 合并空间所有权、用户授权和实时组织部门
授权。目录列表不返回 Markdown，修订列表只返回摘要；更新、移动和删除必须使用客户端实际读取到的
revision，冲突由服务端拒绝，错误文案不作为并发控制协议。

首页两类列表都限制 `limit` 为 1..50，并只返回调用者当前仍可访问的活动空间与活动文档。最近访问按
当前用户成功打开正文的服务端时间排序；最近创建按所有可访问空间的 `createdAt` 排序，并非“我创建
的文档”。`DocumentHomeItem` 只携带有界摘要、空间、创建人和时间元数据，正文仍通过 `getDocument`
按需读取。

## 状态与错误

| status | 语义 | 客户端处理 |
|---:|---|---|
| 0 | 成功 | 解码 result |
| 400 | 参数或业务规则拒绝 | 显示可理解业务错误，不自动重试 |
| 401 | 认证失效 | 结束当前会话并回到登录流程 |
| 500 | 服务内部错误 | 记录 correlation 信息，显示通用错误 |
| 504 | 客户端等待响应超时 | 作为超时处理；写操作需考虑服务端是否已执行 |

错误文案不是稳定协议；需要跨语言或程序化处理的场景应逐步增加稳定错误 code，而不是解析中文 message。
