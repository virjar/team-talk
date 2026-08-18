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
       ├── SUBSCRIBE / UNSUBSCRIBE
       └── PING / PONG / DISCONNECT
            └── IProto fields：fixed integers、VarInt、String、Bytes
```

协议版本当前由 `PacketCodec.PROTOCOL_VERSION` 定义。AUTH payload 以 `TK + version + tail`
序言开头；版本不匹配或未知顶层 PacketType 都是连接级错误，应断开而不是猜测解析。

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

1. 已发布 RPC 新方法只追加；如需改变顺序，必须用显式 `@RpcMethod(id)` 锁定。
2. IProto 字段按固定顺序读写。没有版本分支时，不可在中间插入字段。
3. 不兼容的帧、字段或语义变化必须增加 `PROTOCOL_VERSION` 并更新 round-trip/golden tests。
4. 新 NotifyType 必须登记 NotifyContracts；服务端发送和客户端解码不得各写一份独立类型映射。
5. 新 MessageType 必须登记 MessageBodyRegistry，并同时更新 SDK/服务端的 body policy。
6. `GENERIC` 是受控扩展入口，不应用来绕过稳定领域模型。

项目尚未正式发布，因此允许有计划的破坏性变更；“允许破坏”不等于可以不升级版本或不更新文档。
