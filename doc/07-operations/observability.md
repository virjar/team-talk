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

`GET /health` 汇总 PostgreSQL、MessageStore/RocksDB、Lucene、FileStore 和 TCP 监听。所有关键组件
UP 才返回 200。外部探针应检查 HTTP status 和结构化 component 结果。

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
保存 pending，CrashDumper 使用原子文件保存未处理崩溃，下一次启动重传。

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
