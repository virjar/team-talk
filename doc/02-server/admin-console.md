# 管理后台（Admin Console）

> React SPA + 服务端同进程 REST。运维系统：用户治理/消息审查/日志检索/群管理。
> 访问：`https://<server>/admin/`

## 凭据与安全模型（刻意极简）

固定账号密码，环境变量配置（`env.sh` 或 systemd）：

```bash
ADMIN_USER=admin                    # 默认 admin
ADMIN_PASSWORD=admin-change-me      # 必须修改默认值
```

- POST `/api/admin/login` 换随机 token（内存，12h 过期），SPA 存 localStorage
- 后续请求 `Authorization: Bearer <token>`
- **生产建议**：nginx 将 `/api/admin` 与 `/admin` 限内网（allow/deny）或 VPN 后
- 无细粒度权限（单管理员模型）；多管理员/审计日志见 ROADMAP V2

## 前端工程（admin/）

```
admin/                     # 第四端（与 android/desktop 平级）
├── src/pages/             # Login/Dashboard/Users/Messages/Logs/Groups
├── src/api/client.ts      # axios + token 注入 + 401 自动回登录
└── vite.config.ts         # base=/admin/；dev proxy /api→:8080
```

开发：`cd admin && npm run dev`（:5173，CORS 已配）
构建部署：`npm run build` → `./gradlew :server:copyAdminDist` → 随 jar 部署

## REST API 清单

| 端点 | 功能 |
|------|------|
| POST /api/admin/login | 凭据换 token |
| GET /api/admin/overview | 在线数/用户数/群数/今日事件量/存储用量 |
| GET /api/admin/users?query&page&size | 用户分页（用户名/昵称/UID 模糊） |
| GET /api/admin/users/{uid} | 详情：user+devices+friends+groups+online |
| POST /api/admin/users/{uid}/ban | 封禁三动作：status=2 + 全 token 吊销 + 全设备踢线 |
| POST /api/admin/users/{uid}/unban | status=1 |
| POST /api/admin/users/{uid}/kick-all | 踢全部在线设备 |
| POST /api/admin/users/{uid}/reset-password | BCrypt 新密码 + 踢线（body: {"password":...}） |
| GET /api/admin/messages?keyword&chatId&senderUid&start&end&page&size | Lucene 全局检索 |
| GET /api/admin/messages/{chatId}/{seq}/context?size | 上下文（前后各 size/2） |
| POST /api/admin/messages/{chatId}/{seq}/revoke | 管理员撤回（广播复用，免权限） |
| GET /api/admin/logs/server | 服务端日志文件列表（logs/ + traces/） |
| GET /api/admin/logs/server/{name}?lines=300 | tail（路径穿越防护：canonical 必须在 logsDir 内） |
| GET /api/admin/logs/client | 客户端日志树：{uid: {deviceId: [dates]}} |
| GET /api/admin/logs/client/content?uid&deviceId&date | 内容（tail 2000） |
| GET /api/admin/groups?query&page&size | 群分页 |
| GET /api/admin/groups/{chatId} | 群详情+成员 |
| POST /api/admin/groups/{chatId}/dissolve / mute-all / unmute-all | 管理操作 |

## 封禁语义（与登录链路的联动）

1. `UserService.login` 检查 `status==2` → 拒绝（"账号已被封禁"）
2. `AuthService.handleRefresh` 复查 `requireActive`（refresh 只验 token 的绕过口已封）
3. ban = status 置位 + `revokeAllUserTokens` + `ClientRegistry.kickUser`（在线即断）

## 口径说明

- **今日事件量** = `sync_events` 当日行数（**分发口径**：群消息按接收者计数，
  含非消息通知）——趋势参考，非精确消息数（消息在 RocksDB 无按日索引）
- **存储用量** = 目录 walk（消息 RocksDB / 文件存储）

## V2 遗留（见 ROADMAP）

审计日志（管理员操作记录）/ 统计图表 / 公告推送 / 敏感词 / 事件流查询 / 多管理员
