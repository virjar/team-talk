# 本地测试

本地测试的价值是快速、确定、容易定位。它适合锁定协议格式、状态机、缓存与算法边界，不负责替代真实部署和真实客户端验收。

## 常用入口

```bash
# SDK、协议与公共业务逻辑
./gradlew :protocol:jvmTest
./gradlew :shared:jvmTest

# 服务端领域、持久化与进程内集成测试
./gradlew :server:test

# 客户端共享逻辑与 Desktop 目标测试
./gradlew :app:desktopTest :desktop:desktopTest

# 生产代码日志规范
./scripts/check-println.sh
```

开发中可以只运行受影响模块；准备交付时应扩大到相邻边界。例如修改消息体既影响 `protocol`
编解码，也影响服务端校验和客户端渲染，不能只跑一个 UI 测试。

`:server:test` 需要一个已经存在的 PostgreSQL 数据库，但不会创建数据库，也不会清空或修改其
`public` schema。每个进程内集成/E2E 环境会创建随机 `tt_test_*` schema，并通过 JDBC
`currentSchema` 只在该 schema 建表；正常关闭和启动失败都会执行 `DROP SCHEMA ... CASCADE`。
默认连接是本机 `jdbc:postgresql://localhost:5432/teamtalk`、当前系统用户名和空密码，也可显式设置：

```bash
TK_TEST_PG_JDBC=jdbc:postgresql://localhost:5432/teamtalk_test \
TK_TEST_PG_USER=teamtalk_test \
TK_TEST_PG_PASSWORD=your-test-password \
./gradlew :server:test
```

测试数据库账号只需要连接目标数据库以及创建、删除 schema 的权限。`DatabaseFactory` 目前仍是
进程级单例，因此服务端测试固定单 fork、关闭 JUnit 并行执行；schema 隔离用于保护开发数据和失败
清理，不表示同一测试 JVM 可以并发启动多个环境。

## 应优先放在本地的测试

- wire header、payload 编解码和协议版本拒绝规则；
- RPC ID、Notify payload 与生成代码一致性；
- 消息发送状态机、重连、补发、去重和游标推进；
- LocalCache 的读写、迁移和并发边界；
- 文件路径规范化、大小限制与 MIME 分类等纯规则；
- 权限矩阵、群角色计算、错误码映射；
- 可在无网络环境稳定复现的 UI 状态和组件行为。

## 不应只靠本地测试证明的行为

- PostgreSQL、RocksDB、Lucene 和真实独立进程生命周期的协作（进程内组合测试仍属于本地安全网）；
- 上传后的附件是否能从正式文件端点读取；
- 两个账户之间的实时通知、离线补偿和已读同步；
- Desktop 窗口层级、弹窗、抽屉、拖放和下载动画；
- Android 系统权限、键盘、媒体选择和后台恢复；
- 部署脚本、systemd、反向代理和外部访问地址。

这些行为应进入[部署验收](deployment-acceptance.md)或客户端验收。

## 测试设计准则

### 验证公开契约

测试应从模块公开边界观察结果。不要把内部实现细节写成断言，否则一次合理重构会产生大量无意义失败。

### 控制时间与并发

异步测试使用明确的状态等待和有限超时，不使用固定长时间休眠。涉及重连、心跳或延迟任务时，测试应能说明等待的状态和失败原因。

### 固定边界，而不是固定样例

协议长度、分页边界、空输入、重复请求、越权访问和非法路径都应有测试。只验证一个正常样例无法保护系统边界。

### 测试数据可隔离

测试账号、chatId、clientMsgId 和临时文件必须可区分，避免并行或重跑时相互污染。远程验收使用独立前缀；本地持久化测试使用临时目录。

## 失败定位顺序

1. 先看最小失败测试及其异常，不先扩大重跑范围。
2. 判断失败属于契约、领域逻辑、持久化还是环境依赖。
3. 若本地通过而部署验收失败，核对部署版本、配置与服务日志。
4. 若协议验收通过而客户端失败，核对本地缓存、语义树与 UI 状态。
5. 修复后增加能锁定根因的最小回归测试，再恢复完整验收。
