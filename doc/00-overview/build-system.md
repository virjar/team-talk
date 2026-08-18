# Demo 构建系统

TeamTalk 当前只有一个可运行环境：**Demo**（`im.virjar.com`）。客户端、远程业务测试和部署任务共享同一份配置，不存在 dev/production flavor、`-Pprofile` 或运行时切换服务器。

## 为什么收敛为单环境

原多 Profile 系统同时包含 JSON 动态发现、Gradle 任务矩阵、Android product flavor、Desktop JVM 参数选择和 CI 外部 JSON 注入。在项目只运营一个 Demo 时，这些分支没有业务价值，反而会导致：

- 本地测试、客户端和部署可能指向不同服务器。
- 新增功能需要在多套任务和配置中重复维护。
- “可配置”隐藏了未被真实验证的分支。

如果未来出现真实的私有化产品需求，应以独立发行工程设计，而不是提前在主工程恢复动态 Profile。

## 唯一配置

`gradle/profiles/demo.json` 只保留非敏感的构建与部署坐标：

```json
{
  "serverUrl": "https://im.virjar.com",
  "tcpAddress": "im.virjar.com:5100",
  "deployHost": "im.virjar.com",
  "deployUser": "root",
  "deployPath": "/opt/teamtalk",
  "sslPort": 443
}
```

`DemoConfig` 在 Gradle 配置阶段严格解析该文件，并注入：

- Android `BuildConfig`；
- Desktop 运行与打包 JVM 参数；
- `deployServerDemo` / `uploadRelease`；
- `:server:demoTest`。

密码、JWT 密钥和证书口令位于 `gradle/profiles/demo.secrets`，不进入 Git。

## 任务

| 任务 | 用途 |
|---|---|
| `:desktop:runDemo` | 启动连接 Demo 的桌面客户端，开启测试 HTTP 端口 |
| `:android:assembleDebug` | 构建连接 Demo 的 Debug APK |
| `:server:demoTest` | 在已部署 Demo 上运行真实业务 E2E |
| `buildRelease` | 构建 Demo 服务端和当前平台客户端产物 |
| `deployServerDemo` | 部署/升级 Demo 服务端 |
| `uploadRelease` | 构建并上传 Demo 客户端产物 |

## CI/CD 边界

- `ci.yml`：编译与本地确定性测试，是快速安全网。
- `demo-smoke.yml`：手动在真实 Demo 上运行业务验收。
- `release.yml`：构建多平台产物；手动部署时按“部署服务端 → Demo E2E → 上传客户端”顺序执行。

配置文件是单一事实源，CI 不接受临时 host/profile JSON，从而保证测试的站点就是客户端实际使用的站点。
