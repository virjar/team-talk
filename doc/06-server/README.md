# 服务端

TeamTalk 服务端是 Ktor HTTP(S) 与 Netty TCP 组成的模块化单体。领域服务共享同一进程和存储连接，
但通过明确的 Repository、Store 和事件边界保持可理解性。

## 1. 请求入口

```text
TCP                              HTTP(S)
├── AUTH                         ├── /health
├── INVOKE → generated RPC Stub  ├── /api/v1/files/*
├── MESSAGE                      ├── /api/client-logs
├── SYNC_REQUEST                 ├── /api/admin/*
└── PING/PONG                    └── /downloads/* and static
```

TCP 负责实时和确定性业务；HTTP 负责文件、大 payload、管理和运维。领域规则不能因为入口不同而
出现两套实现。

## 2. 内部结构

```text
protocol/api adapters
        ↓
application coordinators → domain services → domain ports
                                             ↑
                              infrastructure adapters
                                             ↓
                         PostgreSQL / RocksDB / Lucene
```

- adapter 负责认证上下文、编解码和错误映射。
- application 组合连接生命周期、管理视图等跨域流程。
- domain service 负责权限、状态转换与事件目标，只面向领域端口。
- infra adapter 实现 Repository、消息存储、搜索、事件和在线会话端口。

## 3. 关键不变量

1. 未认证连接只能发送 AUTH/PING 等允许帧，且 payload 上限更小。
2. 所有会话业务先校验成员资格。
3. 消息 ACK 前完成消息体、附件、权限、幂等和权威落库校验。
4. 每个成员加入 Chat 时创建其 Conversation。
5. `readSeq`、消息序列和关键版本只增不减。
6. 消息与待投影 outbox 原子提交；搜索、会话和事件投影失败后可恢复。
7. 业务状态提交后才发持久化事件；Presence/Typing 只走瞬时事件。
8. Lucene 与缩略图等派生数据可重建，不取代权威存储。

## 4. 分册

- [领域服务](domain-services.md)：用户、联系人、群、消息、会话与设备规则。
- [持久化](persistence.md)：PostgreSQL、RocksDB、Lucene 和数据恢复边界。
- [文件存储](file-storage.md)：上传、分层存储、附件校验和下载。
- [搜索与管理](search-and-admin.md)：全文搜索、用户搜索和管理后台。

线程、连接和启动过程见[服务端运行时](../03-architecture/server-runtime.md)；部署和监控见
[运维](../07-operations/README.md)。
