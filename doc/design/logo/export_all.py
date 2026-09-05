#!/usr/bin/env python3
"""
TeamTalk Logo 位图导出脚本。

从 SVG 源导出 Desktop 窗口图标所需的 PNG（AWT 不支持矢量，必须位图）。
Android 图标用 VectorDrawable（res/drawable/ 下的矢量 XML），无需此脚本导出。
登录页 logo 用 Compose 矢量绘制（TeamTalkLogo Composable），同样无需位图。

用法：python3 design/logo/export_all.py
依赖：Pillow + Google Chrome（headless 渲染 SVG）
"""
import base64
import os
import subprocess
from PIL import Image

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
ROOT = os.path.dirname(os.path.abspath(__file__))


def render_svg(svg_path: str, out_path: str, size: int) -> None:
    """用 Chrome headless 把 SVG 渲染为指定尺寸的透明背景 PNG。"""
    data = base64.b64encode(open(svg_path, "rb").read()).decode()
    html = (
        f"<!DOCTYPE html><html><head><style>"
        f"html,body{{margin:0;padding:0;width:{size}px;height:{size}px}}"
        f"body{{background:transparent}}"
        f"img{{width:{size}px;height:{size}px;display:block}}"
        f"</style></head><body>"
        f'<img src="data:image/svg+xml;base64,{data}">'
        f"</body></html>"
    )
    html_path = "/tmp/_tt_render.html"
    open(html_path, "w").write(html)
    subprocess.run(
        [
            CHROME, "--headless", "--disable-gpu", "--no-sandbox",
            f"--window-size={size},{size}", f"--screenshot={out_path}",
            "--hide-scrollbars", "--default-background-color=00000000",
            f"file://{html_path}",
        ],
        capture_output=True,
    )
    # Chrome screenshot 在高 DPI 设备可能产生非精确尺寸，用 PIL 规范化
    if os.path.exists(out_path):
        img = Image.open(out_path)
        if img.size != (size, size):
            img.resize((size, size), Image.LANCZOS).save(out_path)


def main():
    main_svg = os.path.join(ROOT, "svg", "logo-main.svg")

    # ── Desktop 窗口图标：16/32/48/64/128/256（AWT 需要位图） ──
    print("[Desktop] 导出窗口图标")
    desktop_dir = os.path.join(ROOT, "desktop")
    os.makedirs(desktop_dir, exist_ok=True)
    for px in (16, 32, 48, 64, 128, 256):
        render_svg(main_svg, os.path.join(desktop_dir, f"icon-{px}.png"), px)
        print(f"  icon-{px}.png")

    print("\n完成。Android 图标用 res/drawable 矢量，登录页用 TeamTalkLogo Composable，无需导出。")


if __name__ == "__main__":
    main()
