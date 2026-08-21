# 可观测性

## 1. 观察层次

| 层次 | 信号 | 回答的问题 |
|---|---|---|
| 构建 | commit、build time、协议版本 | 实际运行的是什么 |
| 组件 | `/health` | 存储、索引、文件和 TCP 是否可用 |
| 服务端业务 | 主日志、采样连接 trace | 请求在哪一步失败 |
| 客户端 | trace、fault、crash dump | 哪个设备/状态/事件出错 |
| 验收 | acceptance report | 用户旅程是否真正闭环 |

单个绿色信号不能替代其他层次。例如 `/health` 成功不能证明好友事件 payload 正确。

## 2. 健康检查

`GET /health` 汇总 PostgreSQL、MessageStore/RocksDB、Lucene、FileStore、TCP 监听以及
`message-projection`、`managed-chat-projection` 两个 durable projection readiness。所有关键组件
UP 才返回 200。外部探针应检查 HTTP status 和结构化 component 结果。

`managed-chat-projection=DOWN` 表示至少一个组织受管群的 desired revision 尚未应用；detail 优先给出
失败 unit/revision/attempt，否则给出 pending 数量。此时相关聊天权限主动拒绝，不能把旧成员投影视为
可降级数据。运行期会按持久退避每 5 秒扫描已到期任务；只有 pending 清零才恢复 readiness。若启动时
完整 drain 仍未收敛，服务会在开放 TCP 前失败，避免带着旧权限投影对外提供服务。

健康检查不执行注册或发送消息，不能作为发布验收。

## 3. 服务端日志

主 logback 日志写控制台与滚动文件：

- `teamtalk.log`：启动、HTTP、领域错误和系统状态。
- `traces/trace.log`：被采样 TCP 连接的有序业务轨迹。

Recorder 在认证前缓存有限条目，认证后按 uid/deviceId 升级为采样 writer。全局采样数受限，未采样
连接的日志构造应近似零开销。常用标签包括 AUTH、SYNC、RPC、SEND、SENDACK、TYPING、
KICK、IDLE 和 CLOSE。

## 4. 客户端日志

客户端把日志分为：

- `trace`：连接、认证、RPC、消息和正常状态流。
- `fault`：异常、不可恢复状态、事件解码和存储失败。

ClientSession 持有独立环形缓冲。fault 经短 debounce 触发上传；开发构建定期上传 trace。上传失败
保存 pending。CrashDumper 的 pending 空间固定按 `canonical TCP+HTTP deployment fingerprint + uid`
隔离；登录 A 产生的崩溃不能由随后登录的 B 读取或上传，只有同一部署、同一账号重新登录后才能处理。认证前无法归属的崩溃写入
独立 unowned 空间，任何后来账号都不会自动认领。禁用日志上传的无头会话不安装或覆盖进程全局
AppLog/crash owner，因此同进程多个 ImBot 或图形客户端不会互相接管日志身份。
认证前的 ImClient/AuthSync/Transport/PacketRouter 与禁用上传的 headless 连接树只写平台诊断，不读取
进程 AppLog owner；认证后的 RpcClient/EventProcessor 才绑定本会话固定 logger。当前安全边界因此会
牺牲一部分底层连接日志上传，完整的可比较 owner-token 连接 logger 绑定留作独立演进，不能退回动态
全局 logger。

进程全局 AppLog 只有一个 `@Volatile` owner 快照；trace/fault buffer、fault handler 与 crash owner
以一次原子替换共同轮换，释放使用 identity CAS。会话异步任务持有固定 owner 追加，因此 A 的迟到
失败只能进入 A 的 buffer/pending，不能在 B 登录后被路由到 B。HttpLogUploader 固定 server、uid 与
认证 identity epoch，每次请求动态读取同一 epoch 的 token；quiesce 先关闭凭据/发布 gate，再主动
断开所有阻塞 HTTP 连接，之后既不读新凭据，也不消费迟到响应。stop 返回后旧 worker 的迟到成功或
失败都直接丢弃，不再写 crash namespace、buffer 或触发 uploader。

CrashDumper 与 Desktop auth、device-id、SQLite 共用 JVM 私有存储基元。POSIX owner namespace 目录以
0700 创建；由 JVM 管理的 auth、device-id、crash pending 与 SQLite 主 DB 以 0600 创建并校验单一硬链接。
SQLite 自建 journal/WAL/SHM sidecar 的安全边界是账号 0700 namespace，不依赖其单文件 mode 恒为 0600，
并验证 sidecar 不会逃出该 namespace。既存过宽路径、符号链接、受保护文件硬链接数不为 1、owner 不匹配
或 macOS 扩展 ACL 授予额外访问均 fail closed。Windows 使用当前 owner 的精确 ACL，任何额外主体的访问
ACE 都不作为宽权限 fallback 接受。pending 通过同目录临时文件、文件 fsync、原子替换与平台可用的目录
fsync 发布，上传完成只删除内容仍精确匹配的 owner 文件。

上传端点只接受当前会话的 Bearer access token 和有界 GZIP；uid/deviceId 一律取 token 中的权威身份，
不接受请求头伪造目录。服务端把压缩体限制为 1 MiB、解压后限制为 4 MiB，再按
`client-logs/{uid}/{deviceId}/{date}.log` 保存并设置保留期。

## 5. 诊断键

日志需要包含足够关联键，但不能包含 secret：

- uid、deviceId（必要时脱敏）。
- chatId、clientMsgId、serverSeq。
- requestId、serviceId、methodId。
- eventId、NotifyType。
- attachment path、size、contentType。
- commit、build time、protocol version。

禁止记录密码、access/refresh token、管理口令、完整私钥和敏感消息正文。

## 6. 日志保留与容量

默认主日志按日期和大小滚动，trace 与客户端日志保留更短。正式部署应按磁盘、隐私和故障发现时间
调整，监控：

- logs/client-logs 总量。
- 临时上传目录增长。
- fault 上传失败与 pending 数。
- trace 采样 writer 数。
- Lucene 和 RocksDB 写错误。

## 7. 排查顺序

1. 记录用户、设备、时间、构建和目标实例。
2. 用 health 排除组件整体故障。
3. 用 clientMsgId/requestId/eventId 串联客户端与服务端日志。
4. 检查权威存储是否写入，再看事件/索引/客户端缓存。
5. 用真实验收或最小操作复现。

按症状的具体步骤见[故障排查](troubleshooting.md)。
