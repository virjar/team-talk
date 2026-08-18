# 部署指南

TeamTalk 使用 `gradle/deployment.json` 作为客户端构建、远程验收和部署自动化的单一配置入口。主仓库带有可用默认值；fork 后请先把其中的 HTTP、TCP 和 SSH 坐标改为自己的私有化环境。

## 配置部署目标

```json
{
  "serverUrl": "https://im.example.com",
  "tcpAddress": "tcp.example.com:5100",
  "deployHost": "deploy.example.com",
  "deployPort": 22,
  "deployUser": "root",
  "deployPath": "/opt/teamtalk",
  "sslPort": 443
}
```

`serverUrl`、`tcpAddress` 与 `deployHost` 可以使用不同主机。使用 `http://` 时部署脚本关闭 Ktor SSL；使用 `https://` 时启用 SSL，且 `sslPort` 必须与 URL 端口一致。

## 一键部署与验收

```bash
./gradlew deployServer
./gradlew :server:acceptanceTest
```

`deployServer` 自动区分首次部署和升级，构建服务端分发包、通过配置的 SSH 端口同步文件、保留持久化数据、重启 systemd 服务并执行本机健康检查。发布后必须运行 `acceptanceTest` 验证真实数据库、文件端点和 TCP 业务链路。

启用 HTTPS 的首次部署或证书更新需要传入 PEM 文件：

```bash
./gradlew deployServer \
  -PsslCert=/absolute/path/fullchain.pem \
  -PsslKey=/absolute/path/privkey.pem
```

## 配置与 Secret

- 非敏感坐标：`gradle/deployment.json`，应提交到当前 fork。
- 敏感数据：`gradle/deployment.secrets`，已被 Git 忽略，仅供部署读取。
- 服务器运行配置：`<deployPath>/conf/env.sh`，权限 600。

首次部署会生成数据库密码和认证密钥。升级时以服务器现有 `env.sh` 为事实源并回写本地 secrets 文件，不通过 Git 或 CI 日志传递。

## 客户端发布

```bash
./gradlew buildRelease
./gradlew uploadRelease
```

GitHub Actions `release.yml` 负责跨平台产物。手动触发时按以下顺序执行：

1. 构建 Server、Desktop 和 Android。
2. 部署服务端。
3. 运行 `:server:acceptanceTest`。
4. 验收通过后上传客户端安装包。

Tag `v*` 创建 Draft GitHub Release，不自动部署服务器。

## 服务器目录与运维

```text
<deployPath>/
├── bin/
├── conf/
├── lib/
├── static/
├── data/
└── logs/
```

运维命令中的用户、主机、SSH 端口和路径应取自 `gradle/deployment.json`：

```bash
ssh -p <deployPort> <deployUser>@<deployHost> 'systemctl status teamtalk'
ssh -p <deployPort> <deployUser>@<deployHost> 'journalctl -u teamtalk -f'
```

数据备份需同时覆盖 PostgreSQL 与 `data/` 下的 RocksDB、Lucene 和文件存储。备份 RocksDB 前应停止服务，避免获得不一致快照。

协议或持久化结构发生破坏性变更时，未正式发布阶段允许在明确目标后清理测试数据；部署脚本不会默认删除数据。
