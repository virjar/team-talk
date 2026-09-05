# E2E 客户端工具

本目录提供真实客户端验收的轻量驱动封装。测试分层、发布门禁和业务覆盖范围分别见[测试与验收](../../doc/09-testing/README.md)与[业务场景目录](../../doc/09-testing/scenario-catalog.md)。

## 工具

| 文件 | 用途 |
|---|---|
| `desktop_client.py` | 调用 Desktop 内置测试 HTTP 服务，读取语义树、执行语义动作和截图 |
| `android_client.py` | 通过 uiautomator2 操作真实 Android Debug APK |
| `peer.py` | 驱动第二个真实协议账户，用于好友、双向消息和群组场景 |
| `document_fixture.py` | 为 Desktop/Android 文档 UI 验收幂等生成或归档 150 篇三层真实文档 |

这些工具只负责执行和观察，不定义产品预期。预期行为来自业务场景、客户端规范和协议契约。

## Desktop

在一个终端以前台方式启动应用：

```bash
./gradlew :client:desktop:run
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

## 文档树验收夹具

`document_fixture.py` 只通过 TeamTalk 公共客户端 SDK/RPC 访问 `gradle/deployment.json`
选定的部署，不读写服务端数据库。它创建 6 个一级文档，每个包含 4 个二级文档，
每个二级文档再包含 5 个三级文档，共 `6 + 24 + 120 = 150` 篇。一、二级节点都有
非空正文，因此能验证“同一文档同时承载内容和子页”。

凭据和夹具状态必须位于仓库外的一个专用目录。工具要求目录权限精确为 `0700`，
`account.properties` 权限精确为 `0600`，并拒绝符号链接、硬链接和未知字段。
已有测试账号可用交互式入口创建账号文件，密码不会进入 shell history、进程参数或工具输出：

```bash
python3 scripts/e2e/document_fixture.py init-account \
  --state-dir /private/tmp/teamtalk-document-fixture
```

无人值守验收可让工具通过 Python `secrets` 模块生成合规的随机用户名和密码；命令只输出
账号文件路径，不输出凭据值：

```bash
python3 scripts/e2e/document_fixture.py init-account \
  --state-dir /private/tmp/teamtalk-document-fixture \
  --generate
```

`seed` 只登录、不隐式注册账号。交互模式填写的账号必须已存在；`--generate` 生成新凭据后，
验收驱动需要先用 `load_account_credentials` 读取同一文件并走一次真实 TeamTalk 注册 UI，之后
Desktop、Android 和夹具工具始终复用该账号。明确核对目标后生成夹具：

```bash
python3 scripts/e2e/document_fixture.py seed \
  --state-dir /private/tmp/teamtalk-document-fixture \
  --confirm-target im.virjar.com:5100
```

首次执行会先原子写入不含凭据的 `document-fixture.json`，再连接服务器。夹具 ID、
空间 ID、文档 ID 和归档 `operationId` 都由该 manifest 稳定派生；进程中断后用同一状态
目录重跑 `seed` 会通过服务端幂等创建语义补齐未完成节点。

Desktop 与 Android 要从同一个账号文件读取用户名和密码，分别走真实登录 UI。
Python 验收脚本可导入不泄露 `repr` 的读取器：

```python
from document_fixture import load_account_credentials

account = load_account_credentials("/private/tmp/teamtalk-document-fixture")
# 只将 account.username / account.password 传给 login.username / login.password，不打印对象或字段。
```

两个客户端的本地缓存相互独立。断网验收前，每个客户端都要在线打开文档空间、展开
所需分支并打开代表性父文档和叶文档；夹具生成进程的内存缓存不会为 UI 客户端预热。

验收结束后使用同一状态目录和同一目标确认归档空间：

```bash
python3 scripts/e2e/document_fixture.py archive \
  --state-dir /private/tmp/teamtalk-document-fixture \
  --confirm-target im.virjar.com:5100
```

`archive` 始终重用 manifest 中的 `operationId`；完成后保留 manifest 作为验收记录。
确认不再需要 Desktop/Android 重登录或夹具清理后，再删除本机私有状态目录。

## 操作约束

1. 先读取当前状态，再执行一个操作，然后重新读取并断言。
2. 网络和 Compose 重组使用有限状态等待，不用固定长休眠串联流程。
3. Desktop 任务窗口携带 `window`，主窗口弹窗与群设置抽屉仍使用 `main`。
4. 输入后读回字段，防止接口成功但 UI 状态未更新。
5. 截图前确认语义状态，避免把加载中、旧窗口或遮挡画面当结果。
6. 账号、消息和文件使用场景前缀，保证重跑与并行测试可区分。
