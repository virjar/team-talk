# TeamTalk E2E 测试工具

AI 驱动的交互式 E2E 测试辅助模块。固化测试客户端代码，避免每次测试重写、踩语法坑。

完整测试流程与约束见 [`doc/06-testing/`](../../doc/06-testing/)。

## 文件说明

| 文件 | 用途 |
|------|------|
| `desktop_client.py` | Desktop TestHttpServer（:18080）客户端封装。语义 OnClick/SetText、坐标点击、语义树查询、截图 |
| `android_client.py` | Android uiautomator2 客户端封装。fastinput_ime、bounds 正则解析、EditText 定位 |
| `peer.py` | TestPeer 对端协同封装。注册 B、接受好友、发消息/图片/语音/视频 |
| `run_checklist.py` | T01-T34 check list 驱动框架。结果记录、汇总表、Markdown 导出 |

## 快速开始

### Desktop 测试

```bash
# 1. 启动 Desktop（内置测试 HTTP 服务 :18080）
./gradlew :desktop:runDemo &

# 2. 用固化模块驱动测试
cd tools/e2e
python3 -c "
from desktop_client import DesktopClient
from run_checklist import Checklist

d = DesktopClient()
d.wait_ping()  # 等服务就绪

cl = Checklist('Desktop')
# AI 根据屏幕状态决策每步操作
d.click_text('没有账号？注册')
d.input_text('用户名', 'test123')
# ... 操作 ...
cl.record('T01', passed=True, note='test123 注册成功')

cl.summary()
"
```

### Android 测试

```python
from android_client import AndroidClient

a = AndroidClient('<serial>')  # adb devices 查 serial
a.enable_fastinput()            # 必须！绕过 MIUI 安全键盘

a.click_text('登录')
a.input_to_field('alice', field_index=0)   # 第 1 个 EditText
a.input_to_field('pass123', field_index=1)  # 第 2 个 EditText
```

### 对端协同（TestPeer）

```python
from peer import TestPeer

peer = TestPeer(project_root='/path/to/team-talk')
info = peer.register('testB01')        # 注册 B
print(info)  # PeerInfo(username='zt-b-testB01', uid='abc-123')

peer.accept_latest_friend(info.username)  # B 接受 A 的好友申请
peer.send_msg(info.username, chat_id, 'hello from B')
```

## 关键约束（踩坑总结）

这些约束固化在代码注释中，违反会导致测试失败：

### Desktop

1. **`findInTree` 精确匹配**：placeholder 带省略号（`输入消息...`）必须完整匹配
2. **图标无 text**：ListHeader 图标只有 contentDescription，语义树不含，必须用 `click_xy()` 或 `click_icon_near()` 坐标点击
3. **子窗口语义**：`?window=<id>` 指定子窗口，UserProfile 可能渲染在 SearchUsers 窗口内（SubWindow 内部导航）

### Android

1. **必须 `enable_fastinput()`**：MIUI 安全键盘拦截系统输入，fastinput 走剪贴板绕过
2. **`d(text=)` 对 Compose 无效**：用 `find_bounds()` 正则解析 bounds 坐标点击
3. **`send_keys` 必须在 fastinput 后**：否则被拦截

## 与测试指南的关系

`doc/06-testing/` 定义测试哲学（AI 交互式驱动、禁用固定脚本）和 check list。
本模块是执行工具——AI 用这些客户端做决策式操作，而非跑死脚本。
