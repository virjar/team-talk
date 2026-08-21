# 部署与升级

## 1. 部署任务

根项目提供：

| 任务 | 结果 |
|---|---|
| `:server:buildServerDist` / `installDist` | 服务端分发 |
| `deployServer` | 按 deployment.json 部署或升级 |
| `buildRelease` | 服务端、Desktop 当前平台与 Android 发布产物 |
| `uploadRelease` | 构建并上传客户端安装包 |
| `uploadClientArtifacts` | CI 上传已分平台构建的产物 |

Desktop 不能在一个 OS 交叉生成所有安装包；GitHub Actions 分别构建 deb、msi 和两种 macOS dmg。

## 2. 首次部署

`deployServer` 的首次流程：

1. 校验 deployment.json 安全格式。
2. 构建服务端分发。
3. 创建 data、conf、ssl、static 等远端目录。
4. 生成或加载本地 deployment.secrets。
5. 上传分发文件、docker-compose、env.sh 和可选 TLS。
6. 启动 PostgreSQL并确认数据库用户。
7. 注册/更新 systemd 服务。
8. 启动 TeamTalk 并输出目标信息。

首次自动生成的 secret 只保存在本地不入库文件和远端 mode 600 的 env.sh。应另外纳入组织密码管理。

## 3. 升级

升级会尝试从远端 env.sh 提取现有 secret，避免重新生成导致数据库或 TLS 不可用；随后备份安装目录
并同步新的可执行/静态内容。

运行态路径必须排除覆盖/删除：

- `data/`
- `conf/env.sh`
- `conf/ssl/`
- 实例日志
- 实例生成的 docker 数据

部署脚本创建的 `${deployPath}.bak` 不是完整数据备份，不能代替 PostgreSQL dump 与 data 快照。

### 预发布 epoch 7 切换

当前组织事实与受管部门群使用持久 desired/applied revision 投影，服务端 schema/data epoch 为 7。
这是明确的破坏性预发布基线，不提供旧 token、旧组织投影或旧 schema 的兼容迁移。切换测试实例前停止写入，
同时重建 PostgreSQL schema/volume 并清空服务端 durable `data/` 后再启动；所有 access/refresh token
永久失效，客户端必须重新登录。只清数据库或只清某个本地存储目录都不是有效升级方式。
启动会在绑定 TCP 前完整应用所有受管部门群 pending revision；任何未收敛项都会使启动失败，不能通过
反复重启或忽略日志绕过。修复数据库事实或投影错误后再启动。

## 4. 发布门禁

```text
local deterministic tests
  → build server/client artifacts
  → deploy test instance
  → /health
  → :server:acceptanceTest
  → publish client artifacts
```

如果服务端协议已升级而旧客户端不兼容，必须协调客户端发布和服务端切换；AUTH 版本错误应给明确
升级提示，而不是无限重连。

## 5. 备份

一次可恢复备份至少包括：

- PostgreSQL 一致性 dump/volume snapshot，包含 users、devices 与只存哈希的 credentials。
- `data/rocksdb`。
- `data/file-store/rocksdb` 与 `data/file-store/files` 同一时间点副本。
- `conf/env.sh`、TLS 和部署坐标的安全副本。

Lucene 可以重建，但在恢复时间要求严格时也可备份。临时上传和普通日志通常不作为恢复必需项。

## 6. 回滚

1. 停止新实例写入。
2. 判断失败是否改变 PostgreSQL schema、服务端 data epoch 或 RocksDB 格式。
3. 若数据结构兼容，可恢复旧二进制和 conf。
4. 若不兼容，恢复升级前数据库与 data 快照，不能只换 jar。
5. 启动后检查 health，并运行至少认证、消息和附件验收。

测试实例允许在明确授权下清空数据重建；生产实例必须有迁移与恢复方案。

## 7. systemd

服务单元工作目录是 deployPath，EnvironmentFile 指向 `conf/env.sh`，ExecStart 使用
`bin/teamtalk.sh`。启动前确保 PostgreSQL Compose 已运行。常用操作：

```bash
systemctl status teamtalk
journalctl -u teamtalk -n 200 --no-pager
systemctl restart teamtalk
```

正式实例应设置合理的 restart policy、打开文件数和 JVM 内存，并将变更纳入部署代码而非手工漂移。
