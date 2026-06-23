"""
TeamTalk Android E2E 测试客户端。

封装 uiautomator2，供 AI 驱动的交互式测试使用。

核心约束（踩坑总结，违反则测试失败）：
1. **必须 set_fastinput_ime(True)**：MIUI 安全键盘拦截系统输入法，
   fastinput IME 走剪贴板通道绕过。前置：设置→安全键盘→关闭 + 输入法切到 AdbKeyboard。
2. **优先使用 resource-id (testTag) 定位**：所有交互节点都有 testTag，映射为
   resource-id。使用 click_id / input_to_id 代替 click_text / input_to_field。
   Compose 的 testTag 在 uiautomator2 中表现为 resource-id。
3. **d(text=) 选择器对 Compose 无效**：Compose 节点有 text 但 u2 选择器匹配不到。
   用 dump_hierarchy + 正则解析 bounds → 坐标点击。
4. **辅助模式有延迟**：uiautomator dump 可能在状态转移过程中读到旧页面或中间态。
   操作后如果状态未变，等多几秒重试，不要立即判定失败。
5. **d(className="EditText") 定位 Compose TextField**：Compose TextField 映射为 EditText。
6. **send_keys 必须在 fastinput_ime 启用后**：否则被安全键盘拦截。

用法：
    from android_client import AndroidClient
    a = AndroidClient("<serial>")   # 或省略 serial 自动连接
    # 推荐：用 resource-id (testTag) 定位
    a.click_id("login.submit")
    a.input_to_id("login.username", "alice")
    # 回退：用文本定位（注意可能有多个同名文本）
    a.click_text("登录", skip=0)
    a.input_to_field("hello", field_index=0)
    a.screenshot("/tmp/screen.png")

前置：
- adb 连接真机，USB 调试 + USB 调试（安全设置）开启
- python3 -m uiautomator2 init -s <serial>
- MIUI 安全键盘关闭 + 输入法切到 com.github.uiautomator/.AdbKeyboard
"""
import os
import re
import time

try:
    import uiautomator2 as u2
except ImportError:
    u2 = None


class AndroidClient:
    """Android uiautomator2 客户端。

    所有文本定位走 dump_hierarchy + 正则解析 bounds（d(text=) 对 Compose 无效）。
    所有输入走 set_fastinput_ime(True) + send_keys（绕过 MIUI 安全键盘）。
    """

    PACKAGE = "com.virjar.tk.android"  # applicationId
    ACTIVITY = "com.virjar.tk.MainActivity"  # 主 Activity 全限定名

    def __init__(self, serial=None):
        if u2 is None:
            raise ImportError(
                "uiautomator2 未安装。pip install uiautomator2 && "
                "python3 -m uiautomator2 init -s <serial>"
            )
        self.d = u2.connect(serial) if serial else u2.connect()
        self.fastinput_ready = False

    def enable_fastinput(self):
        """启用 fastinput IME（剪贴板通道，绕过 MIUI 安全键盘）。

        前置：设置→安全键盘→关闭，输入法切到 com.github.uiautomator/.AdbKeyboard。
        必须在任何 input 操作前调用。
        """
        self.d.set_fastinput_ime(True)
        self.fastinput_ready = True

    # ── 交互操作 ──

    def click_text(self, text, timeout=5, skip=0, clickable_only=False):
        """点击含指定 text 的节点（坐标点击，绕过 d(text=) 失效）。

        优先找包含此 text 的可点击容器节点（Compose Button 的 text
        是独立非 clickable 节点，但被 clickable 容器包裹）。
        skip: 跳过前 N 个匹配。clickable_only: 只匹配 clickable=true 节点。
        返回 True/False。
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            h = self.dump_hierarchy()
            # 1) 先找所有含 text 的节点 bounds（可能非 clickable）
            text_pattern = re.compile(
                r'text="[^"]*' + re.escape(text) + r'[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
            )
            text_matches = text_pattern.findall(h)
            if not text_matches:
                time.sleep(0.5)
                continue
            if skip >= len(text_matches):
                return False
            tx1, ty1, tx2, ty2 = [int(x) for x in text_matches[skip]]

            # 2) 找包含此 text 节点的可点击容器（Compose button）的 bounds
            clickable_pattern = re.compile(
                r'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*>'
            )
            for m in clickable_pattern.finditer(h):
                cx1, cy1, cx2, cy2 = int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))
                if cx1 <= tx1 and cy1 <= ty1 and cx2 >= tx2 and cy2 >= ty2:
                    # 此 clickable 容器包含 text 节点，点击容器中心
                    cx, cy = (cx1 + cx2) // 2, (cy1 + cy2) // 2
                    self.d.click(cx, cy)
                    return True

            # 3) 回退：没找到容器，直接点 text 中心（text 本身可能是 clickable 的）
            cx, cy = (tx1 + tx2) // 2, (ty1 + ty2) // 2
            self.d.click(cx, cy)
            return True

        return False

    def input_to_field(self, value, field_index=0, clear_first=True):
        """向第 field_index 个 EditText 输入文本。

        必须先 enable_fastinput()。clear_first=True 时先清空字段。
        """
        if not self.fastinput_ready:
            self.enable_fastinput()
        fields = self.d(className="android.widget.EditText")
        if field_index >= len(fields):
            return False
        field = fields[field_index]
        field.click()
        time.sleep(0.3)
        if clear_first:
            self.d.clear_text()
            time.sleep(0.1)
        self.d.send_keys(value)
        time.sleep(0.2)
        return True

    def click_field_then_input(self, value, field_index=0):
        """点击字段聚焦 + 输入（等同 input_to_field，语义更明确）。"""
        return self.input_to_field(value, field_index)

    def press_back(self):
        """按返回键。"""
        self.d.press("back")

    # ── resource-id 定位（推荐方式） ──

    def find_by_id(self, resource_id, timeout=5):
        """通过 resource-id (testTag) 查找节点，返回 bounds [x1,y1,x2,y2] 或 None。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            h = self.dump_hierarchy()
            pattern = re.compile(
                r'resource-id="' + re.escape(resource_id) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
            )
            m = pattern.search(h)
            if m:
                return [int(m.group(i)) for i in range(1, 5)]
            time.sleep(0.5)
        return None

    def click_id(self, resource_id, timeout=5):
        """通过 resource-id (testTag) 点击节点。返回 True/False。"""
        bounds = self.find_by_id(resource_id, timeout=timeout)
        if bounds is None:
            return False
        cx, cy = self._center(bounds)
        self.d.click(cx, cy)
        return True

    def input_to_id(self, resource_id, value, clear_first=True):
        """向 resource-id (testTag) 对应的 TextField 输入文本。
        
        通过 dump_hierarchy 找到节点 bounds，点击聚焦，然后 send_keys 输入。
        """
        if not self.fastinput_ready:
            self.enable_fastinput()
        bounds = self.find_by_id(resource_id, timeout=3)
        if bounds is None:
            return False
        cx, cy = self._center(bounds)
        self.d.click(cx, cy)
        time.sleep(0.3)
        if clear_first:
            self.d.clear_text()
            time.sleep(0.1)
        self.d.send_keys(value)
        time.sleep(0.2)
        return True

    def wait_for_id(self, resource_id, timeout=10):
        """等待 resource-id 出现，超时返回 False。"""
        return self.find_by_id(resource_id, timeout=timeout) is not None

    def has_id(self, resource_id, timeout=3):
        """当前屏幕是否包含指定 resource-id。"""
        return self.find_by_id(resource_id, timeout=timeout) is not None

    def get_text_of_id(self, resource_id, timeout=3):
        """获取 resource-id 节点的 text 内容，无此节点返回 None。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            h = self.dump_hierarchy()
            pattern = re.compile(
                r'resource-id="' + re.escape(resource_id) + r'"[^>]*text="([^"]*)"[^>]*bounds='
            )
            m = pattern.search(h)
            if m:
                return m.group(1)
            time.sleep(0.5)
        return None

    # ── UI 树查询 ──

    def dump_hierarchy(self):
        """dump 当前 UI 层次结构 XML 字符串。"""
        return self.d.dump_hierarchy()

    def find_bounds(self, text, timeout=5):
        """在 UI 树中找含 text 的节点，返回 bounds [x1,y1,x2,y2] 或 None。

        正则解析 Compose 语义树里的 text + bounds 属性。
        Compose 的 text 可能是合并节点，用模糊匹配（text in 节点文本）。
        """
        all_bounds = self.find_all_bounds(text, timeout=timeout)
        return all_bounds[0] if all_bounds else None

    def find_all_bounds(self, text, timeout=5, clickable_only=False):
        """返回所有匹配 text 的节点 bounds 列表（按 XML 出现顺序）。

        clickable_only: True 时只匹配 clickable="true" 节点。
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            h = self.dump_hierarchy()
            if clickable_only:
                pattern = re.compile(
                    r'text="[^"]*' + re.escape(text) + r'[^"]*"[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
                )
            else:
                pattern = re.compile(
                    r'text="[^"]*' + re.escape(text) + r'[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
                )
            matches = pattern.findall(h)
            if matches:
                return [[int(m[i]) for i in range(4)] for m in matches]
            time.sleep(0.5)
        return []

    def has_text(self, text, timeout=3):
        """当前屏幕是否包含指定文本。"""
        return self.find_bounds(text, timeout=timeout) is not None

    def wait_for(self, text, timeout=10, interval=0.5):
        """等待指定文本出现，超时返回 False。"""
        return self.find_bounds(text, timeout=timeout) is not None

    def screen_texts(self):
        """提取当前屏幕所有 text（List[str]）。"""
        h = self.dump_hierarchy()
        return re.findall(r'text="([^"]*)"[^>]*bounds=', h)

    @staticmethod
    def _center(bounds):
        """bounds [x1,y1,x2,y2] → 中心 (cx, cy)。"""
        return (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2

    # ── 截图 ──

    def screenshot(self, path):
        """截图保存到 path。"""
        self.d.screenshot(path)
        return os.path.getsize(path)
