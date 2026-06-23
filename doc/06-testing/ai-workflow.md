# AI 驱动测试工作流

> 测试的具体操作方法：Desktop TestHttpServer、Android uiautomator2、TestPeer 对端脚本。

## 环境准备

### 服务端

- demo 站点 `im.virjar.com` 在线
- TCP 5100 可达：`python3 -c "import socket; s=socket.socket(); s.settimeout(3); s.connect(('im.virjar.com',5100)); print('OK')"`

### Android 真机

```
设置 → 开发者选项
  ├── USB 调试 ✅
  ├── USB 调试（安全设置）✅  ← MIUI 必须！否则点击注入报 SecurityException
  └── USB 安装 ✅
```

**uiautomator2**：
```bash
pip install uiautomator2
python3 -m uiautomator2 init -s <serial>
```

**MIUI 安全键盘**（两者**必须同时**配置，缺一不可）：
1. 设置 → 安全键盘 → **关闭**（否则安全键盘拦截输入）
2. 输入法切到 `com.github.uiautomator/.AdbKeyboard`（fastinput IME 走剪贴板通道）

**APK**：
```bash
./gradlew :android:assembleDemoDebug       # 自动配置 im.virjar.com
adb install -r android/build/outputs/apk/demo/debug/android-demo-debug.apk
```

### Desktop

```bash
./gradlew :desktop:runDemo   # 启动 Desktop（内置测试 HTTP 服务 :18080）
```

---

## Desktop（TestHttpServer）

Desktop 应用内置测试 HTTP 服务（端口 18080），所有操作通过 **Compose 语义 Action** 执行，
**全程不需要 macOS 辅助功能权限**。

```bash
./gradlew :desktop:runDemo           # 启动（含测试 HTTP 服务）
curl http://localhost:18080/semantics  # 验证
```

**核心约束：必须使用语义 Action，禁用 Robot 回退路径。**

| 端点 | 正确用法 | 错误用法 |
|------|---------|---------|
| `POST /click` | `?text=登录` 语义 OnClick | ~~`?x=400&y=600`~~ Robot 坐标（需权限） |
| `POST /input` | `?text=用户名` + body `zddesk` 语义 SetText | ~~`?field=...`~~ 参数不存在 |

语义 Action 走进程内 `SemanticsActions.OnClick.invoke()` / `SetText.invoke()`，
零外部权限依赖。Robot 回退仅在语义 Action 不可用时触发，需 macOS 辅助功能权限。

**固化测试客户端**：`tools/e2e/desktop_client.py` 封装了上述端点，直接复用，勿每次重写：
```python
import sys; sys.path.insert(0, 'tools/e2e')
from desktop_client import DesktopClient

d = DesktopClient()          # 默认 http://127.0.0.1:18080
d.click_test_tag("login.submit")     # 语义 OnClick（推荐）
d.input_test_tag("login.username", "alice")  # 语义 SetText
d.longclick_xy(904, 558)     # 右键长按（消息菜单）
d.get_editable_text("chat.input")  # 读回验证
d.screen_texts()             # dump 语义树文本
d.screenshot("/tmp/screen.png")
```

> 注意：`findInTree` **精确匹配** text，placeholder 带省略号（如「输入消息...」）需完整匹配。
> 图标按钮只有 contentDescription 无 text，语义树不含 contentDescription，必须用 `click_xy()` 坐标点击。

### Desktop 多窗口约束（最重要）

Desktop 使用**子窗口模式**渲染编辑资料/修改密码/搜索用户/用户资料/建群/群详情等页面。
每个子窗口是独立的 Compose 窗口，有自己的语义树。**所有交互必须指定正确的 `window` 参数**，
否则操作会落到主窗口（无效或误操作）。

**子窗口名称** = `SubScreen` 枚举名：`EditProfile` / `ChangePassword` / `SearchUsers` / `UserProfile` / `CreateGroup` / `GroupDetail` / `InviteMembers` / `InviteLinks`

**关键规则**：
1. 点击主窗口的元素（如「编辑资料」）前 → 先确认主窗口内容：`d.screen_texts()`
2. 点击后子窗口打开 → **立即切到子窗口语义树**：`d.screen_texts(window='EditProfile')`
3. 所有子窗口内的操作 → 全部带 `window='EditProfile'`
4. 操作完成后必须**关闭子窗口**：`d.keypress('ESCAPE', window='EditProfile')`
5. 验证子窗口已关闭：`d.screen_texts(window='EditProfile')` 应返回空或主窗口内容

```python
# 正确流程：编辑资料
d.click_text('编辑资料')                       # 主窗口点击
time.sleep(0.5)
d.input_test_tag('profile.name', '新名字', window='EditProfile')  # 子窗口输入
d.click_test_tag('profile.save', window='EditProfile')            # 子窗口点击
d.keypress('ESCAPE', window='EditProfile')             # 关闭子窗口

# 错误：不带 window 参数 → 操作落到主窗口，什么都不发生
d.click_text('保存')  # ❌ 主窗口没有「保存」按钮，点了无效
```

---

## Android（uiautomator2 + adb）

**核心约束：优先使用高层次 API，禁用底层 `send_keys`。**

| 方法 | 可用 | 说明 |
|------|------|------|
| `d.set_fastinput_ime(True)` + `d.send_keys()` | ✅ **推荐** | 启用 fastinput IME 后用 send_keys（走剪贴板，绕过 MIUI 安全键盘） |
| `d(className="EditText")[i].click()` | ✅ | 定位 Compose TextField |
| `d(text="xxx")` 选择器 | ❌ | Compose 节点有 text 但 u2 选择器匹配不到 |
| `d.send_keys()` 无 fastinput | ❌ | MIUI 安全键盘拦截 |

**固化测试客户端**：`tools/e2e/android_client.py` 封装了上述约束，直接复用：
```python
import sys; sys.path.insert(0, 'tools/e2e')
from android_client import AndroidClient

a = AndroidClient('<serial>')   # 或省略 serial 自动连接
a.enable_fastinput()            # 必须！绕过 MIUI 安全键盘

a.click_id("login.submit")      # 按 resource-id 点击
a.input_to_id("login.username", "alice")  # 按 resource-id 输入
a.has_text("会话")
a.screenshot("/tmp/screen.png")
```

---

## 对端脚本（TestPeer）

底层是 gradle 任务，`tools/e2e/peer.py` 封装了调用 + 结果解析：
```python
import sys; sys.path.insert(0, 'tools/e2e')
from peer import TestPeer

peer = TestPeer(project_root='/path/to/team-talk')
info = peer.register('testB01')          # 注册 B，返回 PeerInfo(username, uid)
peer.accept_latest_friend(info.username)  # B 接受 A 的好友申请
peer.send_msg(info.username, chat_id, 'hello from B')
peer.send_image(info.username, chat_id, '/tmp/test.png')
```

底层等价命令（`peer.py` 封装的就是这些）：
```bash
./gradlew :server:test --tests "com.virjar.tk.e2e.TestPeer.registerPeer" \
  -Dtk.e2e.remote=true -Dpeer.arg=<suffix>
./gradlew :server:test --tests "com.virjar.tk.e2e.TestPeer.acceptLatestFriend" \
  -Dtk.e2e.remote=true -Dpeer.username=<B>
./gradlew :server:test --tests "com.virjar.tk.e2e.TestPeer.sendMsgAsB" \
  -Dtk.e2e.remote=true -Dpeer.username=<B> -Dpeer.arg=<chatId>:<text>
```

执行后 `peer.py` 自动解析 `server/build/test-results/test/*TestPeer*.xml` 的 `<system-out>` 提取 uid/chatId。

---

## 验证手段

```bash
# 服务端 trace 日志
ssh root@im.virjar.com 'tail -20 /opt/teamtalk/data/logs/traces/trace.log'

# 真机 logcat
adb logcat -d | grep ImClient

# Desktop 本地日志
cat ~/.teamtalk/desktop/logs/app-$(date +%Y-%m-%d).log

# 截图
adb exec-out screencap -p > screen.png     # Android
curl http://localhost:18080/screenshot > screen.png  # Desktop
```
