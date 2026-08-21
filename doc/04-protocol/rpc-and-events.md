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

通用 RPC 扩展使用字符串 `serviceId="generic"`，`methodId=ExtensionType.code`。当前只由
`GenericRpcContract` 锁定入口，没有注册 dispatcher；首个真实扩展落地时才同时增加稳定编号、
会话所有的 handler、权限边界与双端测试。不要恢复已经过时的 `ServiceId.GENERIC(99)` 表达。

## 2. IDL 代码生成

RPC 定义位于 `protocol/src/commonMain/.../rpc/def/`：

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

## 3. payload 规则

- 优先让参数和返回值使用实现 `IProto` 的模型。
- 少量基本类型由生成 codec 按 IDL 参数顺序写入。
- 客户端编码和服务端解码必须字段数量、顺序、nullability 和类型完全一致。
- 每个新模型或复杂 payload 添加 round-trip 测试。
- 业务错误用 RESPONSE status 返回；协议损坏或连接错误关闭连接。

群文件使用独立 `groupFile` 服务，当前方法顺序为 list、createFolder、createFile、addVersion、
listVersions、rename、delete。GroupFileEntry 携带逻辑 revision 和当前 contentVersion；
GroupFileVersion 携带不可变 Attachment 快照。文件二进制不进入 TCP payload，仍先通过 HTTP 上传。

文档使用独立 `document` 服务，按空间、授权、目录、修订和首页索引分组：list/create/update/
archive space，list/upsert/remove grant，list/create/move/delete node，以及 get/update document 和
list/get revision；新增的最近访问与最近创建方法固定为 17/18。列表模型不携带正文，修订列表不携带
完整 Markdown；正文只在打开当前文档或指定修订时返回。update/move/delete 的 expectedRevision 是
并发契约，不是可选提示。

`Document.ancestorIds` 是服务端根据当前目录事实生成的定位路径，顺序固定为 `root → parent`，
不包含文档自身；根目录文档返回空列表。create/get/update/restore 返回的 `Document` 都必须携带
当次事实对应的路径，供客户端在懒加载目录树中逐层展开。协议限制最多 128 层；服务端解析时对跨空间、
非文件夹父节点和循环链路执行防御性校验。

`user.updateProfile` 使用 `ProfilePatch`，wire 先写四位字段 presence mask，再按
`name → avatar → sex → phone` 写入 present 值。缺席字段保持不变；nullable 的 avatar/phone
仍保留 String 自身的 null marker，因此 `Unchanged` 与 `Set(null)` 含义不同。该变更替换旧的完整
`User` 请求，属于不兼容 wire 变化，协议版本已推进到 1。

完整方法查询见[RPC 参考](../10-reference/rpc-reference.md)。

`organization.listUnits` 返回带 `directMemberCount` 的 `OrganizationUnit`。
该值只统计直接归属当前节点的成员，不包含子部门；服务端对整棵目录使用一次数据库
`GROUP BY unit_id` 聚合，不得按节点逐个查询。需要子树成员时显式调用
`organization.listMembers(unitId, recursive = true)`。

## 4. NOTIFY envelope

NOTIFY 表示服务端主动状态变化。概念字段包括：

- `eventId`：持久化事件的用户级连续游标；不同 uid 可以有相同数字，直写瞬时事件可为 0。
- `notifyType`：事件类型。
- `payload`：NotifyContracts 指定的 IProto 字节。

服务端发送持久化事件前调用契约校验，客户端从同一契约表取 reader。这样 CONTACT_ACCEPTED 之类
的事件不会出现服务端发 Contact、客户端按 ContactApply 解码的漂移。

## 5. 事件交付

```text
domain change
  → contract assertion
  → sync_events insert
  → online push
  → client decode/cache commit
  → persist lastEventId
```

认证成功后，客户端等待 LocalCache/EventProcessor 就绪，再通过独立 SYNC_REQUEST 携带持久化
`lastEventId`。服务端每次只返回同时满足条数和 wire 字节预算的一批；客户端完成投影、落盘游标后
才请求下一批，最终收到 SYNC_READY 后才进入实时 NOTIFY。语义为 at-least-once，因此：

- payload 应尽量是完整快照。
- 本地处理必须幂等。
- 只有处理完成才能推进游标。
- 解码失败要记录具体 type/eventId，并让事件下次重试。
- 非零游标必须位于当前 uid 已持久化的 `1..lastSeq`；任意越过本账号水位的值触发 `SYNC_RESET`，
  服务端不关闭身份连接也不提前激活实时推送。

收到 `SYNC_RESET` 后，客户端在一个本地事务内删除 user/contact/chat/member/message/conversation、
conversation draft outbox、bot inbox 与所有 sync cursor，同步清空内存窗口和 StateFlow，然后在
同一连接发送 `SYNC_REQUEST(0)`。独立文档草稿 store 不属于服务器事件投影，不随该事务删除。

`SYNC_RESET` 当前依赖从 0 可重放的完整事件历史，因此服务端仍不得按 TTL 跳过或物理删除
`sync_events`。仅有 RESET 不足以从被裁剪的事件尾部重建全量状态；上线前若启用保留期，还必须
提供权威快照/checkpoint bootstrap。

## 6. 事件类别

事件分为联系人、群/成员、消息、会话、在线/输入状态、已读同步和用户资料。当前类型与 payload
映射见[事件参考](../10-reference/event-reference.md)。

Presence 与 Typing 是短暂信号，不应被误认为持久业务实体。Presence 可以直推而不进离线队列；
Typing 的 Message 只是承载 chatId/senderUid 的信号快照，不落消息历史。

## 7. 历史消息分页

历史消息通过 `MessageRpc.getHistory(chatId, fromSeq, limit)` 按会话序号分页读取；响应是当前查询
结果，不伪装成新的 `MESSAGE_RECV` 事件，也不参与用户级持久事件游标。登录后的跨领域离线补偿只由
`SYNC_REQUEST/BATCH/READY/RESET` 状态机负责。
