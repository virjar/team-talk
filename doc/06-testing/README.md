# E2E 测试基础设施

> TeamTalk 的端到端测试采用 AI 驱动模式，通过 TestHttpServer 语义 Action 操作 UI，
> TestPeer 模拟对端用户。不写固定脚本，每步先 dump 状态再决策。

## 目录

- [1. 测试理念](#1-测试理念)
- [2. TestHttpServer（Desktop）](#2-testhttpserverdesktop)
- [3. TestPeer（对端模拟）](#3-testpeer对端模拟)
- [4. Android 测试工具](#4-android-测试工具)
- [5. 测试约束](#5-测试约束)

---

## 1. 测试理念

### AI 驱动，不写固定脚本

固定脚本流程太僵化，无法适应不同场景和意外状态。AI 必须根据每步的**实际屏幕状态**做决策：

```
循环：
1. dump UI 语义树 → 理解当前页面状态
2. 判断处于哪个状态 → 决定下一步操作
3. 执行单个操作（click/input/keypress）
4. 验证操作结果（不假设一定成功）
5. 遇到异常（弹窗/网络错误/意外跳转）→ 分析原因 → 自行决策
```

### 为什么不写脚本

实际测试中反复证明：多步骤封装成 Python 脚本必然因意外状态失败。原因是：
- UI 状态转换有异步延迟（Compose recomposition）
- 网络操作有超时/重试
- 窗口可见性变化导致 `remember` 状态残留

每步单独执行 + 验证，才能可靠地适应这些不确定性。

---

## 2. TestHttpServer（Desktop）

进程内 HTTP 服务（端口 18080），暴露 Compose 语义树供测试工具操作。零系统权限依赖。

### API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/ping` | GET | 健康检查 |
| `/semantics` | GET | dump 语义树 JSON（含 testTag/text/bounds/editableText） |
| `/click?testTag=` | POST | 调用 OnClick 语义 action |
| `/click?text=` | POST | 按文本定位并点击 |
| `/click?x=&y=` | POST | 按坐标点击 |
| `/input?testTag=` | POST | 调用 SetText 语义 action（输入文本） |
| `/longclick?testTag=` | POST | 调用 OnLongClick（Desktop 右键菜单） |
| `/longclick?x=&y=` | POST | 按坐标长按 |
| `/keypress?key=ESCAPE` | POST | 键盘事件 |
| `/screenshot` | GET | 截图 PNG |

### 语义树 JSON 结构

```json
{
  "id": 1,
  "testTag": "login.username",
  "text": "用户名",
  "editableText": "alice",
  "clickable": false,
  "bounds": [56.0, 224.0, 344.0, 288.0],
  "children": [...]
}
```

### 关键设计：遍历所有 semantics owners

DropdownMenu 的 Popup 注册独立的 semantics owner。如果只取第一个 owner（主窗口），Popup 内容（菜单项）就不可见。

```kotlin
private fun allRoots(windowId: String): List<SemanticsNode> {
    val owners = window.semanticsOwners
    return owners.mapNotNull { it.rootSemanticsNode }
    // 合并所有 owner（主窗口 + Popup）的 root
}
```

### window 参数

Desktop 多窗口模型下，子窗口操作必须指定 `window` 参数：

```
/click?testTag=profile.save&window=EditProfile
/input?testTag=password.old&window=ChangePassword
```

---

## 3. TestPeer（对端模拟）

TestPeer 是一个服务端测试工具，通过 TCP 协议模拟一个 IM 用户，用于双账户 E2E 测试（如搜索用户、发消息、接受好友申请）。

### 常用操作

```python
peer = TestPeer(project_root='.')

# 注册用户
pb = peer.register("userB")

# 查询 UID
uid = peer.whoami("userA", "password")

# 创建私聊
chat_id = peer.create_personal_chat("userB", target_uid)

# 发送消息
peer.send_msg("userB", chat_id, "Hello")

# 接受好友申请
peer.accept_latest_friend("userB")
```

### Python 封装

`tools/e2e/desktop_client.py` 封装了 TestHttpServer API：

```python
d = DesktopClient()
d.click_test_tag("login.submit")
d.input_test_tag("login.username", "alice")
d.longclick_xy(904, 558)
d.get_editable_text("chat.input")
```

---

## 4. Android 测试工具

Android 使用 uiautomator2（辅助功能服务）做 UI 操作，Python 封装在 `tools/e2e/android_client.py`。

核心方法：
- `click_id(resource_id)` — 按 resource-id 点击
- `input_to_id(resource_id, text)` — 输入文本
- `get_text_of_id(resource_id)` — 读取文本
- `wait_for_id(resource_id, timeout)` — 等待元素出现

---

## 5. 测试约束

### 约束 1：优先 testTag 定位

不用文字匹配。原因：
- UI 文案会变（"登录" → "登入"）
- Compose 语义树中文有编码问题
- testTag 是开发者主动标注的稳定标识

### 约束 2：每步验证不假设成功

SetText action 返回 200 但值可能没更新（Compose Desktop 已知问题）。必须读回 `editableText` 验证。

### 约束 3：Desktop 禁止 macOS 系统注入

不用 Robot 坐标注入（需辅助功能权限，极不稳定）。不用 AppleScript/osascript。只用 TestHttpServer 语义 Action。

### 约束 4：禁止固定脚本跑测试

多步骤封装成脚本必然因意外状态失败。每步单独执行 + 验证。

### 约束 5：辅助模式延迟 ≠ 失败

UI 操作后语义树可能有延迟（Compose recomposition 异步）。遇到状态不匹配时先重试等待，不要立即判定失败。

### 约束 6：子窗口操作带 window 参数

Desktop 多窗口模型下，操作 EditProfile/ChangePassword/SearchUsers 等子窗口必须指定 `window` 参数。

---

## 测试用例覆盖

测试用例清单（[test-cases.md](test-cases.md)）定义了 34 个测试用例（T01-T34），覆盖：
- T01-T05：认证（注册/登录/错误密码/校验）
- T06-T08：个人资料（查看/编辑/改密码）
- T09-T14：好友流程（搜索/查看/申请/接受/列表/删除）
- T15-T22：私聊（发起/发送/接收/预览/撤回/编辑/历史）
- T23-T26：富媒体（文件/语音/图片/转发）
- T27-T30：群聊（建群/消息/详情/列表）
- T31-T34：会话管理（置顶/草稿/搜索/未读）
