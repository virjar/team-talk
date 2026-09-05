# 运维

本章面向实例管理员，描述如何配置、部署、观察和恢复 TeamTalk。产品与协议设计不在这里重复。

## 运维对象

```text
systemd: teamtalk.service
├── JVM server
│   ├── HTTP 或 HTTPS（按配置选择端口）
│   └── TCP（TCP_PORT，默认 5100；加密模式由配置决定）
├── Docker PostgreSQL
├── persistent data/
├── conf/env.sh + conf/ssl/
└── static/downloads/
```

## 最低运行基线

- 被选用的 HTTP(S)、TCP 端点与 PostgreSQL 可达；客户端与监听模式满足[传输配置边界](configuration.md#传输配置边界)。
- `data/`、`conf/` 有持久磁盘和正确权限。
- TLS 证书、数据库口令和管理凭据不进入仓库。
- `/health` 有外部探测。
- 服务端主日志、按 DIAGNOSTIC 策略有界启用的连接 trace 和客户端 fault 有容量与保留策略。
- 升级前有 PostgreSQL 与完整 data/conf 备份。
- 部署后运行 `:server:server:acceptanceTest`。

## 分册

- [运行配置](configuration.md)：部署 JSON、环境变量、端口和目录。
- [部署与升级](deployment.md)：首次部署、发布、备份和回滚。
- [Desktop 交叉打包](desktop-cross-build.md)：Conveyor 单机出三平台安装包 + 自更新站点；构建、identity stamp 与纯上传分离。
- [可观测性](observability.md)：健康、日志、版本和诊断键。
- [故障排查](troubleshooting.md)：按症状定位连接、认证、同步、文件和构建问题。

## 操作原则

1. 健康检查不替代业务验收。
2. 升级不覆盖实例状态目录。
3. 先确认实际构建 commit 和目标服务器，再解释日志。
4. 不在日志、截图和工单中传播 token、密码或私钥。
5. Lucene、缩略图等派生数据可重建；PostgreSQL、MessageStore、FileStore 元数据和文件必须成套备份。
