# 变更指南

以下扩展遵循[版本与兼容机制](../04-protocol/versioning.md)：同一 major 内不修改已登记 wire，
新增契约递增协议 minor 并声明 `@SinceProtocol`。普通升级必须迁移并保留已有资料。

## 1. 新增 RPC

1. 在 `protocol/.../rpc/def/XxxRpc.kt` 增加方法，分配未使用的 `@RpcMethod(id)`，并声明新 minor 的 `@SinceProtocol`。
2. 使用已有 IProto 模型；必要时新增模型和 round-trip。
3. 审阅并登记 `writeProtocolBaseline` 清单变更，编译生成 Contract/Proxy/Stub。
4. 在服务端实现对应 Stub 接口；如有旧入口，保留实现并通过协商版本选择明确的业务兼容分支。
5. 在客户端 Repository 封装 Outcome 和缓存策略。
6. 更新 RpcMethodIdGoldenTest 与[RPC 参考](../10-reference/rpc-reference.md)。
7. 添加权限/错误测试和真实部署验收。

不要在客户端与服务端手写两份 serviceId/methodId 或 payload 顺序。

## 2. 新增通知

1. 在 NotifyType 分配新 code，并声明新 minor 的 `@SinceProtocol`。
2. 决定 payload 是哪个完整快照。
3. 登记 NotifyContracts。
4. 服务端在权威状态提交后通过 SyncEventService 发给正确用户。
5. EventProcessor 解码并更新 LocalCache。
6. 添加契约完备性、round-trip、重复事件和失败游标测试。
7. 更新[事件参考](../10-reference/event-reference.md)。

先回答是否需要离线补发；Presence/Typing 之类瞬时事件才可明确直推。旧客户端跳过不支持的持久事件
后不会自动重放它，必须给客户端升级后的新投影提供权威快照入口，并登记 wire 基线。

## 3. 新增消息类型

1. 分配 MessageType 新 code，并声明新 minor 的 `@SinceProtocol`。
2. 创建实现 MessageBody 的类型和 reader。
3. 登记 MessageBodyRegistry 与 MessageBodyPolicy。
4. 服务端增加内容、权限和大小校验。
5. SDK 增加发送 API，客户端增加预览与 renderer fallback。
6. 测试 wire round-trip、旧客户端历史/同步兼容分支和服务端拒绝错误 body。
7. 更新协议、客户端和功能状态。

文字能力优先扩展 RichText；可交互结构使用独立 card，不塞 Markdown 字符串协议。当前 Message body
没有独立长度，不能把未知类型当作可自动跳过；未提供完整兼容适配时必须提高最低协议版本。

## 4. 新增附件能力

1. 复用 Attachment，不添加任意 URL 字段。
2. 上传端返回 FileStore path 和需要的媒体元数据。
3. body 实现 AttachmentBody 并声明匹配 MessageType。
4. SDK canonicalize；服务端 ACK 前查询 AttachmentService/FileStore。
5. 下载器携带 access token；新附件 body 必须进入 MessageStore 的 attachment→chat 索引。
6. Desktop/Android 分别设计缓存、进度、打开/播放。
7. 覆盖不存在路径、伪造 size/type、大文件失败、跨用户引用和匿名下载。

## 5. 增加领域字段

逐层检查：

```text
产品语义
→ PostgreSQL/RocksDB schema
→ IProto 模型与协议版本
→ RPC/Notify 快照
→ LocalCache schema 和 merge
→ ViewModel/UI
→ 测试与文档
```

不能只给数据库或 UI 加字段，也不能直接扩充旧 IProto 的字段布局。新 wire 使用新模型和入口；
客户端以 SQLDelight schema 与 `.sqm` 迁移旧库，服务器提供明确的无损迁移与恢复步骤。协议 major
重整和服务端数据重建是不同决策，后者必须有明确实例与范围授权。

## 6. 新增客户端页面

1. 确定动作属于应用、栏目、对象还是当前会话。
2. Desktop 选择正确容器；Android 选择正确 destination/sheet/dialog。
3. 共享内容组件只接收数据和回调。
4. 定义 loading/empty/error/no-permission/missing-capability。
5. 添加稳定 testTag 和键盘/返回行为。
6. 运行真实客户端、操作并截图。

## 7. 修改部署配置

1. 在 DeploymentConfig 增加字段和严格校验。
2. 更新 build task、BuildConfig、验收和运行时消费者。
3. 说明默认值、secret 边界和升级兼容。
4. 添加 buildSrc 测试。
5. 更新[运行配置](../07-operations/configuration.md)。

不要只让部署脚本生成环境变量而运行时不读取。TCP 坐标变更必须保持完整链路：
`tcpAddress → TCP_HOST/TCP_PORT → TcpServer → 对应传输模式的健康探针 → 客户端与远程验收`。
按[传输配置边界](../07-operations/configuration.md#传输配置边界)分别验证服务端传输选择、当前 SDK
地址限制和部署工具生成结果；TLS 模式须验证握手与证书校验，明文模式验证配置行为，两者都覆盖非默认
端口。SDK 拒绝远程明文不等于服务端拒绝绑定该地址。

## 8. 提交前清单

- [ ] 受影响模块编译通过。
- [ ] 本地边界测试通过。
- [ ] 真实业务验收覆盖跨模块变化。
- [ ] Desktop/Android 交互按需验证。
- [ ] 没有 secret、临时截图或运行数据进入提交。
- [ ] 权威文档、reference 状态和链接已更新。
- [ ] `git diff --check` 无问题。
