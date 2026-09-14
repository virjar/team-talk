"""真实 TaskEditor 连续输入回归，不创建服务端任务。

先在指定 Desktop 验收实例打开一张空白新任务表单，再执行：
    python3 scripts/e2e/task_editor_calendar_check.py --port 18082

按周/按月切换后紧接日期、间隔和时间输入，检查旧渲染帧没有覆盖先前字段；
再检查单次任务的开始/截止输入。动作之间不等待重组，只在整组操作后核对结果。
结束时保留未保存表单供查看，不点击保存、取消或关闭其他窗口。
"""

import argparse
import time

from desktop_client import DesktopClient


def check_editor(client):
    def click(tag):
        success, result = client.click_test_tag(tag)
        assert success, f"点击 {tag} 失败：{result}"

    def enter(tag, value):
        success, result = client.input_test_tag(tag, value)
        assert success, f"输入 {tag} 失败：{result}"

    def expect(values, summary=None):
        deadline = time.monotonic() + 5
        while True:
            nodes = client._flat_nodes()
            actual = {node.get("testTag"): node.get("editableText") for node in nodes if "editableText" in node}
            text = {node.get("testTag"): node.get("text") for node in nodes if "text" in node}
            if all(actual.get(tag) == value for tag, value in values.items()) and (
                summary is None or text.get("task.editor.recurrence.summary") == summary
            ):
                return
            assert time.monotonic() < deadline, (
                f"输入没有保留：{ {tag: actual.get(tag) for tag in values} }; "
                f"周期摘要：{text.get('task.editor.recurrence.summary')}"
            )
            time.sleep(0.05)

    assert client.ping(), "指定 Desktop 测试服务不可用"
    assert client.find_test_tag("task.editor"), "请先打开空白新任务表单"
    assert client.has_text("新建任务"), "仅对新建任务表单执行"
    assert client.get_editable_text("task.editor.title") == "", "请使用空白新表单"
    assert client.get_editable_text("task.editor.description") == "", "请使用空白新表单"
    if not client.find_test_tag("task.editor.recurrence.firstDate"):
        click("task.editor.recurrence.enabled")
        assert client.wait_for_test_tag("task.editor.recurrence.firstDate"), "重复安排未展开"

    prefix = "task.editor.recurrence."
    for _ in range(3):
        click(prefix + "frequency.1")
        weekly = {"firstDate": "2026-09-14", "interval": "2", "start": "09:00", "due": "18:00", "zone": "Asia/Shanghai"}
        for field, value in weekly.items():
            enter(prefix + field, value)
        expect({prefix + field: value for field, value in weekly.items()}, "每 2 周的周一 · 从 2026-09-14 起")

        click(prefix + "frequency.2")
        monthly = {"firstDate": "2026-01-31", "interval": "3", "start": "08:15", "due": "17:45", "zone": "Asia/Shanghai"}
        for field, value in monthly.items():
            enter(prefix + field, value)
        expect({prefix + field: value for field, value in monthly.items()}, "每 3 个月的 31 日 · 从 2026-01-31 起")

    click(prefix + "enabled")
    assert client.wait_for_test_tag("task.editor.start.date"), "单次任务时间输入未展开"
    dates = {"task.editor.start.date": "2026-09-14", "task.editor.start.time": "09:10",
             "task.editor.date": "2026-09-15", "task.editor.time": "18:20"}
    for tag, value in dates.items():
        enter(tag, value)
    expect(dates)
    click("task.editor.start.clear")
    enter("task.editor.time", "19:30")
    expect(dict(dates, **{"task.editor.start.date": "", "task.editor.start.time": "", "task.editor.time": "19:30"}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, type=int)
    args = parser.parse_args()
    check_editor(DesktopClient(port=args.port))
    print("PASS: 周/月连续输入与开始/截止输入均保留，未提交任务")
