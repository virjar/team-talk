# Demo 部署指南

TeamTalk 当前只部署到 Demo 站点。目标坐标来自 `gradle/profiles/demo.json`，不支持临时 Profile 或外部 JSON 注入。

## 一键部署与验收

```bash
./gradlew deployServerDemo
./gradlew :server:demoTest
```

`deployServerDemo` 自动区分首次部署和升级，构建服务端分发包、同步文件、保留持久化数据、重启 systemd 服务并执行健康检查。业务发布必须紧接着运行 `demoTest`。

需要更新 SSL 证书时：

```bash
./gradlew deployServerDemo -PsslCert=/absolute/path/fullchain.pem -PsslKey=/absolute/path/privkey.pem
```

## 配置与 Secret

- 非敏感坐标：`gradle/profiles/demo.json`，进入 Git。
- 敏感数据：`gradle/profiles/demo.secrets`，已忽略，仅供部署读取。
- 服务器运行配置：`/opt/teamtalk/conf/env.sh`，权限 600。

首次部署会生成 Secret；升级时优先从服务器现有 `env.sh` 恢复，不应通过 Git 或 CI 日志传递。

## 客户端发布

```bash
./gradlew buildRelease
./gradlew uploadRelease
```

GitHub Actions `release.yml` 负责跨平台产物。手动触发时的顺序是：

1. 构建 Server、Desktop 和 Android。
2. 部署 Demo 服务端。
3. 运行 `:server:demoTest`。
4. 验收通过后上传客户端安装包。

Tag `v*` 会创建 Draft GitHub Release，不会自动部署服务器。

## 服务器目录

```text
/opt/teamtalk/
├── bin/
├── conf/
├── lib/
├── static/
├── data/
└── logs/
```

数据备份需同时覆盖 PostgreSQL 与 `data/` 下的 RocksDB/文件存储。备份 RocksDB 前应停止服务，避免获得不一致快照。

## 运维检查

```bash
ssh root@im.virjar.com 'systemctl status teamtalk'
ssh root@im.virjar.com 'journalctl -u teamtalk -f'
ssh root@im.virjar.com 'tail -100 /opt/teamtalk/data/logs/teamtalk.log'
```

如果协议或持久化结构发生了破坏性变更，当前未发布阶段允许在明确目标后清理 Demo 测试数据；不应在部署脚本中默认自动删除数据。
