# RPC 参考

TeamTalk 使用 Kotlin interface 作为 IDL。`@RpcService("name")` 定义字符串 `serviceId`，每个方法用 `@RpcMethod(id)` 显式声明 wire 编号，KSP 生成 Contract、客户端 Proxy 和服务端 Stub。

## 兼容性规则

1. 已发布的 `serviceId` 和 `methodId` 不得改变含义。
2. 每个方法必须声明唯一、正数的 `@RpcMethod(id)`；声明顺序不参与编号；已发布版本的编号在兼容
   演进中不得复用。
3. 方法必须是 `suspend`，参数和返回类型必须属于生成器支持的协议类型。
4. 修改 IDL 后必须同时实现服务端方法、客户端仓储封装和契约测试。
5. 注册、登录和 refresh 属于 TCP AUTH 握手，不属于下列 RPC。
6. 普通消息发送使用 MESSAGE / MESSAGE_ACK，不通过 `message` RPC。

当前表是尚未正式发布的协议 v22 开发基线，不是“已经发布、永久不可变”的历史登记表。发布前仍可在
同步更新所有端、协议版本、生成契约、golden test 和本文档的前提下做协调式破坏性调整；首次正式
发布后，实际发布版本中的编号才进入第 1、2 条兼容性承诺。

源文件位于 `protocol/protocol/src/commonMain/kotlin/com/virjar/tk/protocol/rpc/def/`。

版本支持窗、退役与编号冻结见[版本机制](../04-protocol/versioning.md)。旧 generic 预留已在零号基线删除。

## sync

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `beginCheckpoint` | `datasetId` | `SyncCheckpointHeader(datasetId, checkpointId, baseEventId, currentUser)` |
| 2 | `listCheckpointContacts` | `SyncCheckpointPageRequest(checkpointId, cursor?)` | `SyncCheckpointContactPage` |
| 3 | `listCheckpointChats` | `SyncCheckpointPageRequest(checkpointId, cursor?)` | `SyncCheckpointChatPage` |
| 4 | `listCheckpointConversations` | `SyncCheckpointPageRequest(checkpointId, cursor?)` | `ConversationPage` |

`sync` 是唯一允许在 `SYNCHRONIZING` 状态调用的 INVOKE service，普通业务 RPC 仍只在
`AUTHENTICATED` 后开放。`beginCheckpoint` 把 checkpoint 绑定到当前认证连接；后续页必须复用
同一 `checkpointId` 和服务端返回的不透明独占游标。联系人与 Chat 每页最多 256 条，
Conversation 复用自身最多 16 条的页契约。客户端必须收齐 currentUser、Contact、Chat 和
Conversation 后才可发布，不能逐页替换本地投影。

header 的 `baseEventId` 是安装 checkpoint 后继续请求持久事件 tail 的游标。各个
section 的 keyset 页由独立数据库读取产生，不宣称它们共用一个 MVCC snapshot；页间
并发变化由 `baseEventId` 之后的 tail 最终收敛。

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
| 2 | `updateProfile` | `patch: ProfilePatch` | `Unit` | `Unchanged` 保持原值，`Set(null)` 显式清除 nullable avatar/phone；非空 avatar 必须是本人 staging 的 canonical JPEG/PNG/WebP `Attachment`（≤ 8 MiB）；服务端以认证 uid 为准 |
| 3 | `search` | `keyword: String` | `List<User>` | 用户搜索 |

只有实际资料变化才会原子递增 `User.revision` 并提交本人/提交时活动好友的完整 `USER_UPDATED(User)`，随后发布 FileStore 对象；
退出附件围栏后，同一 User 再以 `eventId=0` best-effort 发给其余 SYNC_READY 会话，排除上述 durable 收件人。
若 PG 已提交而 FileStore 标记失败，仍先广播 committed User 再原样返回标记错误；广播自身失败不改变已提交结果。无变化不写 `updated_at`、不发事件。发布或响应丢失时，
同一完整描述符重试会补齐发布且不重复写头像；替换/清除前也会先修复当前 staging 头像。已绑定但不再是
当前头像的对象不能再次提交，避免旧重试覆盖新资料。

## contact

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `list` | — | `List<Contact>` |
| 2 | `apply` | `targetUid`, `remark?` | `ContactApply` |
| 3 | `accept` | `operationId`, `issuedAt`, `token` | `ContactApply` |
| 4 | `reject` | `operationId`, `issuedAt`, `token` | `ContactApply` |
| 5 | `delete` | `friendUid` | `Unit` |
| 6 | `setRemark` | `friendUid`, `remark?` | `Unit` |
| 7 | `blacklist` | `targetUid` | `Unit` |
| 8 | `removeFromBlacklist` | `targetUid` | `Unit` |
| 9 | `listPendingApplies` | — | `List<ContactApply>`（完整收到待处理视图，硬上限 100 条） |
| 10 | `listBlacklist` | — | `List<Contact>` |
| 11 | `listApplyRecords` | `beforeId`, `limit` | `List<ContactApplyRecord>`（双向历史，id 倒序） |
| 12 | `getPendingApply` | `targetUid` | `ContactApplyLookup`（两人间精确 pending） |
| 13 | `getPresenceSnapshot` | — | `FriendPresenceSnapshot(serverEpoch, revision, friendUids, onlineFriendUids)` |

好友关系和黑名单权限必须由服务器判断。客户端列表是投影，不能作为能否发送或查看资料的权威依据。
`ContactApply` 是收件人处理动作的定向投影；`ContactApplyRecord` 是双向历史查询投影。申请处理
token 只向收到申请的一方返回，发出记录和已处理记录中的 token 始终为空。
`accept/reject.operationId` 是客户端在首次 RPC 前持久化的规范 UUID，`issuedAt` 是同一次本地提交的固定毫秒
时间戳。客户端按申请 token 冻结一种决定，
超时、断线、进程恢复、403、429 或 5xx 必须复用同一 ID；明确的其他 4xx 才结束该本地命令。403 可能是
原命令提交后权限变化导致的重放拒绝，不能清除待确认 generation。服务端把处理结果、
关系变更、CONTACT_ACCEPTED 事件与 `(receiverUid, operationId)` 收据放在同一事务，同 ID 同 payload 返回原
`ContactApply` 且不重复发事件，同 ID 改写 issuedAt、token 或接受/拒绝类型返回 409。可靠期限为 7 天，允许
客户端时钟领先 15 分钟；过期返回 410。每个 receiver 在期限内最多保留 1,024 条处理收据，满额新命令返回
429 而不淘汰旧回执；客户端本地同时待确认的处理命令最多 128 条。

`getPresenceSnapshot` 没有 uid 参数，只能查询当前认证用户。服务端先从 ContactRepository 取得该用户的
完整好友集合，再把整组候选一次交给 `ClientRegistry`；Registry 在同一个串行 owner 命令中读取当前
`serverEpoch + revision` 与在线子集，不循环调用单用户在线查询，也不向 RPC 层暴露全局在线集合。
`friendUids` 与 `onlineFriendUids` 都按 uid 严格升序、去重且各不超过 4,000 项，后者必须是前者的子集；
epoch 是规范 UUID，首次快照允许 `revision = 0`。

## chat

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `createPersonal` | `targetUid` | `Chat` |
| 2 | `createGroup` | `operationId`, `name`, `avatar?`, `memberUids` | `Chat` |
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
| 15 | `createInviteLink` | `operationId`, `issuedAt`, `chatId`, `name`, `maxUses`, `expiresAt` | `String` token |
| 16 | `listInviteLinks` | `chatId` | `List<InviteLink>` |
| 17 | `revokeInviteLink` | `token` | `Unit` |
| 18 | `joinByInvite` | `token` | `Chat` |
| 19 | `getInviteInfo` | `token` | `InviteLink` |
| 20 | `leaveGroup` | `chatId` | `Unit` |

`createGroup.memberUids` 与 `addMembers.uids` 在去重前最多各 1,000 项；creator 占群容量名额。群内所有
HUMAN/BOT/SYSTEM 活跃身份合计最多 1,000，超限返回固定“群成员数量已达上限”且不泄露人数或目标状态。
`createGroup.operationId` 必须是客户端生成并在未知结果重试中复用的规范 UUID。服务端以
`(creatorUid, operationId)` 持久唯一收据和规范请求指纹保证精确重放返回同一群；同 ID 改写群名、头像
或规范初始成员返回 409。收据与 Chat、初始 Member/Conversation、容量台账和 CHAT_CREATED 事件原子提交。

`createInviteLink.operationId` 与 `issuedAt` 同样由客户端在首次 RPC 前持久化；完整的
issuedAt/chat/name/maxUses/expiresAt 是不可变
payload。服务端在同一事务写邀请链接和回执，同 ID 精确重放返回原 token（即使链接后来被撤销）且不再占用
链接容量，同 ID 改写 payload 返回 409。重放仍必须重新通过当前活动群、用户可写权威和管理员权限校验，
因此已被移除或降权的旧创建者不能取回秘密 token。回执可靠期限为 7 天，过期返回 410；每个创建者期限内
最多 256 条，满额新命令返回 429 且不逐出旧回执。客户端每个群只允许一个待确认创建、账号同时最多 128 条。

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
| 7 | `addReaction` | `chatId`, `serverSeq`, `emoji` | `Unit` |
| 8 | `removeReaction` | `chatId`, `serverSeq`, `emoji` | `Unit` |
| 9 | `listReactions` | `chatId`, `fromSeq`, `toSeq` | `List<MessageReactionSummary>` |

回应增删是 row-keyed `(chatId, serverSeq, emoji, uid)` 幂等命令：重复 add/remove 第二次成功且不产生
事件；操作者必须是当前聊天成员，目标消息必须存在且未撤回，每用户每消息至多 12 个不同 emoji。聚合
计数永远由服务端 `message_reactions` 表派生；`listReactions` 返回闭区间快照，客户端只做行级投影。
消息撤回在同一 PostgreSQL 事务删除其全部回应；成员离群后历史回应保留为事实但不再接受新回应。

`getHistory(fromSeq = 0)` 从最新位置取倒序窗口。历史与搜索的 `limit` 必须在 1..10；该上限同时约束存储/索引扫描，并为最坏消息正文保留 16 MiB 响应帧余量。搜索、历史、编辑、撤回和转发都必须校验调用者当前仍属于相关会话。发送新消息和 `MESSAGE_ACK` 使用 `chatId + clientMsgId` 复合身份，成功 ACK 返回服务器分配的 seq。

## conversation

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `listPage` | `request: ConversationPageRequest` | `ConversationPage`（最多 16 条） |
| 2 | `setDraft` | `chatId`, `draft?` | `Unit` |
| 3 | `setPin` | `chatId`, `pinned` | `Unit` |
| 4 | `setMute` | `chatId`, `muted` | `Unit` |
| 5 | `delete` | `chatId` | `Unit` |

客户端重放使用持久 `sync_events` 流，权威会话快照使用 `listPage`。首页的
`request.cursor = null`；后续页原样回传上一页的 opaque `nextCursor`，直到其为 null。
服务端在权限过滤后按不可变 `chatId DESC` 做 keyset `limit + 1`；客户端收齐全部页后才原子替换
本地投影，不得逐页发布或静默截断。

会话是“用户对聊天的视图”，因此草稿、置顶、静音和 readSeq 属于用户维度，而不是 Chat 的全局属性。
PERSONAL `Conversation` 必须携带 `peerUid` 与正数 `peerRevision`，`chatAvatar` 是该版本对端的完整用户头像描述符快照；GROUP 和
删除哨兵的 `peerUid/peerRevision/chatAvatar` 必须为空。客户端以 `peerUid` 和 revision 观察规范 User 并优先展示不旧于快照的当前 name/avatar，
Conversation 字段只作冷启动快照；因此头像清除后不得复活旧快照。群头像仍由旧 Chat 字符串字段承载且不在本批能力内。

## device

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `listDevices` | — | `List<Device>` |
| 2 | `kickDevice` | `deviceId` | `Unit` |

`kickDevice` 自版本 10 起固定使用 `@RpcMethod(2)`。踢出设备同时吊销该设备凭证并关闭活跃连接。

## organization

| ID | 方法 | 参数 | 返回 | 说明 |
|---:|---|---|---|---|
| 1 | `listUnitPage` | `OrganizationUnitPageRequest` | `OrganizationUnitPage` | revision 绑定的活动节点 keyset 页，最多 256 条 |
| 2 | `listMemberPage` | `OrganizationMemberPageRequest` | `OrganizationMemberPage` | 直属或子树关系 keyset 页，最多 256 条 |

普通客户端只有读取能力；上述两个方法都通过长连接上的强类型二进制 RPC 执行，不走 JSON/HTTP。
组织结构、成员归属和部门群启停通过独立的 `/api/admin/organization/**` HTTP 控制面执行。管理写与
终端读是有意分离的两个入口，不能因为控制面使用 HTTP 就把终端目录回读改成 HTTP。
`OrganizationUnit.directMemberCount` 与 `listMemberPage(unitId, recursive = false)` 的直属成员口径一致，
根节点也不例外；子部门成员不重复计入。两类页都携带 `revision`、`nextCursor` 和
`snapshotChanged`；后者为 true 时 items 必为空且 cursor 必为 null，调用方必须丢弃此前所有页。
每页在返回 items 前复验完整活动目录：非空时恰好一根、无断链/循环、最多 10,000 节点，根深度为 1 且最多 64 层。
损坏或越界目录整页失败，不返回截断数据。

管理写提交后，服务端可向已完成 `SYNC_READY` 的连接发送
`ORGANIZATION_CHANGED(revision)` 瞬时提示。提示不携带目录行，也不改变本 RPC 的权威性；客户端收到后
仍须重新收齐同一 revision 的分页快照。断线期间的提示不补发，每次连接重新进入 `AUTHENTICATED`
都会重新执行 revision 绑定的全量 RPC 对账。

## groupFile

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `list` | `chatId`, `parentId?` | `List<GroupFileEntry>` |
| 2 | `createFolder` | `entryId`, `commandId`, `chatId`, `parentId?`, `name` | `GroupFileEntry` |
| 3 | `createFile` | `entryId`, `commandId`, `chatId`, `parentId?`, `name`, `attachment` | `GroupFileEntry` |
| 4 | `addVersion` | `commandId`, `chatId`, `entryId`, `attachment`, `expectedRevision` | `GroupFileEntry` |
| 5 | `listVersions` | `chatId`, `entryId` | `List<GroupFileVersion>` |
| 6 | `rename` | `commandId`, `chatId`, `entryId`, `name`, `expectedRevision` | `Unit` |
| 7 | `delete` | `commandId`, `chatId`, `entryId`, `expectedRevision` | `Unit` |
| 8 | `getEntry` | `chatId`, `entryId` | `GroupFileEntry` |

读取与新变更的首次交付都按认证 uid 实时校验群成员。createFile/addVersion 只接受调用者自己上传且与
FileStore 元数据完全匹配的 Attachment；expectedRevision 是条目级乐观锁，不能用 contentVersion 代替。
createFolder/createFile 的 `entryId`、五类变更的 `commandId` 都必须是客户端生成的规范 UUID，
并在未知结果重试时原样复用；相同命令与规范化不可变 payload 的精确重放只返回已提交事实，
不再占用条目、同级或版本容量，相同 ID 搭配不同 payload 则拒绝。已提交 rename/delete 的精确 Unit 收据
在操作者后来被移出群后仍可返回 ACK；它不返回当前条目、资产或秘密，也不授予新的读写能力。
`PENDING / ACKNOWLEDGED / REJECTED` 是客户端可靠 outbox 的本地状态，不是本表中的 wire 返回值。

## document

| ID | 方法 | 参数 | 返回 |
|---:|---|---|---|
| 1 | `listSpaces` | `DocumentSpacePageRequest(cursor?, limit = 32)` | `DocumentSpacePage(items, nextCursor?)` |
| 2 | `createSpace` | `spaceId`, `name`, `description?` | `DocumentSpaceCreateResult(spaceId, space?)` |
| 3 | `updateSpace` | `spaceId`, `name`, `description?` | `DocumentSpace` |
| 4 | `archiveSpace` | `spaceId`, `operationId` | `Unit` |
| 5 | `listGrants` | `spaceId` | `DocumentSpaceGrantPage`（完整快照，最多 1000 条并拒绝重复/非法主体） |
| 6 | `upsertGrant` | `spaceId`, `principalType`, `principalId`, `role`, `includeDescendants`, `expectedPolicyRevision`, `operationId`, `issuedAt` | `DocumentPolicyMutationResult` |
| 7 | `removeGrant` | `spaceId`, `principalType`, `principalId`, `expectedPolicyRevision`, `operationId`, `issuedAt` | `DocumentPolicyMutationResult` |
| 8 | `listNodes` | `spaceId`, `parentId?` | `List<DocumentNode>` |
| 9 | `createDocument` | `documentId`, `spaceId`, `parentId?`, `title`, `DocumentContent(markdown, assets)` | `DocumentCreateResult(documentId, document?)` |
| 10 | `getDocument` | `spaceId`, `documentId` | `Document` |
| 11 | `updateDocument` | `spaceId`, `documentId`, `DocumentContent(markdown, assets)`, `expectedRevision` | `Document` |
| 12 | `moveNode` | `spaceId`, `nodeId`, `parentId?`, `name`, `expectedRevision`, `operationId`, `issuedAt` | `DocumentMoveCommandResult(operationId, result?)` |
| 13 | `deleteNode` | `spaceId`, `nodeId`, `expectedRevision`, `operationId` | `Unit` |
| 14 | `listRevisions` | `spaceId`, `documentId`, `beforeRevision`, `limit` | `DocumentRevisionPage` |
| 15 | `getRevision` | `spaceId`, `documentId`, `revision` | `DocumentRevision` |
| 16 | `listRecentDocuments` | `limit` | `List<DocumentHomeItem>` |
| 17 | `listRecentlyCreatedDocuments` | `limit` | `List<DocumentHomeItem>` |
| 18 | `transferSpaceCustody` | `spaceId`, `ownerPrincipalType`, `ownerPrincipalId`, `stewardUid`, `expectedCustodyRevision`, `operationId` | `DocumentCustodyTransferResult` |
| 19 | `getNodePathSpine` | `spaceId`, `nodeId` | `DocumentPathSpine(nodes: root → target)` |

`listSpaces` 的 `limit` 范围是 1..64；游标是不透明、版本化的独占 `spaceId` 锚点。单页最多 64 个互异
空间，精确协议编码不得超过 256 KiB。调用方不得解释或拼接游标，也不得把某空间不在第一页解释为撤权。

`createSpace` 的 `spaceId` 是创建提交确认；可选 `space` 是返回时刻的权限投影。首次创建或精确重放时，
若空间仍活动且原创建者仍为当前 steward，`space` 携带当前 owner、steward、custodyRevision 和 `myRole`；
创建者交接后不再是 steward，或空间归档后的精确重放返回同一 `spaceId` 与 `space = null`。后者仍是成功结果，客户端应完成创建
outbox 并退休旧投影，不能补造 Owner 或继续重试同一已提交命令；null 不否定可能存在的显式 grant，
实际角色只能由后续 `listSpaces` 等实时授权读取重建。

`createDocument` 的 `documentId` 同样是稳定提交确认。首次创建返回完整 `document`；已提交命令的精确重放
返回相同 `documentId`，并允许 `document = null`，即使响应丢失后创建者已经失权、空间归档或文档软删除。
调用方必须将 null 视为命令成功并完成对应创建 outbox，不能从原始 payload 补造当前文档投影；需要当前事实时
只能另行调用实时授权的读取。精确确认要求 actor、space 与不可变创建指纹全匹配；改写 payload 或有权限的
跨 actor/ID 碰撞返回 409，未授权请求仍由实时 EDIT 门禁拒绝。
创建指纹与更新 CAS 都覆盖 `DocumentContent` 的 Markdown 和 canonical 资产清单；清单不完整、
元数据漂移或引用地址畸形都在写入修订前拒绝。`Document`、`DocumentRevision` 返回与各自
Markdown 快照相匹配的完整资产清单，历史修订不使用当前清单猜测。

`updateDocument` 是 content-only CAS；它不携带 title，服务端使用锁内当前名称创建内容修订。标题与
parent 只通过 `moveNode` 修改，客户端需要同时改名和保存正文时必须按返回 revision 串行两条写入，不能
让普通正文重试绕过可靠结构命令。

`getNodePathSpine` 在一次授权读事务中返回活动根节点到目标节点的完整摘要链，最多 129 个
`DocumentNode`；所有节点必须属于请求空间、父链连续且身份互异。它不返回 Markdown，也不把路径节点
等同于任一级完整子节点列表。空间实时 ACL 不足返回 `403`；目标缺失、已删除或属于其他空间返回 `404`。

`moveNode` 的 `operationId + issuedAt` 与 parent/name/expectedRevision 一起在首个请求前冻结。首次提交
返回非空 `DocumentMoveResult`；精确重放返回相同 operationId 与 `result = null`，只证明原结构副作用
已经提交。服务端按 actor 保存 7 天有限收据，每 actor 最多 1,024 条活动身份；同 ID 异 payload 为 409，
过期为 410，容量满为 429，no-op 同样占一条收据但不推进 revision。客户端不得从空 result 补造位置，
必须读取当前 Document 或 path spine 收敛后再结束本地可靠命令。

文档只以独立空间为权限根，不接受群 scope。所有调用按认证 uid 合并当前 steward 的隐式 Owner、用户 grant 和实时组织部门
grant。`DocumentSpace.createdBy` 仅为不可变来源；`ownerPrincipalType/ownerPrincipalId` 为可转移业务归属，不是 ACL。文档树列表不返回 Markdown，修订列表只返回持久化小字段摘要；`beforeRevision=0` 请求最新页，后续使用返回的独占 revision 游标，`limit` 上限为 100。每篇文档都可作为叶节点或其他文档的父节点。更新、移动和删除必须使用客户端实际读取到的
revision，冲突由服务端拒绝，错误文案不作为并发控制协议。
该 revision 是标题、正文、父级和删除状态共享的节点聚合版本：完全 no-op 不推进，但陈旧请求仍冲突；
纯父级移动推进聚合版本而不追加内容修订，所以修订历史序号可以不连续。
文档 `updateDocument`、`moveNode` 与 `deleteNode` 的 revision/CAS 冲突统一返回 status `409`；树环、层级、
名称和游标等普通参数或业务规则拒绝仍返回 `400`。指定空间的精确访问中，空间不存在或已归档返回
`404`，活动空间存在但当前 uid 的实时 ACL 不足返回 `403`；通过空间 ACL 后，缺失、已删除或属于其他
空间的精确文档/父节点统一返回 `404`。授权变更只在调用者通过空间 ACL 后校验并公开目标主体是否存在。
客户端不得通过匹配中文错误文案识别任何一种状态。
`archiveSpace` 与 `deleteNode` 的 `operationId` 是客户端发送前持久化的 canonical UUID；同一命令的
超时、断线和进程重启重试必须复用。服务端把 operationId 与软删除原子提交，并只对同 actor、资源和
operationId 与冻结 expectedRevision 的完成态返回幂等成功；删除的 `expectedRevision` 只在首次提交时执行 CAS。

`upsertGrant/removeGrant` 使用与 custody 独立的 `policyRevision` CAS。operationId 与 issuedAt 是客户端首次发送前一同冻结、
未知结果重试复用的 canonical UUID/毫秒时间；服务端以 `(actorUid, operationId)` 保存不可变请求指纹、起始 revision 和
原 resulting revision。精确重放先于当前 actor/ACL 校验识别，但只确认原副作用已提交，不再次写 grant；响应
`DocumentPolicyMutationResult` 重新投影锁内当前 `policyRevision/effectiveRole`，所以旧重试不会跨后续 remove、
regrant、ban 或 archive 复活/删除权限。同 ID 改写 payload、或新 operationId 携带陈旧 expected revision，返回
`409`；真实 no-op 保存 receipt 但不推进 revision且同样占用可靠窗口。每 actor 最多保留 1,024 条未过期 ACL
回执，窗口满返回 `429`；7 天后命令返回 `410`，过期回执可安全回收且旧未知重试不能重做。客户端收到 `effectiveRole = NONE` 必须退休整个空间干净
投影，仍为 ADMIN 以上才可重拉 grant 列表。

`transferSpaceCustody` 只允许当前 steward 发起新命令。个人 owner 必须与 steward 为同一活动 HUMAN；组织 owner 必须是活动节点，但其成员不因归属自动获权。
`expectedCustodyRevision` 是独立于节点 revision 的 CAS，`operationId` 是未知结果重试必须复用的 canonical UUID。同 ID/同 actor/同空间/同指纹精确重放返回收据中的原
`DocumentCustodyTransferResult(spaceId, ownerPrincipalType, ownerPrincipalId, stewardUid, custodyRevision)`，即使旧 steward 已失权、空间又交接或已归档也不变。返回类型不含 `myRole`，不是当前空间快照；SDK 成功后无条件清理该空间的干净权限投影，后续由 `listSpaces` 重建。
任何进入写事务的交接都先锁 `OrganizationState` 全局围栏并复查不可变收据，未命中才按 `State → User → Space → Unit` 锁序处理新命令。
复用 ID 但改写 payload，或以陈旧 custodyRevision 发起新命令，统一返回 `409`。新 operationId 的 owner/steward 与锁内当前事实完全相同时返回 `400` 且不保存收据；修正为真实交接可复用该 ID。组织节点归档时若仍持有活动空间则返回业务拒绝，必须先交接归属。

首页两类列表都限制 `limit` 为 1..50，并只返回调用者当前仍可访问的活动空间与活动文档。SQL 仅预筛候选；同一读快照还会携带批量空间/grant/组织访问快照，并由
`DocumentAuthorizationPolicy` 以 typed `DocumentCapability.READ` 做最终裁决。它们不是文档搜索，当前协议没有文档搜索 RPC。创建文档时，
创建者的最近访问记录与文档、首个修订在同一事务中写入；后续成功打开正文会更新时间，列表按该服务端时间排序。最近创建按所有可访问空间的 `createdAt` 排序，并非“我创建
的文档”。`DocumentHomeItem` 只携带有界摘要、空间、创建人和时间元数据，正文仍通过 `getDocument`
按需读取。

## 状态与错误

| status | 语义 | 客户端处理 |
|---:|---|---|
| 0 | 成功 | 解码 result |
| 400 | 参数或业务规则拒绝 | 显示可理解业务错误，不自动重试 |
| 401 | 认证失效 | 结束当前会话并回到登录流程 |
| 403 | 活动文档空间的实时 ACL 不足 | 停止被拒操作并刷新权限事实；不结束登录会话 |
| 404 | 指定空间不存在/已归档，或空间内精确文档节点不可见 | 停止精确操作并刷新活动投影；本地草稿按产品策略保留 |
| 409 | 文档节点 revision、空间 custodyRevision/policyRevision 或可靠 operationId/payload 冲突 | 保留本地意图，读取最新事实；未知结果只重放原 operationId 和原 payload |
| 410 | 有限期可靠命令已超过重试窗口 | 终止该旧命令，刷新当前权威事实，不生成同 payload 的新 operationId |
| 429 | actor 的可靠命令回执窗口已满 | 保留原意图，稍后重试；不要换 operationId 扩大窗口 |
| 500 | 服务内部错误 | 记录 correlation 信息，显示通用错误 |
| 504 | 客户端等待响应超时 | 作为超时处理；写操作需考虑服务端是否已执行 |

错误文案不是稳定协议；需要跨语言或程序化处理的场景应逐步增加稳定错误 code，而不是解析中文 message。
