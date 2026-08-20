# 工程约束

这些规则保护协议确定性、生命周期和可测试性。违反时应先说明为什么原边界不再成立，而不是增加
例外开关。

## 1. 依赖与所有权

- `protocol ← shared ← app ← android/desktop` 单向依赖。
- `server → protocol` 只复用契约；服务端生产代码禁止依赖客户端 SDK `shared`。
- 服务端 `domain` 不 import `infra`、RPC 生成 Stub 或 transport adapter；外部能力由领域端口注入。
- 每个长期对象有唯一所有者，owner 销毁时级联销毁。
- `close/destroy` 幂等。
- 网络断开只清连接层；AUTH_FAILED 清用户层。

## 2. 协议

- IProto 读写字段严格同序同类型。
- RPC IDL 是 method 编解码的唯一入口；每个方法必须显式声明唯一 `@RpcMethod(id)`。
- 新 NotifyType 登记 NotifyContracts。
- 新 MessageType 登记 body registry 和 policy。
- 不兼容变化升级 PROTOCOL_VERSION 和 golden tests。
- 优先传稳定模型，不手写重复 payload。

## 3. 本地优先

- ViewModel 不把一次网络响应作为长期页面状态。
- 新数据必须有 LocalCache/事件/恢复路径。
- 服务端写操作大多通过 NOTIFY 收敛客户端。
- readSeq、serverSeq、version 等单调字段用 max 合并。
- EventProcessor 成功后才推进游标。

## 4. 服务端

- 未认证与已认证连接使用不同 frame limit。
- EventLoop 不做阻塞 IO。
- 权限和附件校验在成功响应前完成。
- 领域状态写入后才持久化/推送事件。
- 消息、幂等索引和待投影 outbox 原子写入；跨存储投影失败必须可重试和启动恢复。
- 服务端禁止 `println`；初始化前必要信息使用受控 stderr，其余走 SLF4J/Recorder。
- 不吞异常；CancellationException 保持取消语义。

## 5. Kotlin 与状态

- 传输和状态 data class 使用 `val`，通过 `copy()` 更新。
- Compose 可空状态先捕获局部变量，不在跨重组分支中滥用 `!!`。
- StateFlow 的读改写在明确同步边界内完成。
- 不把可变集合直接暴露给 UI。
- Repository 保持 `CancellationException`；Feature/ViewModel 捕获宽泛异常时必须先重抛取消，
  owner 销毁后不得写入错误状态或调用结果回调。
- 单文件超过约 500 行时按真实职责拆分，避免为了行数机械分割。

## 6. 平台 UI

- Android 与 Desktop 不共享导航。
- 共享组件不创建平台 Window/NavController。
- Desktop 页面先选择工作区、检查器、模态、任务窗口或确认框。
- 缺后端能力时显示明确空态，不放假按钮/假数据。
- 稳定交互添加 testTag；已有 tag 非必要不改名。
- Desktop Enter 换行、Cmd/Ctrl+Enter 发送。

## 7. 配置

- 默认不增加布尔开关、profile、flavor 或运行时服务器选择。
- 部署坐标统一从 deployment.json 读取。
- secret 不入库、不写日志。
- 构建产物内嵌 commit/build time。
- 新配置必须说明所有者、默认值、组合测试和废弃方式。

## 8. 测试

- 协议/模型增加 round-trip。
- 算法、存储和状态合并用本地确定性测试。
- 跨服务/跨客户端业务加入真实部署验收。
- UI 改动启动真实客户端并截图。
- 不为单元测试暴露仅测试使用的生产 API。
- 不用固定坐标脚本代替语义验证。

## 9. 文档

- 稳定事实写权威章节；状态/缺口写 reference；过程留在提交/任务。
- 不复制 RPC、事件、配置表。
- 代码路径和命令必须可执行。
- 移动文档后扫描所有 Markdown、源码注释和 AGENTS.md 链接。
