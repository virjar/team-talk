"""
TeamTalk 对端协同（TestPeer）封装。

TestPeer 是 Kotlin 测试（server 模块），扮演账号 B 与 UI 操作的账号 A 协同。
本模块封装 gradle 调用 + 解析 system-out 提取 uid/chatId/username。

用法：
    from peer import TestPeer
    peer = TestPeer(project_root="/path/to/team-talk")

    # 注册对端账号 B
    info = peer.register("testB01")
    print(info)  # PeerInfo(username="zt-b-testB01", uid="abc-123")

    # B 接受 A 的好友申请
    peer.accept_latest_friend(info.username)

    # B 给 A 发消息（需要 chatId）
    peer.send_msg(info.username, chat_id, "hello from B")

前置：demo 站点 im.virjar.com 在线，TCP 5100 可达。
"""
import os
import re
import subprocess

# TestPeer 默认密码（RemoteDemoSupport.registerUser 固定用 password123）
DEFAULT_PASSWORD = "password123"


class PeerInfo:
    """对端账号信息。"""

    def __init__(self, username, uid, password=DEFAULT_PASSWORD):
        self.username = username
        self.uid = uid
        self.password = password

    def __repr__(self):
        return f"PeerInfo(username={self.username!r}, uid={self.uid!r})"


class TestPeer:
    """TestPeer gradle 任务封装。

    所有方法执行 ./gradlew :server:test --tests TestPeer.<method>，
    解析测试结果 XML 的 system-out 提取结构化输出。
    """

    def __init__(self, project_root, remote=True):
        """
        project_root: team-talk 项目根目录（含 settings.gradle.kts）。
        remote: 是否连远程 demo（-Dtk.e2e.remote=true），默认 True。
        """
        self.project_root = os.path.abspath(project_root)
        self.remote = remote

    def _run(self, method, extra_props=None):
        """执行 TestPeer.<method>，返回完整 stdout（含 ===标记=== 输出）。

        extra_props: 额外 -D 系统属性 dict，如 {"peer.username": "xxx"}。
        """
        cmd = [
            os.path.join(self.project_root, "gradlew"), ":server:test",
            "--tests", f"com.virjar.tk.e2e.TestPeer.{method}",
            "-q",
        ]
        if self.remote:
            cmd.append("-Dtk.e2e.remote=true")
        if extra_props:
            for k, v in extra_props.items():
                cmd.append(f"-D{k}={v}")

        result = subprocess.run(
            cmd, cwd=self.project_root,
            capture_output=True, text=True, timeout=120,
        )
        if result.returncode != 0:
            raise RuntimeError(
                f"TestPeer.{method} 失败 (exit {result.returncode}):\n"
                f"{result.stderr[-500:]}"
            )
        # TestPeer 通过 println 输出到 system-out，gradle -q 下会打到 stdout
        return result.stdout + self._read_xml_stdout(method)

    def _read_xml_stdout(self, method):
        """从测试结果 XML 提取 <system-out>（gradle -q 有时不打 stdout）。"""
        import glob
        xml_dir = os.path.join(
            self.project_root, "server", "build", "test-results", "test"
        )
        for xml_path in glob.glob(os.path.join(xml_dir, "*TestPeer*.xml")):
            try:
                with open(xml_path, encoding="utf-8") as f:
                    content = f.read()
                # 提取 <system-out>...</system-out>
                m = re.search(r"<system-out>(.*?)</system-out>", content, re.DOTALL)
                if m:
                    return "\n" + m.group(1)
            except Exception:
                continue
        return ""

    # ── 对端操作 ──

    def register(self, suffix):
        """注册对端账号。返回 PeerInfo。

        suffix: 用户名后缀（生成 zt-b-<suffix>）。
        """
        out = self._run("registerPeer", {"peer.arg": suffix})
        username = self._extract(out, r"username=(\S+)")
        uid = self._extract(out, r"uid=([0-9a-zA-Z]+)")
        if not username or not uid:
            raise RuntimeError(f"注册结果解析失败，原始输出:\n{out}")
        return PeerInfo(username=username, uid=uid)

    def whoami(self, username, password=DEFAULT_PASSWORD):
        """查询账号 uid。返回 uid 字符串。"""
        out = self._run("whoami", {
            "peer.username": username,
            "peer.password": password,
        })
        uid = self._extract(out, r"uid=([0-9a-zA-Z]+)")
        return uid

    def accept_latest_friend(self, username):
        """B 自动接受最新一条待处理好友申请。返回 True/False。"""
        out = self._run("acceptLatestFriend", {"peer.username": username})
        return "SUCCESS" in out

    def create_personal_chat(self, username, target_uid):
        """B 创建与 target_uid(A) 的私聊，返回 chatId。"""
        out = self._run("createPersonalChat", {
            "peer.username": username,
            "peer.arg": target_uid,
        })
        chat_id = self._extract(out, r"chatId=(\S+)")
        return chat_id

    def send_msg(self, username, chat_id, text):
        """B 在 chat_id 发送文本消息。返回 True/False。"""
        out = self._run("sendMsgAsB", {
            "peer.username": username,
            "peer.arg": f"{chat_id}:{text}",
        })
        return "SUCCESS" in out

    def send_image(self, username, chat_id, file_path):
        """B 在 chat_id 发送图片。file_path 为本地图片路径。"""
        out = self._run("sendImageAsB", {
            "peer.username": username,
            "peer.arg": chat_id,
            "peer.file": file_path,
        })
        return "SUCCESS" in out

    def send_voice(self, username, chat_id, file_path):
        """B 在 chat_id 发送语音。"""
        out = self._run("sendVoiceAsB", {
            "peer.username": username,
            "peer.arg": chat_id,
            "peer.file": file_path,
        })
        return "SUCCESS" in out

    def send_video(self, username, chat_id, file_path):
        """B 在 chat_id 发送视频。"""
        out = self._run("sendVideoAsB", {
            "peer.username": username,
            "peer.arg": chat_id,
            "peer.file": file_path,
        })
        return "SUCCESS" in out

    def send_file(self, username, chat_id, file_path):
        """B 在 chat_id 发送文件。"""
        out = self._run("sendFile", {
            "peer.username": username,
            "peer.arg": chat_id,
            "peer.file": file_path,
        })
        return "SUCCESS" in out

    @staticmethod
    def _extract(text, pattern):
        """从文本中提取第一个正则匹配组，无匹配返回 None。"""
        m = re.search(pattern, text)
        return m.group(1) if m else None

    # ── 新增：T17/T18/T34 协同方法 ──

    def recv_check(self, username, target_uid):
        """B 检查与 targetUid(A) 私聊的最后一条消息。

        返回 dict: {"text": <最后消息>, "from": <senderUid前缀>, "unread": <int>}
        返回 None 表示未找到会话。
        """
        out = self._run("recvCheck", {
            "peer.username": username,
            "peer.arg": target_uid,
        })
        if "FAILED" in out:
            return None
        text = self._extract(out, r"text=([^\n]+)")
        sender = self._extract(out, r"from=(\S+)")
        unread = self._extract(out, r"unread=(\d+)")
        return {"text": text, "from": sender, "unread": int(unread) if unread else 0}

    def send_msg_to(self, username, target_uid, text="hello from B"):
        """B 一键给 targetUid(A) 发送消息（自动创建私聊+发送）。

        返回 (chat_id, success: bool)。
        """
        out = self._run("sendMsgTo", {
            "peer.username": username,
            "peer.arg": f"{target_uid}:{text}",
        })
        chat_id = self._extract(out, r"chatId=(\S+)")
        success = "SUCCESS" in out
        return chat_id, success

    def gen_test_file(self, file_type="png"):
        """生成本地最小合法测试文件。

        file_type: "png" | "mp3" | "mp4"
        返回文件路径字符串。
        """
        out = self._run("generateTestFile", {"peer.arg": file_type})
        path = self._extract(out, r"path=(\S+)")
        return path
