# 私有化部署

TeamTalk 的部署目标是：一个 fork 可以通过一份公开坐标配置和一组私密凭据，构建自己的客户端、
部署自己的服务端并对同一目标运行验收。

## 1. 准备服务器

推荐基线：

- Linux x86_64 或 arm64
- JDK 17
- Docker 与 Docker Compose
- 可用域名和 TLS 证书
- 对客户端开放 HTTPS 443 与 TCP 5100
- 具备 SSH/rsync 权限的部署账号

单组织万级用户是当前架构边界。容量规划需结合在线连接数、消息速率、附件大小和日志采样，不应
只按注册用户数估算。

## 2. 配置部署坐标

修改 `gradle/deployment.json`：

- `serverUrl`：客户端访问的 HTTPS 根地址，不带 `/api`。
- `tcpAddress`：客户端 TCP 地址，格式为 `host:port`。
- `deployHost` / `deployPort` / `deployUser`：Gradle 部署任务使用的 SSH 目标。
- `deployPath`：远端安装根目录，默认 `/opt/teamtalk`。
- `sslPort`：服务端 HTTPS 监听端口。

HTTP 域名、TCP 地址和 SSH 主机可以不同。附件消息只存服务端相对路径；客户端用 `serverUrl`
解析为下载地址，因此构建客户端前必须确认它指向自己的实例。

## 3. 提供 Secret

敏感值放在不提交的 `gradle/deployment.secrets` 或 CI Secret 中。至少包括：

- SSH 私钥或等价认证材料
- PostgreSQL 口令
- 管理后台凭据
- TLS keystore/证书所需密码

不要把真实 secret 写入 Markdown、`deployment.json`、Gradle 命令历史或构建产物名称。

## 4. 首次部署

```bash
./gradlew deployServer
```

任务会构建服务端分发包、创建远端目录、上传静态和可执行文件并配置运行服务。远端典型结构：

```text
/opt/teamtalk/
├── bin/                 启动脚本和服务端分发
├── conf/                env.sh、TLS 等私密配置
├── data/                RocksDB、Lucene、文件和客户端日志
├── static/              首页与客户端安装包
└── docker-compose.yml   PostgreSQL
```

`data/`、`conf/env.sh`、`conf/ssl/` 和运行日志是实例状态。升级同步不得用 `--delete` 删除这些路径。

## 5. 验收

先检查健康状态，再跑业务验收：

```bash
curl https://im.example.com/health
./gradlew :server:acceptanceTest
```

验收目标同样读取 `deployment.json`，因此不会出现“部署到 A、测试 B”的 profile 漂移。失败时不要
只看 HTTP 200；应结合验收报告、服务端 trace、客户端 fault 和目标实例数据判断。

## 6. 发布客户端

```bash
./gradlew buildRelease
./gradlew uploadRelease
```

客户端会内嵌构建时的服务坐标、git commit 和 build time。fork 发布前应在所有目标平台构建，
不能把 macOS 本机构建误当作 Windows/Linux 交叉编译。CI 发布矩阵见 `.github/workflows/release.yml`。

## 7. 升级与回滚

当前项目未承诺数据结构向后兼容。升级前必须：

1. 阅读目标提交的数据库、协议和缓存变更。
2. 备份 PostgreSQL、`data/` 和 `conf/`。
3. 在测试实例部署并执行真实业务验收。
4. 再升级正式实例并观察健康、认证、消息、附件与日志。

如果变更明确允许清空测试数据，应清理测试实例后重新验收；生产数据不能依赖这一策略。详细运行
步骤见[部署与升级](../07-operations/deployment.md)。
