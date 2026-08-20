# 协议与数据契约

本章定义 TeamTalk 客户端与服务端之间的实时协议。它面向 SDK 维护者、其他语言客户端实现者和
排查跨版本问题的开发者。

## 协议层次

```text
TCP stream
  └── Frame：TYPE + LENGTH + PAYLOAD
       ├── AUTH / AUTH_RESP
       ├── INVOKE / RESPONSE / STREAM_*
       ├── MESSAGE / MESSAGE_ACK
       ├── NOTIFY
       └── PING / PONG / DISCONNECT
            └── IProto fields：fixed integers、VarInt、String、Bytes
```

协议版本当前由 `PacketCodec.PROTOCOL_VERSION` 定义。AUTH payload 以 `TK + version + tail`
序言开头；版本不匹配或未知顶层 PacketType 都是连接级错误，应断开而不是猜测解析。

当前是正式发布前的新基线，`PROTOCOL_VERSION = 0`，只定义当前代码中的 wire 格式，
不承担开发数据的向后兼容。切换该基线时必须同步更新服务端、所有客户端并重建开发数据。
以后再发生不兼容 wire 变更时仍递增版本，不在同一版本号下猜测字段。

## 规范与代码的关系

- 帧和原语：`protocol/.../protocol/PacketCodec.kt`、`PacketBuffer.kt`。
- 顶层类型：`PacketType.kt`。
- RPC：`protocol/.../rpc/def/*Rpc.kt` 及 KSP 生成 Contract/Stub/Proxy。
- 模型：实现 `IProto` 的 `model/`、`body/` 和 `protocol/payload/`。
- 通知：`NotifyType.kt` 与 `NotifyContracts.kt`。

文档解释语义和兼容规则；字段顺序与方法 ID 发生争议时，以当前协议代码和 golden test 为最终事实。

## 阅读顺序

1. [Wire Format](wire-format.md)：字节、帧、顶层包与限制。
2. [RPC 与事件](rpc-and-events.md)：请求响应、IDL 和离线通知。
3. [消息与附件](messages-and-attachments.md)：消息模型、富文本和服务端文件契约。
4. [认证与错误](authentication-and-errors.md)：连接认证、token 和错误分层。
5. [RPC 参考](../10-reference/rpc-reference.md)与[事件参考](../10-reference/event-reference.md)：快速查询表。

## 兼容规则

1. 每个 RPC 方法必须显式声明唯一、正数的 `@RpcMethod(id)`；声明顺序不参与 wire 编号。
2. IProto 字段按固定顺序读写。没有版本分支时，不可在中间插入字段。
3. 不兼容的帧、字段或语义变化必须增加 `PROTOCOL_VERSION` 并更新 round-trip/golden tests。
4. 新 NotifyType 必须登记 NotifyContracts；服务端发送和客户端解码不得各写一份独立类型映射。
5. 新 MessageType 必须登记 MessageBodyRegistry，并同时更新 SDK/服务端的 body policy。
6. GENERIC 只通过下节定义的 RPC、NOTIFY、MESSAGE 三个受控入口使用，不能绕过已有强类型领域契约。

项目尚未正式发布，因此允许协调式破坏性变更和重建测试数据；“允许破坏”不等于可以不升级版本、不同步客户端或不更新文档。

## 协议演进的三个受控入口

TeamTalk 使用“稳定二进制契约 + 通用逃生通道 + 大版本收敛”的循环。普通需求仍优先追加明确的
RPC 方法、NotifyType 或 MessageType；只有当前稳定协议无法表达、又不能立即升级所有端时，才在
`ExtensionType` 分配一个不复用的编号，并选择下面至少一条通道：

| 通道 | 固定入口 | wire 约定 | 当前未登记扩展的行为 |
|---|---|---|---|
| RPC | `GenericRpcContract.SERVICE = "generic"` | `InvokePayload.serviceId="generic"`，`methodId=ExtensionType.code`，参数使用 Invoke 自带 payload | 当前没有 generic dispatcher，按未知 service 拒绝 |
| 服务端推送 | `NotifyType.GENERIC = 99` | payload 为 `GenericPayload(extensionType, opaque data)` | 严格解码通用信封后忽略，持久事件继续推进游标 |
| 普通消息 | `MessageType.GENERIC = 99` | body 为同一个 `GenericPayload` | 完整保存/转发 opaque bytes，客户端显示“不支持的扩展消息”；客户端创建未登记编号时服务端拒绝 |

一次扩展的完整演进是：真实需求出现 → 分配 `ExtensionType` → 在所选通道实现会话所有的 handler、
权限和测试 → 通过 GENERIC 跨端演进 → 在明确的大协议版本中收敛成强类型 RPC/事件/消息 → 协调
所有端与数据后再决定是否退役旧扩展编号。不得预先伪造空 dispatcher；若未来需要 handler 注册表，
注册表必须归属于客户端或服务端会话，不能使用跨登录会话共享的全局可变单例。

> **维护者警告：`ExtensionType` 刻意可以为空。空预留不等于死代码或僵尸协议。禁止仅因零引用、
> 空枚举或静态扫描结果删除 `ExtensionType`、RPC `"generic"`、`NotifyType.GENERIC(99)`、
> `MessageType.GENERIC(99)` 或 `GenericPayload`。只有一次明确的大协议版本收敛决策，连同所有端、
> 数据与 round-trip 门禁一起处理时，才可以移除这些 wire 入口。**
