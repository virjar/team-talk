# 从试用到生产

目标读者：已经按[私有化部署](private-deployment.md)或[AI 辅助部署](ai-assisted-deployment.md)
跑通了测试站点，现在要把它交给真实用户长期使用的管理员。本章是加固清单：每一项标注了
现状（✅ 已支持 / ⚠️ 支持但有注意事项 / 🚧 尚未提供），并给出操作入口。

原则：加固项彼此独立，可以按需逐项进行；但 ❶❷❸ 是对外服务前的最低要求。

## 最低要求（对外服务前必做）

### ❶ 管理员密码与访问面

- ✅ 修改随机生成的管理员密码：管理台「管理安全」页（见私有部署 §5）。
- ✅ 站点通过防火墙/安全组只开放 `80`（HTTP）、`5100`（TCP）端口；`443` 在启用
  HTTPS 正式证书时一并开放（试用期为自签/HTTP，无需 443）；
  PostgreSQL（5432）与 SSH（22）只对管理员开放。
- ⚠️ 注册是开放的：任何能访问站点的人都可以注册。测试期建议防火墙限制来源 IP，
  正式使用时再放开；当前没有注册开关或邀请机制（需求明确后可立项）。

### ❷ 数据备份

- ⚠️ 停服冷备（当前唯一一致备份方式）：停 TeamTalk 与 PostgreSQL，整目录复制
  `$deployPath/`（含 `data/`、`conf/`、PostgreSQL 数据卷），再启动。恢复 = 原样回拷。
- 🚧 在线热备与一键恢复工具尚未提供（[REL-01](../10-reference/roadmap.md)）；
  升级前务必先做一次冷备（详见[部署与升级](../07-operations/deployment.md)）。

### ❸ 升级纪律

- ✅ 任何升级前：阅读目标版本说明、完成冷备、先在测试实例演练（见私有部署 §9）。
  升级预检会拒绝数据集不匹配的组合，但备份责任在管理员。

## 传输安全（HTTPS）

### 自签证书（试用默认）

- ✅ 部署工具自动生成自签证书，客户端内置信任，开箱即用。局限：浏览器访问首页与
  管理台会提示“不安全”，且证书 10 年固定不轮换。

### 正式证书（推荐，需要域名）

1. 为服务器准备域名（如 `im.example.com`）并解析到服务器 IP。
2. 申请证书：Let's Encrypt 免费证书即可。服务器上有 Certbot 时：
   ```bash
   certbot certonly --standalone -d im.example.com
   # 证书：/etc/letsencrypt/live/im.example.com/fullchain.pem
   # 私钥：/etc/letsencrypt/live/im.example.com/privkey.pem
   ```
3. 修改 `buildSrc/deployment-local/Deployment.kt` 的 `server.http.url` 为
   `https://im.example.com`，然后带证书升级部署：
   ```bash
   ./gradlew deployServer \
     -PsslCert=/etc/letsencrypt/live/im.example.com/fullchain.pem \
     -PsslKey=/etc/letsencrypt/live/im.example.com/privkey.pem
   ```
   证书与 TCP 共用，SAN 必须覆盖该域名。已在用的自签 TCP 证书切换见
   [传输配置边界](../07-operations/configuration.md#传输配置边界)。
4. ⚠️ 续期：Let's Encrypt 90 天过期，续期后重跑第 3 步命令（幂等）。设置日历提醒，
   或用 crontab + `certbot renew` + 部署命令的钩子（注意部署要求干净源码树，自动化
   前先阅读[部署与升级](../07-operations/deployment.md)）。

## 数据库

- ✅ 当前拓扑：PostgreSQL 由部署工具在同机以 Docker Compose 拉起，数据落在
  `$deployPath` 的卷中，零运维。
- ⚠️ 独立数据库服务器：服务端支持 `DATABASE_JDBC_URL` 环境变量指向外部实例，但
  部署工具生成的 `conf/env.sh` 不包含该键，**每次升级会重写 env.sh**——升级后需
  重新补写并 `systemctl restart teamtalk`。数据库密码仍在 `deployment.secrets`
  管理内。稳定支持独立数据库属于后续工作，当前更适合有运维能力的团队。
- 单实例是当前架构边界（万级用户），容量评估见[私有化部署 §1](private-deployment.md#1-准备服务器)。

## 客户端分发与签名

### Android 签名

- ✅ 在 `buildSrc/deployment-local/Deployment.kt` 配置 `client.androidSigning`
  （keystore 路径 + 别名，密码从环境变量或 local.properties 提供，不入库），之后
  Debug/Release 均使用客户证书签名。不配置时使用内置试用证书——**试用证书只适合
  内部测试，正式分发前必须换成自己的证书**，且一旦分发给用户就不要再更换
  （换签名等于换应用，老用户无法覆盖安装）。
- 生成 keystore：`keytool -genkeypair -v -keystore release.keystore -alias teamtalk -keyalg RSA -keysize 2048 -validity 10950`

### 桌面安装包

- ✅ macOS/Windows/Linux 安装包由同一构建机交叉产出，经管理台「客户端发布」或
  `release -PreleaseTargets=site` 发布到站点下载首页；桌面端应用内更新为文件级增量。
- ⚠️ macOS 开发者签名与公证：构建链路已准备（见[Desktop 制品构建](../07-operations/desktop-cross-build.md)），
  需要Apple 开发者账号的证书与公证凭据；未签名/未公证的包在 macOS 上首次打开需要
  右键→打开绕过 Gatekeeper。
- ⚠️ Windows 代码签名：未签名包 SmartScreen 会告警；签名证书接入属于后续工作。

## 推送通知（Android 后台）

- ⚠️ 六家厂商推送（小米/华为/荣耀/OPPO/vivo/魅族）已实现部署级接入：在部署配置中
  为需要的厂商填入应用凭据并放入官方 SDK。前提是：应用已在各厂商开放平台以你的
  包名注册、通过审核并获得推送通道/分类/模板权限。**全部六家均未经过真机送达验收**，
  详见[Android 通知](../05-clients/android.md#消息通知的当前范围)。
- 未配置厂商通道时，Android 端保留进程存活期间的标准通知——即开箱即用，只是杀进程后
  收不到离线推送。

## 三方服务（短信/邮箱等）

- 🚧 短信验证码、邮件通知等三方服务接入尚未提供；当前账号体系为用户名+密码注册，
  手机号选填仅用于管理员查询。需求明确后按 roadmap 立项。

## 检查清单（复制给 AI 助手核查也可以）

```text
[ ] 管理员密码已修改，deployment-local 目录已离线备份
[ ] 防火墙仅开放 80/443/5100；注册暴露面已评估
[ ] 升级前冷备流程演练过一次
[ ] （有域名）HTTPS 正式证书部署 + 续期提醒已设置
[ ] Android 正式签名已配置且 keystore 已备份
[ ] （需要）厂商推送通道已在对应开放平台申请并通过审核
[ ] 客户端首装/升级/消息/文件全流程在真实设备验证过
```
