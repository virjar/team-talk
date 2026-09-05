# RPC 与事件

## 1. RPC envelope

INVOKE payload：

```text
requestId VarInt
serviceId String
methodId VarInt
payload Bytes?
```

RESPONSE payload：

```text
requestId VarInt
status VarInt
payload Bytes?
```

`requestId` 只在当前连接内关联请求。客户端保存 `requestId → Deferred`；连接断开时必须完成或取消
全部 pending request，不能让调用永远等待。

STREAM_ITEM/STREAM_END 复用 requestId，只为未知长度结果保留 wire code 和 payload codec；当前
没有服务端分片发送、客户端聚合/取消、背压或超时状态机，因此是 **reserved / not operational**。
普通列表 RPC 仍使用单一 RESPONSE；大结果必须使用各领域已经声明的分页/游标契约，不能把这两个
可解码帧当作已落地的流式能力。

RPC 内层结果最多为 `ProtocolLimits.MAX_PAYLOAD_SIZE - 16` 字节；预留的 16 字节用于外层
requestId、status、presence 和 length。RESPONSE 与保留的 STREAM codec 在写入任何 envelope 字段前
执行同一预算检查，读取端也使用相同上限。服务端权威结果违反编码预算属于 500 级实现/契约错误，
不得被 `IllegalArgumentException` 的通用业务映射伪装成客户端 400。

所有业务 RPC 使用明确的服务和方法编号；同一 major 保留旧签名，新增签名分配新方法 ID。
`@SinceProtocol` / `@RemovedInProtocol`、生成版本窗和已登记清单共同约束可用范围，见
[版本机制](versioning.md)。零号基线已移除未实现的通用 RPC 逃生入口。

## 2. IDL 代码生成

RPC 定义位于 `protocol/protocol/src/commonMain/.../rpc/def/`：

```kotlin
@RpcService("message")
interface MessageRpc {
    @RpcMethod(1)
    suspend fun getHistory(chatId: String, fromSeq: Long, limit: Int): List<Message>
    @RpcMethod(6)
    suspend fun markRead(chatId: String, readSeq: Long)
}
```

KSP 生成：

- `Contract`：service 名称、method ID 与参数/返回编解码。
- `Proxy`：客户端类型安全调用。
- `Stub`：服务端分发和参数解码。

每个方法必须用唯一、正数的 `@RpcMethod(id)` 显式锁定 ID；processor 对缺失、重复和非法 ID
直接报编译错误，声明顺序不参与编号。修改契约时同时更新 `RpcMethodIdGoldenTest`。客户端和
服务端不得手写另一套 service/method 枚举。

`@RpcService(name)` 是全局唯一的 wire 路由身份；接口简单名也必须全局唯一，因为所有
`Contract/Stub/Proxy` 位于同一生成包。处理器在生成文件前拒绝两种冲突，并列出冲突接口的完整名称，
避免新服务被注册表的同名 key 静默覆盖。入口见
[RpcProcessor](../../protocol/rpc-processor/src/main/kotlin/com/virjar/tk/protocol/rpc/processor/RpcProcessor.kt)，
编译期错误由 [RpcProcessorCompileTest](../../protocol/rpc-processor/src/test/kotlin/com/virjar/tk/protocol/rpc/processor/RpcProcessorCompileTest.kt)
验证；这项校验不改变已有 service/method ID 或 wire 字节。

## 3. payload 规则

- 优先让参数和返回值使用实现 `IProto` 的模型。
- 少量基本类型由生成 codec 按 IDL 参数顺序写入。
- 客户端编码和服务端解码必须字段数量、顺序、nullability 和类型完全一致。
- 每个新模型或复杂 payload 添加 round-trip 测试。
- 业务错误用 RESPONSE status 返回；协议损坏或连接错误关闭连接。

`PresencePayload` 的 wire 顺序固定为
`serverEpoch → revision → uid → status → lastSeenAt`。`serverEpoch` 是小写规范 UUID，增量
`revision` 必须为正数；online 使用 `status = 1, lastSeenAt = 0`，offline 使用 `status = 0` 并携带
末台设备离线发生点。`FriendPresenceSnapshot` 依次编码 epoch、允许为 0 的 revision、完整
`friendUids` 与 `onlineFriendUids`；两个集合各最多 4,000 项，wire 必须严格升序且无重复，在线集合
必须是好友集合的子集。

Chat 成员命令遵守共享 `GroupPolicy`：`createGroup.memberUids` 和 `addMembers.uids` 的原始列表在去重前
最多 1,000 项，每个 uid 必须是 1..36 个不含空白或控制字符的规范标识；`addMembers.uids` 不接受空列表。
服务端按 uid 去重并使用确定顺序，`createGroup` 还会把 creator 恰好加入一次。群主、普通用户、机器人
和系统服务身份共同计入最多 1,000 个活跃成员；非活跃旧成员的重新加入也消耗一个名额。超限统一返回
“群成员数量已达上限”，不得在错误中回显当前人数、剩余名额或目标 uid。已是活跃成员的邀请重试仍是
幂等成功，不再次消耗邀请次数，即使群已满。

`createGroup.operationId` 是客户端在发送前生成的规范 UUID。同一建群意图在超时、断线或进程恢复后的
重试必须复用该值；群名、头像与去重排序后的初始成员共同形成规范 SHA-256 请求指纹。服务端将
`(creatorUid, operationId)` 唯一收据、Chat、Member、Conversation、容量台账和 CHAT_CREATED 事件放在
同一 PostgreSQL UoW 中提交。精确重放返回原 chatId 且不再次占用会话额度或追加事件；同一 ID 携带
不同规范 payload 返回 409，不能把未知结果重试改成新的 operationId。

`contact.accept/reject` 的参数顺序固定为 `operationId, issuedAt, token`，`chat.createInviteLink` 固定为
`operationId, issuedAt, chatId, name, maxUses, expiresAt`。两类 operationId 与首次本地提交的 `issuedAt` 都必须
在首个 RPC 前写入账号隔离的
LocalCache outbox；网络、超时、403、429、5xx、认证失效和本地解码未知结果保留原 ID，明确的其他 4xx 才
结束该代。403 可能发生在原命令已提交后的撤权重放，不能证明原结果不存在。服务端用 actor + operationId
唯一收据和规范 SHA-256 指纹保证同 payload 返回原结果、不同 payload
返回 409；好友关系/事件或邀请链接与结果收据在同一 PostgreSQL UoW 提交。好友处理收据每 actor 最近
最多 1,024 条，邀请创建收据每 actor 最多 256 条，本地两类 outbox 分别最多 128 条。可靠期限固定为 7 天，
允许客户端时钟最多领先服务端 15 分钟；过期固定返回 410。服务端只清理过期收据，窗口内满额则以 429 拒绝
新命令，不能通过淘汰旧身份把 ACK 丢失重试变成第二次副作用。
服务端平时按小时执行全局有界批次清理；若一次运行耗尽批次预算，则进入带间隔的有界追赶模式并记录
积压状态，直至不足一个批次后再恢复小时周期，确保沉默账号或突发流量留下的过期回执不会永久滞留。

`sync` service 是普通业务 RPC 的唯一同步态例外。它固定四个方法：
`beginCheckpoint(datasetId)`、`listCheckpointContacts(request)`、
`listCheckpointChats(request)` 和 `listCheckpointConversations(request)`。header 包含当前 User、
`checkpointId` 与 `baseEventId`；后续页只能由同一认证连接持同一 checkpointId 读取。
联系人和 Chat 页各至多 256 条，Conversation 复用 16 条有界页。各页是分开的 keyset
读取，不共享跨 RPC 的 MVCC snapshot；客户端收齐所有 section 后一次安装，再由
`baseEventId` 之后的 tail 收敛页间并发变化。

邀请链接精确重放只绕过重复创建和链接容量，不能绕过授权：每次都重新校验活动群、当前用户写权威与管理员
身份，通过后才读取并返回收据中的原 token。链接撤销不删除仍在保留窗口内的命令收据；降权或移出群的旧
创建者不能通过旧 operationId 取回 token。

群文件使用独立 `groupFile` 服务，当前方法顺序为 list、createFolder、createFile、addVersion、
listVersions、rename、delete。GroupFileEntry 携带逻辑 revision 和当前 contentVersion；
GroupFileVersion 携带不可变 Attachment 快照。文件二进制不进入 TCP payload，仍先通过 HTTP 上传。
createFolder/createFile 由客户端同时提供稳定 `entryId` 与 `commandId`，addVersion、rename 和
delete 提供稳定 `commandId`；这些值都是规范 UUID，未知结果重试必须复用原值。服务端把
认证 uid、命令种类、规范化名称/父级、Attachment 和 expectedRevision 等不可变 payload 做指纹并
持久化收据：五类变更的相同命令精确重放不产生第二条事实，不同 payload 复用同一命令则
失败关闭。当前 rename/delete 都返回 Unit；精确收据是 ACK，不重复修改条目、容量或审计。
客户端在首个 RPC 前把五类命令的完整不可变载荷写入有界 outbox；
`PENDING / ACKNOWLEDGED / REJECTED` 只描述该本地提交与后台恢复状态，不是 RPC 返回类型。

文档使用独立 `document` 服务，按空间、授权、文档树、修订和首页索引分组：list/create/update/
archive/transfer custody space，list/upsert/remove grant，list/create/move/delete node，以及 get/update document 和
list/get revision。文档统一通过 method 9 `createDocument` 创建，`parentId` 可为空间根或同空间活动文档。
指定 `spaceId` 的精确访问中，空间不存在或已归档稳定返回 404，活动空间存在但当前 uid 的
实时 ACL 不足则稳定返回 403；通过空间 ACL 后，缺失、已删除或属于其他空间的精确文档/父节点也统一
返回 404，不允许用状态或错误文案探测跨空间节点。标题、游标、版本号等普通请求参数非法仍返回 400，
聚合修订冲突返回 409。只有通过空间 ACL 后，授权变更请求才会公开其目标用户或组织节点的校验结果。
最近访问与最近创建方法固定为 16/17。列表模型不携带正文，修订列表不携带
完整 Markdown；`listRevisions` 使用 `beforeRevision` 独占游标和最大 100 条的有界页，返回
`DocumentRevisionPage(items, nextBeforeRevision)`，游标 0 同时表示首屏请求和历史耗尽。正文只在打开当前文档或指定修订时返回。
method 11 `updateDocument(spaceId, documentId, content, expectedRevision)` 是 content-only 写入，不能改名；
标题与 parent 只由 method 12 `moveNode` 修改。update/move/delete 的 expectedRevision 是
节点聚合并发契约，不是可选提示；服务端先做版本比较再判断 no-op。纯父级移动推进聚合 revision 但不
追加内容修订，因此历史分页中的修订号允许不连续。

method 12 的完整参数为
`moveNode(spaceId, nodeId, parentId, name, expectedRevision, operationId, issuedAt)`，返回
`DocumentMoveCommandResult(operationId, result?)`。operationId 与 issuedAt 在首个请求前冻结，未知结果
重试必须原样复用完整 payload。首次提交的 `result` 是事务内解析的 `DocumentMoveResult`；精确重放只
返回相同 operationId 并令 `result = null`，确认已提交而不重放可能过时的位置投影。同 ID 异 payload
返回 409，过期 identity 返回 410，每 actor 的 7 天活动窗口达到 1,024 条时返回 429；no-op 也保存一条
有限收据，但不推进 revision。

method 6/7 的 ACL 写入分别固定为
`upsertGrant(spaceId, principalType, principalId, role, includeDescendants, expectedPolicyRevision, operationId, issuedAt)` 和
`removeGrant(spaceId, principalType, principalId, expectedPolicyRevision, operationId, issuedAt)`，均返回
`DocumentPolicyMutationResult(spaceId, policyRevision, effectiveRole)`。operationId 是 actor 作用域的 canonical
UUID；issuedAt 与 operationId 在首次发送前一同冻结，未知结果重试必须复用首次完整 payload。服务端固定锁内先匹配不可变 receipt，未命中才校验活动
actor/目标、typed `MANAGE_POLICY` 与 policy CAS。真实 grant 变化只推进一次 policyRevision，no-op 也保存
receipt 但不推进。精确重放不再执行原 upsert/remove，而是返回当前锁内 role/revision；同 ID 异指纹和陈旧
新命令均为 409。每 actor 最多保留 1,024 条仍在 7 天窗口内的回执（no-op 同样计数）；窗口满返回 429，
过期命令返回 410。过期回执只在持有 actor 围栏的新命令中回收，因此回收后的旧重试也不会变成新写入。
`effectiveRole = NONE` 是整空间干净投影 tombstone，不是可缓存的角色值。

`createSpace` 与 `createDocument` 的首个参数分别是客户端生成的稳定 `spaceId` / `documentId`。发起方必须在
本地创建意图或草稿时生成并持久保存该 ID，超时、断线和进程重启后的同一意图继续复用；服务端保存不可变
创建指纹并确认已存在的同一资源，同一 ID 携带不同创建内容则拒绝。该约束用于覆盖“事务已提交但响应丢失”
窗口，不能改回服务端收到请求后才随机生成 ID。

method 9 固定返回 `DocumentCreateResult(documentId, document?)`。首次成功创建携带完整 `document`；任何已提交
命令的精确重放可以只返回相同 `documentId` 和 `document = null`，包括创建者后来失权、空间归档或文档软删除。
null 只确认原创建事务已经提交，不承诺当前资源仍活动或可见。只有 actor、space、documentId 与规范初始 payload
指纹全部匹配的收据才能在实时 ACL 前确认；跨 actor、改写 payload 或 ID 碰撞仍先经过当前 EDIT 权限门禁，
有权限的碰撞返回 409，无权限调用不能借创建重放探测文档身份。

`createSpace` 固定返回 `DocumentSpaceCreateResult(spaceId, space?)`。首次创建以及精确重放时，只要空间仍活动且
原创建者仍是当前 steward，`space` 就是该时刻的权威空间投影：它可以包含创建后已经变化的组织 owner 和
custodyRevision，而不是冻结的首创快照。若空间后来归档，或原创建者已经不再是 steward，精确重放仍以
相同 `spaceId` 确认原创建事务完成，但 `space = null`；调用方必须把它视为成功完成命令，不能据此补造
`myRole = Owner`、继续使用旧 ACL 或把同一创建意图无限重试。null 本身不判定调用者是否仍有显式 grant；
当前访问角色只能由后续 `listSpaces` 等实时授权读取重新建立。

`archiveSpace` 与 `deleteNode` 另携带客户端发送前持久化的 `operationId`。服务端把它和空间/节点的软删除
状态在同一 PostgreSQL 事务提交；完成态只有在 actor、资源、operationId 与删除的冻结 expectedRevision 全部相同时才按幂等成功返回。
删除的 `expectedRevision`、叶节点和 ACL 只在首次提交时校验；未知结果重试不得生成新 operationId，明确
`400/409` 拒绝后才可结束该本地命令世代并保留正文草稿。

`DocumentSpace.createdBy` 是不可变创建来源；末尾的 `ownerPrincipalType` / `ownerPrincipalId`、`stewardUid`
和 `custodyRevision` 表示可转移归属、唯一人类责任人与独立交接版本。组织 owner 不是成员 ACL，组织成员仍需要 grant。
method 18 `transferSpaceCustody` 的参数顺序固定为
`spaceId, ownerPrincipalType, ownerPrincipalId, stewardUid, expectedCustodyRevision, operationId`，返回
`DocumentCustodyTransferResult(spaceId, ownerPrincipalType, ownerPrincipalId, stewardUid, custodyRevision)`。该结果只是原命令的
不可变回执，不含 `myRole`，也不代表空间当前未再发生交接或归档。

`operationId` 是客户端在首次发送前保存的 canonical UUID；未知结果重试必须复用原值与完整原 payload。任何进入写事务的交接先锁
`OrganizationState` 全局围栏，在围栏内先复查不可变收据；未命中才按 `State → User → Space → Unit` 锁序执行 custody CAS、归属容量校验和收据追加。精确重放返回收据中的原 `DocumentCustodyTransferResult`，
即使原 steward 已失权、空间又发生了交接或已归档也不改写；这只是对已提交命令的回执，不为新命令绕过当前 `TRANSFER_CUSTODY`
能力。复用 ID 但改写 payload 或以陈旧 custodyRevision 发起新命令返回 409。目标 owner 是用户时必须与 steward 相同；
目标 owner 是组织时组织节点必须活动，steward 始终必须是活动普通用户。收据未命中的新命令若 owner/steward 与锁内实时事实完全相同，返回 400 且不追加收据；同 operationId 修正为真实交接后仍可使用。已经提交的相同 operationId 精确重放仍优先返回原收据，不受此 no-op 规则影响。

`Document.ancestorIds` 是服务端根据当前文档父子事实生成的定位路径，顺序固定为 `root → parent`，
不包含文档自身；根级文档返回空列表。create/get/update/restore 返回的 `Document` 都必须携带
当次事实对应的路径，供客户端在懒加载文档树中逐层展开。协议限制最多 128 个祖先；服务端解析时对跨空间、
不存在或已删除的父文档、自身/后代移动、循环链路和超深子树执行防御性校验。
`moveNode` 首次提交在 `DocumentMoveCommandResult.result` 中返回
`DocumentMoveResult(node, ancestorIds)`；客户端只能采用服务端在提交事务中解析出的 `ancestorIds`，
不得用确认对话框打开时捕获的旧目录路径拼接移动结果。精确重放的空 result 要通过新的授权读取收敛
当前路径，不能从原请求补造。
若目标父文档在移动期间消失，服务端返回层级冲突 409 并保留仍存在的移动节点；只有移动节点本体不存在才返回 404。

`conversation.listPage` 使用强类型 `ConversationPageRequest`，因此请求解码在构造游标字符串前
就限制最多 128 个 base64url 字节。`ConversationPage` 最多 16 条，只有完整 16 条的非末页才能
携带 `nextCursor`，页内编码预算为 8 MiB。游标按不可变 `chatId DESC` 排序，是 exclusive cursor；
客户端不解析其内容，只原样回传。

`user.updateProfile` 使用 `ProfilePatch`，wire 先写四位字段 presence mask，再按
`name → avatar → sex → phone` 写入 present 值。缺席字段保持不变；nullable 的 avatar/phone
仍保留自身的 presence marker，因此 `Unchanged` 与 `Set(null)` 含义不同。非空 avatar 是完整
FileStore `Attachment(path, name, contentType, size)`，只接受 canonical 相对路径、JPEG/PNG/WebP
和至多 8 MiB 的对象；任意 URL 或只传 path 都不是合法头像。当前契约只保留 `ProfilePatch`，不承载
整对象覆盖语义。完整 User 携带正数单调 `revision`；资料或 status 变化递增，凭据专属变化不递增。
`Conversation` 的单聊投影必须携带对端 `peerUid` 与正数 `peerRevision`，可携带同一完整头像描述符；群聊和
删除哨兵的 `peerUid/peerRevision/chatAvatar` 必须为空。

完整方法查询见[RPC 参考](../10-reference/rpc-reference.md)。

`organization.listUnitPage(OrganizationUnitPageRequest)` 按稳定 `unitId ASC` 返回最多 256 个带
`directMemberCount` 的节点；`organization.listMemberPage(OrganizationMemberPageRequest)` 按稳定
`(unitId, uid) ASC` 返回最多 256 条直属或子树关系。每页编码预算分别为 512 KiB 和 1 MiB，远小于
16 MiB RPC envelope。cursor 是服务端拥有的 canonical base64url 值，绑定 exclusive key、首屏
organization revision，成员 cursor 还绑定根节点和 recursive 语义。
游标是可规范校验但不是认证凭据；服务端在每个数据页产生前都复验完整活动树为单根、无环、不超过 10,000 节点且
根到叶最多 64 层。递归成员 CTE 的循环/超深状态先门禁 relation page，损坏树不会返回部分成员。

每个数据库页在 repeatable-read 快照中先读取 revision 再读取 keyset；若后续 cursor 的 revision 已
过期，响应必须是 `snapshotChanged=true` 的空终止页，客户端丢弃整轮并从空 cursor 重试，绝不能拼接
两个 revision。`directMemberCount` 只统计当前节点直属成员，不包含子部门；递归成员必须显式使用
`recursive=true`，其结果不能覆盖任何单节点直属成员缓存。

这两个组织读取方法属于 TCP 长连接上的强类型二进制 RPC。`/api/admin/organization/**` 只承载管理
写控制面，不是普通终端的目录读取链路。一次组织管理写提交后可以广播
`ORGANIZATION_CHANGED(61, revision)`；它只携带 revision，不携带行，终端必须继续用上述分页 RPC
取得权威快照。

## 4. NOTIFY envelope

NOTIFY 表示服务端主动状态变化。概念字段包括：

- `eventId`：持久化事件的用户级连续游标；不同 uid 可以有相同数字，直写瞬时事件可为 0。
- `notifyType`：事件类型。
- `payload`：NotifyContracts 指定的 IProto 字节。

服务端发送持久化事件前调用契约校验，客户端从同一契约表取 reader。这样 CONTACT_ACCEPTED 之类
的事件不会出现服务端发 Contact、客户端按 ContactApply 解码的漂移。

`ORGANIZATION_CHANGED` 固定使用 `OrganizationChangedPayload(revision)`，其中 revision 为正数。
服务端只向已经完成 `SYNC_READY` 的在线连接发送 `eventId = 0` 提示；它不进入 `sync_events`，也不
推进或覆盖正数事件游标。客户端必须先持久提升组织投影的 `requiredRevision`，再把刷新提示发布给
页面；通知遗漏、写失败或进程退出均由下一次 `AUTHENTICATED` 边沿的全量 revision-fenced RPC 对账
收敛。因此该通知不是 durable fanout，也不能替代组织 PostgreSQL 事实或分页响应。

`PRESENCE` 与 `TYPING` 同样固定使用 `eventId = 0`。PRESENCE 在登录、重连等既定刷新点以版本化好友
快照重建基线，不为单条丢失增量即时补拉；TYPING 的发送方通过 MESSAGE 通道发送
`MessageType.TYPING` 空正文信封，服务端按当前会话成员权威校验后，以 `NotifyType.TYPING` 直发其他
成员。该路径不返回 MESSAGE_ACK，不写消息、会话或 `sync_events`，过载、断线和离线期间都允许丢弃。

## 5. 事件交付

```text
domain change
  → contract assertion
  → sync_events insert
  → online push
  → client decode/cache commit
  → persist lastEventId
```

认证成功后，客户端等待与 AUTH `datasetId` 一致的 LocalCache/EventProcessor 就绪，再通过独立
SYNC_REQUEST 携带持久化的 `datasetId + lastEventId`。服务端每次只返回同时满足条数和 wire 字节预算
的一批；游标必须位于当前账号的 `compactedThrough..lastSeq`。客户端完成投影、原子落盘 dataset 与游标后
才请求下一批，最终收到 SYNC_READY 后才进入实时 NOTIFY。语义为 at-least-once，因此：

- payload 应尽量是完整快照。
- 本地处理必须幂等。
- 只有处理完成才能推进游标。
- 解码失败要记录具体 type/eventId，并让事件下次重试。
- datasetId 必须与 AUTH 结果一致，且游标必须位于当前 uid 已保留的 `compactedThrough..lastSeq`；任一不匹配
  都触发携带权威 datasetId 的 `SYNC_RESET`，
  服务端不关闭身份连接也不提前激活实时推送。

收到 `SYNC_RESET` 后，客户端在 `SYNCHRONIZING` 内使用上述 `SyncRpc` 收齐 checkpoint。
只有本地 sync state 仍精确匹配加载前的 expected dataset + cursor 时，才在一个 SQLite
事务中替换 current user/contact/chat/conversation 等紧凑服务器投影、清理不再受 checkpoint
覆盖的 member/已确认 message 行，并把 cursor 设为 `baseEventId`。所有 section 安装完成前
不发布任何部分页；事务成功后才从 `baseEventId` 请求 tail。outgoing、Bot inbox 及其
retained floor、conversation draft/read 以及待确认命令 outbox 是本地可靠事实，必须保留并重叠加到
权威结果；同一 dataset 内的独立文档草稿 store 也不属于服务器事件投影。认证返回不同
`datasetId` 时，客户端改用新的 deployment + dataset + uid 文档命名空间，禁止恢复或重放旧
dataset 的草稿和可靠文档 operation。后续权威会话全量快照若明确不含
某 chat，仍可清理对应孤儿会话 outbox。

服务端默认保留 30 天 `sync_events`。清理只删除已完成实时派发尝试且已过期的
连续前缀，并在同一事务内推进 `compactedThrough`。正在 replay 或 checkpoint 的连接以
connection lease 保护其最低所需游标，replay/checkpoint anchor 与 compactor 共用 per-user delivery
gate，因此清理不能跨过已准入的读取。这项保留只承诺“checkpoint 当前投影 + tail”可恢复，
不把已压缩的历史业务回调伪装成永久离线队列。

## 6. 事件类别

事件分为联系人、群/成员、消息、会话、在线/输入状态、已读同步、用户资料和组织目录失效提示。当前类型与 payload
映射见[事件参考](../10-reference/event-reference.md)。

Presence 与 Typing 是短暂信号，不应被误认为持久业务实体。Presence 增量由
`serverEpoch + revision` 排序，登录或重连后的权威基线由 `contact.getPresenceSnapshot` 提供；
Typing 的 Message 只承载 `chatId + senderUid` 信号快照，不落消息历史、outbox 或持久游标。

## 7. 历史消息分页

历史消息通过 `MessageRpc.getHistory(chatId, fromSeq, limit)` 按会话序号分页读取；响应是当前查询
结果，不伪装成新的 `MESSAGE_RECV` 事件，也不参与用户级持久事件游标。登录后的跨领域离线补偿只由
`SYNC_REQUEST/BATCH/READY/RESET` 状态机负责。
