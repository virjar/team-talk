# 参考与项目状态

本章用于快速查找稳定编号、当前能力边界和术语。它不解释完整设计背景；需要理解原因时，应回到架构、协议或领域章节。

## 参考入口

- [功能状态](feature-status.md)：当前已实现、部分实现、计划中和明确不做的能力。
- [RPC 参考](rpc-reference.md)：serviceId、methodId、参数与返回值。
- [事件参考](event-reference.md)：NotifyType、payload 和客户端副作用。
- [测试选择器](test-selectors.md)：Desktop / Android 自动化的 `testTag` 与窗口 ID。
- [术语表](glossary.md)：产品、协议和同步概念的统一含义。
- [路线图](roadmap.md)：尚未完成的阶段目标与进入条件。

## 事实源优先级

当文档与代码不一致时，按以下方式处理：

1. wire、RPC、Notify 和消息体的最终事实源是 `protocol` 中的契约与生成测试；
2. 服务端权限和持久化语义以领域服务及其测试为准；
3. UI 选择器以源码中的 `testTag` 为准；
4. 部署参数以 `gradle/deployment.json` 的 schema 和运行时代码为准；
5. 发现差异时应修正文档或代码，不能长期保留两套解释。

功能状态是动态快照，允许频繁更新；协议编号和 RPC methodId 是兼容性契约，修改必须遵守[协议演进规则](../04-protocol/README.md)。
