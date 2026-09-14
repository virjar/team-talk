# 运维

本章面向实例管理员，描述如何配置、部署、观察和恢复 TeamTalk。产品与协议设计不在这里重复。

## 运维对象

```text
systemd: teamtalk.service
├── JVM server
│   ├── HTTP 或 HTTPS（按配置选择端口）
│   └── TCP（TCP_PORT，默认 5100；加密模式由配置决定）
├── Docker PostgreSQL
├── persistent data/（含 release-store/ 客户端制品仓）
├── conf/env.sh + conf/ssl/
└── static/downloads/
```

## 最低运行基线

- 被选用的 HTTP(S)、TCP 端点与 PostgreSQL 可达；客户端与监听模式满足[传输配置边界](configuration.md#传输配置边界)。
- `data/`、`conf/` 有持久磁盘和正确权限。
- TLS 私钥、数据库口令和管理凭据不进入仓库；客户端固定信任只携带公共证书。
- `/health` 有外部探测。
- 服务端主日志、按 DIAGNOSTIC 策略有界启用的连接 trace 和客户端 fault 有容量与保留策略。
- 升级前有 PostgreSQL 与完整 data/conf 备份。
- 部署后核对健康与实际构建身份，再按[验收范围](../09-testing/deployment-acceptance.md)验证业务。
  自动注册账号的 `acceptanceTest` 使用独立测试实例；真人内测节点只在明确授权的账号与范围内验证。

## 分册

- [运行配置](configuration.md)：部署 Kotlin 源码、默认/local 选择、机器快照、环境变量、端口和目录。
- [部署与升级](deployment.md)：首次部署、发布、备份和回滚。
- [统一发行流程](releasing.md)：根版本、人工说明、密封产物、本机与 GitHub CI 共用的 Gradle 发布入口。
- [Desktop 交叉打包与签名](desktop-cross-build.md)：JBR、bootstrap、四目标安装包、Android 签名与 Desktop 系统信任边界。
- [客户端发布与更新体系](client-releases.md)：注册中心、通道、应用内更新与旧安装器迁移。
- [可观测性](observability.md)：健康、日志、版本和诊断键。
- [故障排查](troubleshooting.md)：按症状定位连接、认证、同步、文件和构建问题。

## 操作原则

1. 健康检查不替代业务验收。
2. 升级不覆盖实例状态目录。
3. 先确认实际构建 commit 和目标服务器，再解释日志。
4. 不在日志、截图和工单中传播 token、密码或私钥。
5. Lucene、缩略图等派生数据可重建；PostgreSQL、MessageStore、FileStore 元数据和文件必须成套备份。
