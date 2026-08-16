# 设计令牌总表

> 代码位置：`app/src/commonMain/kotlin/com/virjar/tk/ui/theme/Tokens.kt`（`Tk` 对象）
> 本表是唯一事实源，代码与表不一致视为代码 bug。

## 1. 颜色

### 1.1 语义色（`Tk.colors`，明暗两套）

除 M3 ColorScheme 角色外，扩展色板承载 M3 没有的语义（hover/选中/气泡/在线…）。

| 令牌 | 亮色 | 暗色 | 用途 |
|------|------|------|------|
| `hover` | `#EDEEF1` | `#2A2D34` | 鼠标悬停背景（列表项/按钮/菜单项） |
| `selected` | `#E1EAFF` | `#1E2A47` | 列表选中项背景 |
| `divider` | `#E5E6EB` | `#2E3036` | 发丝分隔线 |
| `bubbleIncoming` | `#F2F3F5` | `#272A31` | 对方消息气泡（无阴影、无描边） |
| `bubbleOutgoing` | `#3370FF` | `#2E5AE8` | 自己消息气泡 |
| `metaText` | `#8F959E` | `#6B6E75` | 三级文本：时间戳/占位符/系统提示 |
| `secondaryText` | `#646A73` | `#A8ABB3` | 二级文本：消息预览/副标题 |
| `online` | `#34C724` | `#34C724` | 在线状态点 |
| `unreadBadge` | `#F54A45` | `#E54548` | 未读计数徽标 |
| `pinIcon` | `#8F959E` | `#6B6E75` | 置顶图钉（低调，非品牌色） |

### 1.2 M3 角色映射（AppTheme.kt）

| 角色 | 亮色 | 说明 |
|------|------|------|
| `primary` | `#3370FF` | LarkBlue，交互色/自己气泡/选中态文字 |
| `onPrimary` | `#FFFFFF` | |
| `primaryContainer` | `#E1EAFF` | 与 `selected` 同值（选中=浅蓝家族） |
| `background` | `#F5F6FA` | 应用底色（灰，非白） |
| `surface` | `#FFFFFF` | 内容区/卡片 |
| `surfaceVariant` | `#F0F1F5` | 中栏列表底 |
| `onSurface` | `#1D2129` | 主文本 |
| `onSurfaceVariant` | `#4E5969` | 二级文本（M3 内） |
| `outlineVariant` | `#E5E6EB` | 与 `divider` 同值 |
| `error` | `#F54A45` | 飞书危险红（原 `#F53F3F` 校正） |
| `secondary` | `#00B89A` | Teal（成功态/次要操作） |

暗色主结构与现状一致（`#16181D` 底 / `#1D2026` 面 / primary `#5B8DFF`），仅接入上表扩展色。

### 1.3 头像配色板（名称哈希取色，不用 MaterialTheme）

```
#3370FF #00B89A #FF7D00 #F54A45 #7B61FF #00A870 #E6294A #3491FA
```

## 2. 字阶

沿用 M3 Typography，关键档位对齐飞书桌面（14px 正文基线）：

| 样式 | 规格 | 用途 |
|------|------|------|
| `titleMedium` | 16 SemiBold / 24 | 面板标题（列表头/设置页标题） |
| `titleSmall` | 14 Medium / 20 | 会话名/联系人名/列表项主文本 |
| `bodyMedium` | 14 Normal / 20 | 聊天正文/表单输入 |
| `bodySmall` | 13 Normal / 18 | 会话预览行 |
| `labelMedium` | 12 Medium / 16 | 徽标数字/按钮 |
| `labelSmall` | 11 Normal / 14 | 时间戳/系统提示/群昵称 |

> 注：`bodySmall` 13sp、`labelSmall` 11sp 是对 M3 默认（12/10）的飞书校正。

## 3. 间距（`Tk.spacing`，4px 栅格）

| 令牌 | 值 | 典型用途 |
|------|----|---------|
| `xs` | 4 | 图标与文字间隙、紧凑内边距 |
| `sm` | 8 | 头像与文本间隙、列表项上下留白（内容级） |
| `md` | 12 | 气泡内边距（左右）、列表项上下 padding |
| `lg` | 16 | 面板左右 padding、表单外边距 |
| `xl` | 20 | 空态留白、设置区块间隔 |
| `xxl` | 24 | 页面级留白 |

## 4. 圆角

| 令牌 | 值 | 用途 |
|------|----|------|
| `Tk.shapes.avatar(size)` | `size × 0.22` | **圆角方形头像**（飞书特征，40dp 头像 ≈ 8.8dp 圆角） |
| `small` | 8 | 气泡、输入框、卡片、菜单 |
| `extraSmall` | 4 | 气泡指向角（连续消息侧的收小角） |
| `medium` | 12 | 弹窗/大卡片 |
| pill | `CircleShape` | 徽标/按钮（高度的一半） |

气泡角规则：四角 8dp；非连续消息时，**靠近头像的一角收 4dp**（视觉上"接"头像，替代气泡尾巴）。

## 5. 尺寸（`Tk.dimens`）

| 令牌 | 桌面 | Android | 用途 |
|------|------|---------|------|
| `railWidth` | 56 | —（底部导航） | 左侧导航栏宽 |
| `listPaneWidth` | 300 | —（屏宽） | 中栏列表宽 |
| `listItemHeight` | 64 | 72 | 会话/联系人项高 |
| `listAvatar` | 40 | 48 | 列表头像 |
| `chatAvatar` | 36 | 36 | 聊天气泡旁头像 |
| `headerHeight` | 48 | 56（TopAppBar） | 列表头/聊天头 |
| `inputMinHeight` | 36 | 44 | 输入框最小高 |
| `bubbleMaxWidth` | 420 | 300 | 气泡最大宽（含媒体） |
| `iconSize` | 20 | 22 | 工具栏图标 |

## 6. 动效

| 场景 | 规格 |
|------|------|
| hover 进出/选中背景切换 | 无动画（即时），色彩本身已低对比 |
| DropdownMenu/弹窗 | M3 默认（150ms fade+scale） |
| 列表滚动 | 系统默认，禁止动画装饰 |
