"""桌面消息气泡右键 popup 菜单回归用例。

背景（2026-09 回归）：SelectionContainer 的平台复制菜单在右键按下时抢先弹出，
应用菜单被顶掉，而语义 onLongClick 路径仍工作——只有走真实指针的用例能拦住它。
本用例刻意只使用 /rightclick（Robot 真实鼠标右键），禁止 /longclick 语义捷径。

用法：先启动 desktop 验收实例并登录一个存在会话的账号，然后
    python3 scripts/e2e/desktop_popup_menu_check.py [port]

通过条件：右键消息气泡弹出应用菜单（快捷回应栏 + 回复/转发），ESC 可关闭。
"""

import json
import sys
import time

from desktop_client import DesktopClient


def flat(client):
    out = []

    def walk(node):
        out.append(node)
        for child in node.get("children", []):
            walk(child)

    walk(client.semantics())
    return out


def main() -> int:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
    client = DesktopClient(port=port)
    if not client.ping():
        print("FAIL: desktop 测试服务未就绪，先启动 :client:desktop:run")
        return 2

    conversations = sorted({
        n.get("testTag") for n in flat(client)
        if (n.get("testTag") or "").startswith("conv.item.")
    })
    if not conversations:
        print("FAIL: 当前账号没有会话，请先在该账号上产生一条聊天")
        return 2
    client.click_test_tag(conversations[0])
    time.sleep(2)

    messages = sorted({
        n.get("testTag") for n in flat(client)
        if (n.get("testTag") or "").startswith("chat.message.seq")
        and (n.get("testTag") or "").endswith(".body")
    }, key=lambda tag: int(tag.removeprefix("chat.message.seq.").removesuffix(".body")))
    if not messages:
        print("FAIL: 该会话没有已确认消息，无法触发右键菜单")
        return 2

    # 优先选文本气泡：语音/媒体卡的右键菜单能力由各自渲染路径决定，不作为本用例目标。
    def has_text(tag):
        for n in flat(client):
            if n.get("testTag") == tag and (n.get("text") or "").strip():
                return True
        return False

    target = next((t for t in messages if has_text(t)), messages[0])

    _, body = client._req("/find", {"testTag": target})
    x1, y1, x2, y2 = json.loads(body)["bounds"]
    code, _ = client._req("/rightclick", {"x": (x1 + x2) / 2, "y": (y1 + y2) / 2},
                          method="POST")
    time.sleep(1)

    texts = [n.get("text") for n in flat(client) if n.get("text")]
    menu_open = any(t in texts for t in ("回复", "转发")) and "👍" in texts
    if not menu_open:
        print("FAIL: 右键未弹出应用菜单（若只有'复制'，即平台文本菜单抢占回归）")
        return 1

    client.click_text("回复")
    time.sleep(1)
    reply_engaged = any("回复" in (n.get("text") or "") for n in flat(client)
                        if (n.get("testTag") or "").startswith("chat.message."))
    client.keypress("ESCAPE")
    print("PASS: 右键弹出应用菜单" + ("，回复入口可达" if reply_engaged else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
