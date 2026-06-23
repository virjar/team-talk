"""
TeamTalk Desktop E2E 测试客户端。

封装 TestHttpServer（端口 18080）的语义 Action 调用，供 AI 驱动的交互式测试使用。
所有操作走进程内 Compose 语义 OnClick/SetText，零 macOS 辅助功能权限依赖。

用法：
    from desktop_client import DesktopClient
    d = DesktopClient()           # 默认 http://127.0.0.1:18080
    d.click_text("登录")
    d.input_text("用户名", "alice")
    d.wait_for("会话", timeout=8)

前置：先 ./gradlew :desktop:runDemo 启动 Desktop（内置测试 HTTP 服务）。
"""
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 18080


class DesktopClient:
    """Desktop TestHttpServer 客户端。

    封装语义 Action（OnClick/SetText）+ 语义树查询 + 截图。
    所有方法对 window 参数透明：None=主窗口，字符串=子窗口 ID（如 SearchUsers）。
    """

    def __init__(self, host=DEFAULT_HOST, port=DEFAULT_PORT, timeout=15):
        self.base = f"http://{host}:{port}"
        self.timeout = timeout

    # ── 底层 HTTP ──

    def _req(self, path, params=None, data=None, method="GET"):
        url = self.base + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        body = None
        if data is not None:
            body = data.encode("utf-8") if isinstance(data, str) else data
        req = urllib.request.Request(url, data=body, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return r.status, r.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", errors="replace")
        except Exception as e:
            return 0, str(e)

    def ping(self):
        """验证测试服务可用。返回 True/False。"""
        code, _ = self._req("/ping")
        return code == 200

    # ── 交互操作（语义 Action）──

    def click_text(self, text, window=None):
        """通过语义文本定位并点击（On​Click action）。

        注意：findInTree 是**精确匹配**——placeholder「输入消息...」带省略号，
        必须传完整字符串，不能只传「输入消息」。

        返回 (success: bool, response_body: str)
        """
        params = {"text": text}
        if window:
            params["window"] = window
        code, body = self._req("/click", params, method="POST")
        return code == 200, body

    def click_test_tag(self, tag, window=None):
        """通过 testTag 定位并点击。比 text 更稳定（不受文案改动影响）。"""
        params = {"testTag": tag}
        if window:
            params["window"] = window
        code, body = self._req("/click", params, method="POST")
        return code == 200, body

    def click_xy(self, x, y, window=None):
        """坐标点击。TestHttpServer 内部先找该坐标的语义可点击节点再调 OnClick，
        仅在语义 Action 不可用时 fallback Robot（需权限）。

        用途：图标按钮只有 contentDescription 无 text，语义树不含 contentDescription，
        无法用 click_text，必须用 bounds 中心坐标点击。
        """
        params = {"x": x, "y": y}
        if window:
            params["window"] = window
        code, body = self._req("/click", params, method="POST")
        return code == 200, body

    def longclick_text(self, text, window=None):
        """长按指定文本节点（触发 combinedClickable.onLongClick，如消息长按菜单）。"""
        params = {"text": text}
        if window:
            params["window"] = window
        code, body = self._req("/longclick", params, method="POST")
        return code == 200, body

    def longclick_test_tag(self, tag, window=None):
        """长按指定 testTag 节点（触发 combinedClickable.onLongClick）。"""
        params = {"testTag": tag}
        if window:
            params["window"] = window
        code, body = self._req("/longclick", params, method="POST")
        return code == 200, body

    def longclick_xy(self, x, y, window=None):
        """右键/长按指定坐标的节点（Desktop 右键弹菜单）。"""
        params = {"x": x, "y": y}
        if window:
            params["window"] = window
        code, body = self._req("/longclick", params, method="POST")
        return code == 200, body

    def click_icon_near(self, anchor_text, offset_index=0, window=None):
        """点击某文本节点附近第 offset_index 个 clickable 节点。

        ListHeader 图标（搜索/建群/申请）无 text，但位于标题文本右侧。
        用 anchor_text 定位标题，取其后第 N 个 clickable 节点的 bounds 中心点击。
        offset_index=0 紧邻标题右侧第一个图标。
        """
        clickables = self._clickable_bounds_after(anchor_text, window)
        if offset_index < len(clickables):
            l, top, r, b = clickables[offset_index]
            return self.click_xy((l + r) / 2, (top + b) / 2, window)
        return False, "no clickable node found near anchor"

    def _clickable_bounds_after(self, anchor_text, window=None):
        """返回语义树中，出现在 anchor_text 节点**之后**的 clickable 节点 bounds 列表。"""
        flat = self._flat_nodes(window)
        found_anchor = False
        result = []
        for node in flat:
            if not found_anchor:
                if anchor_text in (node.get("text") or ""):
                    found_anchor = True
                continue
            if node.get("clickable"):
                result.append(node["bounds"])
        return result

    def input_text(self, label, value, window=None):
        """定位 label 字段，用 SetText action 设置 value。

        label 是 OutlinedTextField 的 label 文本（如「用户名」「密码」「输入消息...」）。
        注意精确匹配（见 click_text 的说明）。
        """
        params = {"text": label}
        if window:
            params["window"] = window
        code, body = self._req("/input", params, data=value, method="POST")
        return code == 200, body

    def input_test_tag(self, tag, value, window=None):
        """通过 testTag 定位 TextField，用 SetText action 设置 value。

        比 input_text 更可靠（不受 label 文案变化影响）。
        推荐用法：d.input_test_tag("login.username", "alice")
        """
        params = {"testTag": tag}
        if window:
            params["window"] = window
        code, body = self._req("/input", params, data=value, method="POST")
        return code == 200, body

    def get_editable_text(self, test_tag, window=None):
        """读取指定 testTag TextField 的 editableText 值。返回字符串或 None。"""
        for node in self._flat_nodes(window):
            if node.get("testTag") == test_tag:
                return node.get("editableText")
        return None

    def keypress(self, key, window=None):
        """发送按键（如 ESCAPE）。用于关闭子窗口/面板。"""
        params = {"key": key}
        if window:
            params["window"] = window
        code, body = self._req("/keypress", params, method="POST")
        return code == 200, body

    # ── 语义树查询 ──

    def semantics(self, window=None):
        """获取语义树 JSON（dict）。window 指定子窗口 ID。"""
        params = {"window": window} if window else None
        code, body = self._req("/semantics", params)
        if code != 200:
            return {}
        try:
            return json.loads(body)
        except Exception:
            return {}

    def _flat_nodes(self, window=None):
        """语义树扁平化为节点列表（带 bounds/text/clickable），按深度优先顺序。"""
        result = []

        def walk(n):
            if isinstance(n, dict):
                result.append(n)
                for c in n.get("children", []):
                    walk(c)
            elif isinstance(n, list):
                for item in n:
                    walk(item)

        walk(self.semantics(window))
        return result

    def screen_texts(self, window=None):
        """提取当前窗口所有可见文本（List[str]）。"""
        return [n["text"] for n in self._flat_nodes(window) if n.get("text")]

    def has_text(self, text, window=None):
        """当前窗口是否包含指定文本。"""
        return text in self.screen_texts(window)

    def find_node(self, text=None, window=None):
        """查找含指定 text 的节点，返回 bounds [l,t,r,b] 或 None。"""
        for node in self._flat_nodes(window):
            if text is not None and text in (node.get("text") or ""):
                return node.get("bounds")
        return None

    def find_clickables(self, window=None):
        """返回所有 clickable 节点的 bounds 列表。"""
        return [n["bounds"] for n in self._flat_nodes(window) if n.get("clickable")]

    # ── 等待与截图 ──

    def wait_for(self, text, timeout=10, interval=0.5, window=None):
        """等待指定文本出现，超时返回 False。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.has_text(text, window):
                return True
            time.sleep(interval)
        return False

    def wait_ping(self, timeout=60, interval=2):
        """等待测试服务就绪（app 启动后调用）。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.ping():
                return True
            time.sleep(interval)
        return False

    def screenshot(self, path):
        """截图保存到 path，返回文件大小（字节）。"""
        urllib.request.urlretrieve(self.base + "/screenshot", path)
        return os.path.getsize(path)
