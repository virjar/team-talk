# 单一部署配置

TeamTalk 不再维护环境 Profile、Gradle 任务矩阵、Android flavor 或客户端服务器切换 UI。客户端构建、远程业务验收和自动部署统一读取仓库内的 `gradle/deployment.json`。

这并不意味着服务地址被写死。TeamTalk 是开源项目，fork 后可以直接修改这一份配置，将客户端和部署任务指向自己的私有服务器。仓库默认值指向项目维护者的公开实例，仅用于开箱即用和主仓库持续验收。

## 设计边界

- 只有一个配置入口，避免本地运行、安装包、验收任务和部署任务连接不同服务器。
- 配置随 fork 进入版本控制，私有发行版能够稳定复现其目标地址。
- HTTP、TCP 与 SSH 部署主机可以不同；不假设所有端点位于同一台机器。
- `serverUrl` 支持 `http` 和 `https`，其协议决定服务端是否启用 SSL。
- 密码、JWT 密钥和证书口令不进入 Git。

## 配置格式

`gradle/deployment.json` 保存非敏感坐标：

```json
{
  "serverUrl": "https://im.virjar.com",
  "tcpAddress": "im.virjar.com:5100",
  "deployHost": "im.virjar.com",
  "deployPort": 22,
  "deployUser": "root",
  "deployPath": "/opt/teamtalk",
  "sslPort": 443
}
```

| 字段 | 含义 |
|---|---|
| `serverUrl` | 客户端使用的 HTTP(S) 根地址；协议同时决定部署时是否启用 SSL |
| `tcpAddress` | 客户端使用的 IM TCP `host:port`，其端口同步到服务端运行配置 |
| `deployHost` | SSH 部署目标，可以与客户端端点不同 |
| `deployPort` | SSH 端口，默认 22 |
| `deployUser` | SSH 用户，默认 `root` |
| `deployPath` | 服务端安装目录，必须是非根绝对路径 |
| `sslPort` | HTTPS 监听端口；使用 HTTPS 时须与 `serverUrl` 中的端口一致 |

`DeploymentConfig` 会在 Gradle 配置阶段严格校验未知字段、URL、端口和路径，然后注入 Android `BuildConfig`、Desktop 运行与打包参数、部署任务和远程验收任务。

敏感配置保存在被 Git 忽略的 `gradle/deployment.secrets`。首次部署自动生成；升级时从远端 `conf/env.sh` 恢复。

## 标准任务

| 任务 | 用途 |
|---|---|
| `:desktop:run` | 启动连接已配置服务器的桌面客户端，并开启测试 HTTP 端口 |
| `:android:assembleDebug` | 构建连接已配置服务器的 Android APK |
| `:server:acceptanceTest` | 在已配置的真实部署上运行跨服务业务 E2E |
| `buildRelease` | 构建服务端和当前平台客户端产物 |
| `deployServer` | 首次部署或升级已配置服务器 |
| `uploadRelease` | 构建并上传客户端产物 |

## CI/CD 边界

- `ci.yml`：编译和本地确定性测试。
- `acceptance.yml`：对 `gradle/deployment.json` 指定的服务器执行业务验收。
- `release.yml`：构建多平台产物；手动发布按“部署服务端 → 远程验收 → 上传客户端”执行。

CI 不接受临时主机或外部 JSON 覆盖。fork 项目应修改并提交自己的 `gradle/deployment.json`，使构建产物与验收目标始终一致。
