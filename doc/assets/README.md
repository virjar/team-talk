# 文档资产

本目录保存既有产品演示稿。新增设计配图使用可版本化、可 diff 的 SVG，截图与录屏留在外部验收记录。

`presentations/` 存放可直接对外演示、并由对应产品文档说明事实边界的演示稿。
《为什么是 TeamTalk》由 [`../presentations/build-why-teamtalk.mjs`](../presentations/build-why-teamtalk.mjs)
生成；竞品事实、日期或产品边界变化后，应同时更新长文、演示稿源码和最终 `.pptx`。

UI 验收截图不进入本仓库（见根 `AGENTS.md`）：验收证据由测试报告与 CI 记录承载。

使用规则：

- 视觉与交互规范以 [`05-clients/`](../05-clients/README.md) 为准；
- 当前功能完成度以[功能状态](../10-reference/feature-status.md)为准；
- 不向主仓库新增截图、录屏或其他二进制图片；
- 稳定设计配图应说明对象与关系，避免把临时 UI 状态当作长期设计；
- 验收证据链接到对应测试报告或任务记录，不持续堆入本目录。
