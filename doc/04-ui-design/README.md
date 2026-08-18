# UI 设计系统 — 飞书（Lark）风格

> 本目录是客户端 UI 的**设计事实源**：UI 代码的所有视觉与交互决策以这里为准。
> 项目没有人类设计师，本规范由 AI 依据飞书桌面端设计语言提炼，通过截图迭代闭环收敛。

## 为什么选飞书

1. **信息密度优先**：IM 是生产力工具，飞书的紧凑布局（13-14px 正文、64dp 会话项）在同等窗口面积下比 Material 默认样式（大触控目标、大留白）多显示约 40% 的会话/消息。
2. **克制的中性色**：灰阶体系（#F5F6F7/#F2F3F5/#E5E6EB 分层）让内容本身成为视觉主角。品牌蓝 #3370FF 只用于按钮、焦点和选中等小面积交互态；大面积自己消息改用浅蓝语义表面，避免连续消息形成“蓝墙”。
3. **桌面优先的交互模型**：三栏布局、hover 态、右键菜单、多行编辑和显式快捷键发送——正是 Desktop 端当前最缺的。
4. **圆角方形头像**是飞书的强识别特征，与微信/QQ 的圆形头像形成差异。

## 文档

| 文档 | 内容 |
|------|------|
| [design-tokens.md](design-tokens.md) | **令牌总表**：颜色（明/暗）、字阶、间距、圆角、尺寸——代码中 `Tk` 对象的对照表 |
| [components.md](components.md) | 组件规格（头像/会话项/气泡/输入区/导航栏）+ 全部页面布局规格 + 交互规范 |
| [placeholders.md](placeholders.md) | **后端缺失占位清单**：UI 已设计、等后端能力补齐的功能位（课题：视觉先行、接口后补） |

## 工作流（截图迭代闭环）

```
改代码 → ./gradlew :desktop:run → curl :18080/screenshot → 目视对比规格 → 修 → 循环
```

- TestHttpServer 在 Desktop 开发运行时提供语义树/点击/输入/截图 HTTP API，发布包会物理移除，详见 [07-testing/ai-workflow.md](../07-testing/ai-workflow.md)。
- 每轮视觉迭代必须以截图为准，不接受"代码看起来对"。
- 暗色模式在亮色验收后进行，两套截图都入 `screenshots/`（命名 `light-<页面>.png` / `dark-<页面>.png`）。

## 验收基线（2026-08 首轮，亮色）

| 截图 | 内容 |
|------|------|
| `screenshots/light-login.png` | 登录窗（已被 §2.3 重排版取代，见 P4 轮） |
| `screenshots/light-main-empty.png` | 三栏空态：细导航栏 + 会话列表 + Logo 空态 |
| `screenshots/light-conversation-list.png` | 会话列表：未读徽标/时间戳/群角标/置顶区 |
| `screenshots/light-chat.png` | 聊天面板：双方头像/内容自适应气泡/已读水位线/多行富文本输入 |
| `screenshots/light-contacts-grouped.png` | 通讯录：搜索框 + 拼音首字母分组 sticky 头 + 右侧索引条（P4 轮） |

## 验收基线（2026-08 P4 轮，暗色）

主题体系：设置「外观」三态（跟随系统/浅色/深色，`TkTheme` 单一事实源，双端持久化）；
走查强制参数 `-Dteamtalk.theme=dark|light`（run 任务透传）。

| 截图 | 内容 |
|------|------|
| `screenshots/dark-login.png` | 登录窗（§2.3 无装饰 420×480，纯底卡片） |
| `screenshots/dark-main-conversations.png` | 三栏：rail #272A31 / 列表 #16181D / 右栏 #1D2026 |
| `screenshots/dark-chat.png` | 聊天：暗色语义气泡/高对比正文/富文本输入容器 |
| `screenshots/dark-contacts.png` | 通讯录 |
| `screenshots/dark-settings.png` | 设置（含「外观」行） |
| `screenshots/dark-group-detail.png` | 群详情右栏面板 |
| `screenshots/dark-subwindow-creategroup.png` | 子窗口（§2.6 统一 460 宽 + 返回键头） |

注：dark-* 截图顶部白色条带为 macOS 原生标题栏（走查机器系统为浅色；系统暗色用户标题栏随系统）。

## 设计原则（约束所有组件）

1. **令牌唯一**：组件内禁止出现裸 `dp`/`Color(0xFF...)`，一律引用 `Tk.spacing` / `Tk.colors` / `Tk.dimens`；新需求先补令牌再用。
2. **密度分级**：桌面紧凑（会话项 64dp 高、头像 40dp），Android 触控（72dp/48dp）——通过 `Tk.dimens` 平台参数化，不用 `if (platform)` 散落判断。
3. **状态即层**：hover → `Tk.colors.hover`；选中 → `Tk.colors.selected`；两者叠加时选中优先。所有列表类组件统一。
4. **占位先行**：后端没有的能力（在线状态/reaction/输入中…）按 [placeholders.md](placeholders.md) 预留 UI 位，不硬造假数据。
5. **动效克制**：只有状态切换（hover/选中/菜单）允许 100-150ms 动画；布局位移不做动画（列表滚动性能优先）。
