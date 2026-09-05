# 文档资产

本目录存放文档需要引用的图片、演示稿等二进制资产。

`presentations/` 存放可直接对外演示、并由对应产品文档说明事实边界的演示稿。
《为什么是 TeamTalk》由 [`../presentations/build-why-teamtalk.mjs`](../presentations/build-why-teamtalk.mjs)
生成；竞品事实、日期或产品边界变化后，应同时更新长文、演示稿源码和最终 `.pptx`。

UI 验收截图不进入本仓库（见根 `AGENTS.md`）：验收证据由测试报告与 CI 记录承载。

使用规则：

- 视觉与交互规范以 [`05-clients/`](../05-clients/README.md) 为准；
- 当前功能完成度以[功能状态](../10-reference/feature-status.md)为准；
- 新截图只有在能解释稳定设计时才进入主文档；
- 一次测试的截图证据应留在测试报告或任务记录，不持续堆入本目录；
- 替换截图时使用能说明页面、主题和状态的文件名，避免使用日期或“最终版”等临时命名。
