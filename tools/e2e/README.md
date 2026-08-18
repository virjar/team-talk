# E2E 客户端工具

本目录提供真实客户端验收的轻量驱动封装。测试分层、发布门禁和业务覆盖范围分别见[测试与验收](../../doc/09-testing/README.md)与[业务场景目录](../../doc/09-testing/scenario-catalog.md)。

## 工具

| 文件 | 用途 |
|---|---|
| `desktop_client.py` | 调用 Desktop 内置测试 HTTP 服务，读取语义树、执行语义动作和截图 |
| `android_client.py` | 通过 uiautomator2 操作真实 Android Debug APK |
| `peer.py` | 驱动第二个真实协议账户，用于好友、双向消息和群组场景 |

这些工具只负责执行和观察，不定义产品预期。预期行为来自业务场景、客户端规范和协议契约。

## Desktop

在一个终端以前台方式启动应用：

```bash
./gradlew :desktop:run
```

另一个终端使用客户端：

```python
from desktop_client import DesktopClient

d = DesktopClient()
assert d.wait_ping()
assert d.input_test_tag("login.username", "alice")[0]
assert d.input_test_tag("login.password", "pass123")[0]
assert d.click_test_tag("login.submit")[0]
assert d.wait_for("会话")
d.screenshot("/tmp/teamtalk-desktop.png")
```

优先使用 `click_test_tag` 和 `input_test_tag`。文本定位适合探索，坐标只在没有语义动作时诊断性使用。每次输入后可用 `get_editable_text` 读回值；每次点击后应等待并断言目标状态。

子窗口操作必须传窗口 ID：

```python
d.input_test_tag("profile.name", "新名字", window="sub-EditProfile")
d.click_test_tag("profile.save", window="sub-EditProfile")
d.keypress("ESCAPE", window="sub-EditProfile")
```

完整端点、窗口模型和状态循环见[Desktop 自动化](../../doc/09-testing/desktop-automation.md)，稳定标签见[测试选择器](../../doc/10-reference/test-selectors.md)。

## Android

前置条件是 adb 可访问真机或模拟器，并已初始化 uiautomator2。部分厂商系统需要关闭安全键盘并允许 USB 调试的安全输入能力。

```python
from android_client import AndroidClient

a = AndroidClient("<serial>")
a.enable_fastinput()
assert a.input_to_id("login.username", "alice")
assert a.input_to_id("login.password", "pass123")
assert a.click_id("login.submit")
assert a.wait_for_id("main.home")
a.screenshot("/tmp/teamtalk-android.png")
```

Compose 的 `testTag` 映射为 Android resource id。优先使用 `*_id` 方法；文本、EditText 序号和坐标是兼容回退，不应成为稳定用例的主要定位方式。

## 第二账户

`peer.py` 调用项目的 TestPeer 能力，让第二账户通过公共协议与测试部署交互：

```python
from peer import TestPeer

peer = TestPeer(project_root="/path/to/team-talk")
other = peer.register("scenario-peer")
peer.accept_latest_friend(other.username)
peer.send_msg(other.username, chat_id, "hello from peer")
```

测试不能直接写数据库制造联系人、消息或群成员状态；否则绕过的权限、事件和缓存路径恰好是业务验收需要覆盖的部分。

## 操作约束

1. 先读取当前状态，再执行一个操作，然后重新读取并断言。
2. 网络和 Compose 重组使用有限状态等待，不用固定长休眠串联流程。
3. Desktop 任务窗口携带 `window`，主窗口弹窗与群设置抽屉仍使用 `main`。
4. 输入后读回字段，防止接口成功但 UI 状态未更新。
5. 截图前确认语义状态，避免把加载中、旧窗口或遮挡画面当结果。
6. 账号、消息和文件使用场景前缀，保证重跑与并行测试可区分。
