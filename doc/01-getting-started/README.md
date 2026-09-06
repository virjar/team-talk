# 上手与部署

本章帮助你完成三个结果：理解 TeamTalk 的运行组成、启动一个可调试的客户端，以及把独立 clone 的
项目部署到自己的服务器。内部协议和存储原理不在这里展开。

## 运行组成

最小可用环境包含：

```text
Android 或 Desktop 客户端
        │
        ├── TCP（默认 5100）：认证、RPC、消息、通知、心跳
        └── HTTP(S)：文件、静态页面、日志、健康检查
                         │
                  TeamTalk server
                   ├── PostgreSQL
                   ├── RocksDB
                   └── Lucene
```

客户端、部署任务和真实业务验收共用选中部署配置函数产生的 `DeploymentConfig`。主仓库的
`buildSrc/deployment/Deployment.kt` 保持公版坐标；私有发行在独立 clone 使用被 Git 忽略的
`buildSrc/deployment-local/Deployment.kt` 完整替换默认配置。字段和选择规则见[运行配置](../07-operations/configuration.md)。
远程部署可以选择 HTTPS + WebPKI TLS/TCP，也可以用 IP、HTTP 站点和客户端固定信任的自签 TCP 证书。
HTTP scheme 与 TCP TLS 独立配置，具体按[传输配置边界](../07-operations/configuration.md#传输配置边界)核对。

开发者预览版的体验范围、已知限制与最小分发检查见[小范围内测指南](developer-preview.md)。

## 选择你的路径

### 只体验或开发客户端

使用仓库默认部署：

```bash
./gradlew :client:desktop:run
```

Desktop 会同时开启仅绑定本机的语义测试服务。详细说明见[开发环境](development.md)。

### 调试服务端内部实现

启动本地 PostgreSQL 和服务端：

```bash
docker compose up -d
./gradlew :server:server:run
```

如果希望另外构建连接本地服务端的客户端，使用独立 clone 的 `buildSrc/deployment-local/Deployment.kt`
设置本地 HTTP/TCP 坐标并重新构建。主仓库默认配置仍保留公版地址。

### 部署自己的实例

阅读[私有化部署](private-deployment.md)。部署完成后必须运行：

```bash
./gradlew :server:server:acceptanceTest
```

健康检查只能证明组件可用，真实验收负责验证注册、认证、好友、私聊、群聊、文件等业务链路。

## 下一步

- 想理解系统为什么这样设计：阅读[系统架构](../03-architecture/README.md)。
- 想增加业务功能：阅读[开发与扩展](../08-development/README.md)。
- 遇到启动或连接问题：阅读[故障排查](../07-operations/troubleshooting.md)。
