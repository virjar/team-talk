#!/usr/bin/env python3
"""
SVG → Android VectorDrawable 转换器（项目内置，无外部依赖）。

支持 SVG 元素：rect / circle / line / path / g（分组属性继承）。
支持属性：fill / stroke / stroke-width / stroke-linecap / fill-opacity / opacity。
支持渐变：linearGradient（fill="url(#id)"）。

两种模式：
  1. 普通转换：viewport 保持 SVG 原始尺寸（viewBox）。
  2. 自适应图标模式（--adaptive）：缩放+居中到 108×108 viewport，
     图形含描边/半径的完整外缘落入指定安全区内（默认 60dp），
     避免 Android 系统按圆/方/泪滴裁剪时切到内容。

用法：
  # 普通转换
  python3 svg_to_vector.py input.svg output.xml

  # 自适应图标前景（图形缩进安全区）
  python3 svg_to_vector.py input.svg output.xml --adaptive

  # 自定义安全区大小（默认 60，即外缘落在中心 60dp 内，四周留 24dp）
  python3 svg_to_vector.py input.svg output.xml --adaptive --safe 56
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from typing import Optional

# Android VectorDrawable 命名空间
NS = "xmlns:android=\"http://schemas.android.com/apk/res/android\""

# ─────────────────────── 颜色 / 渐变解析 ───────────────────────

def parse_color(c: Optional[str], gradients: dict, gradient_idx: list) -> tuple:
    """
    解析 SVG 颜色/渐变引用，返回 (fill_or_stroke 属性字符串, 用于渐变补偿的色值)。
    VectorDrawable 的 path 不支持渐变填充（vectors 顶层支持但 path 内联复杂），
    本转换器把渐变近似为其首个 stop 的色值，并打印提示。
    """
    if c is None or c == "none":
        return "", None
    m = re.match(r"url\(#([^)]+)\)", c)
    if m:
        gid = m.group(1)
        g = gradients.get(gid)
        if g:
            # 取渐变第一个 stop 的色值作为近似
            first_stop = g["stops"][0] if g["stops"] else "#FFFFFF"
            if not gradient_idx[0]:
                print(f"[提示] VectorDrawable path 不直接支持渐变，已用首色 {first_stop} 近似 {gid}", file=sys.stderr)
                gradient_idx[0] = True
            return first_stop, first_stop
        return "#FFFFFF", None
    return c, None


def collect_gradients(root) -> dict:
    """收集 linearGradient 定义：id → {stops:[color...], dir}。"""
    gradients = {}
    for gdef in root.iter():
        tag = _local(gdef.tag)
        if tag == "linearGradient":
            gid = gdef.get("id")
            stops = []
            for stop in gdef.iter():
                if _local(stop.tag) == "stop":
                    style = stop.get("style", "")
                    m = re.search(r"stop-color:\s*(#[0-9A-Fa-f]+)", style)
                    if m:
                        stops.append(m.group(1))
            gradients[gid] = {"stops": stops}
    return gradients


# ─────────────────────── 几何转 path ───────────────────────

def circle_to_path(cx: float, cy: float, r: float) -> str:
    """circle → 两条圆弧 path（兼容 VectorDrawable，不依赖 arc flags 歧义）。"""
    return (f"M {cx-r:.2f},{cy:.2f} "
            f"a {r:.2f},{r:.2f} 0 1,0 {2*r:.2f},0 "
            f"a {r:.2f},{r:.2f} 0 1,0 {-2*r:.2f},0")


def line_to_path(x1: float, y1: float, x2: float, y2: float) -> str:
    return f"M {x1:.2f},{y1:.2f} L {x2:.2f},{y2:.2f}"


def rect_to_path(x: float, y: float, w: float, h: float, rx: float = 0) -> str:
    if rx <= 0:
        return f"M {x:.2f},{y:.2f} h {w:.2f} v {h:.2f} h {-w:.2f} z"
    # 圆角矩形：四角圆弧
    return (f"M {x+rx:.2f},{y:.2f} h {w-2*rx:.2f} "
            f"a {rx:.2f},{rx:.2f} 0 0,1 {rx:.2f},{rx:.2f} "
            f"v {h-2*rx:.2f} "
            f"a {rx:.2f},{rx:.2f} 0 0,1 {-rx:.2f},{rx:.2f} "
            f"h {-w+2*rx:.2f} "
            f"a {rx:.2f},{rx:.2f} 0 0,1 {-rx:.2f},{-rx:.2f} "
            f"v {-h+2*rx:.2f} "
            f"a {rx:.2f},{rx:.2f} 0 0,1 {rx:.2f},{-rx:.2f} z")


# ─────────────────────── 属性继承 ───────────────────────

def _local(tag: str) -> str:
    """去掉 XML 命名空间前缀。"""
    return tag.split("}")[-1] if "}" in tag else tag


def merged_attrs(elem, parent_attrs: dict) -> dict:
    """合并元素自身属性和继承自父 <g> 的属性（元素优先）。"""
    a = dict(parent_attrs)
    for k, v in elem.attrib.items():
        if k != "style":
            a[k] = v
    # style 属性拆开合并
    style = elem.get("style", "")
    for decl in style.split(";"):
        if ":" in decl:
            k, v = decl.split(":", 1)
            a[k.strip()] = v.strip()
    return a


# ─────────────────────── 递归收集 path ───────────────────────

def collect_paths(elem, parent_attrs: dict, gradients, gradient_idx, out: list):
    attrs = merged_attrs(elem, parent_attrs)
    tag = _local(elem.tag)

    if tag in ("g", "svg"):
        for child in elem:
            collect_paths(child, attrs, gradients, gradient_idx, out)
        return

    fill_raw = attrs.get("fill")
    stroke_raw = attrs.get("stroke")
    stroke_w = attrs.get("stroke-width")
    linecap = attrs.get("stroke-linecap")
    opacity = attrs.get("opacity") or attrs.get("fill-opacity")
    fill_color, _ = parse_color(fill_raw, gradients, gradient_idx)

    def add_path(d: str, bbox: tuple, *, is_stroke: bool = False):
        """记录 path 及其原始图形 bbox (minx,miny,w,h)，供自适应缩放精确计算。"""
        color = stroke_raw if is_stroke else fill_color
        col_parsed, _ = parse_color(color, gradients, gradient_idx) if color else ("", None)
        if not col_parsed:
            return
        out.append({
            "d": d,
            "bbox": bbox,
            "fill": "#00000000" if is_stroke else col_parsed,
            "stroke": col_parsed if is_stroke else "",
            "stroke_width": stroke_w or "",
            "linecap": linecap or "",
            "opacity": opacity or "",
            "is_stroke": is_stroke,
        })

    if tag == "circle":
        cx, cy, r = float(attrs["cx"]), float(attrs["cy"]), float(attrs["r"])
        # 描边宽度也算进 bbox 外缘
        sw = float(stroke_w) if stroke_w else 0
        ext = r + sw / 2
        bbox = (cx - ext, cy - ext, 2 * ext, 2 * ext)
        d = circle_to_path(cx, cy, r)
        if fill_raw and fill_raw != "none":
            add_path(d, bbox)
        if stroke_raw and stroke_raw != "none":
            add_path(d, bbox, is_stroke=True)
    elif tag == "line":
        x1, y1, x2, y2 = (float(attrs["x1"]), float(attrs["y1"]),
                          float(attrs["x2"]), float(attrs["y2"]))
        sw = float(stroke_w) if stroke_w else 0
        bbox = (min(x1, x2) - sw / 2, min(y1, y2) - sw / 2,
                abs(x2 - x1) + sw, abs(y2 - y1) + sw)
        d = line_to_path(x1, y1, x2, y2)
        if stroke_raw and stroke_raw != "none":
            add_path(d, bbox, is_stroke=True)
    elif tag == "rect":
        x, y = float(attrs.get("x", 0)), float(attrs.get("y", 0))
        w, h = float(attrs["width"]), float(attrs["height"])
        rx = float(attrs.get("rx", 0))
        sw = float(stroke_w) if stroke_w else 0
        bbox = (x - sw / 2, y - sw / 2, w + sw, h + sw)
        d = rect_to_path(x, y, w, h, rx)
        if fill_raw and fill_raw != "none":
            add_path(d, bbox)
        if stroke_raw and stroke_raw != "none":
            add_path(d, bbox, is_stroke=True)
    elif tag == "path":
        d = attrs.get("d", "")
        # path 无法精确算 bbox，用 pathData 坐标近似
        bbox = bbox_from_pathdata(d)
        if fill_raw and fill_raw != "none":
            add_path(d, bbox)
        if stroke_raw and stroke_raw != "none":
            add_path(d, bbox, is_stroke=True)


def bbox_from_pathdata(d: str) -> tuple:
    """从 pathData 粗略提取 bbox（处理 M/L 绝对坐标）。"""
    xs, ys = [], []
    for m in re.finditer(r"[ML]\s+([\d.,\-\s]+)", d):
        for pair in re.findall(r"(-?[\d.]+)[\s,]+(-?[\d.]+)", m.group(1)):
            xs.append(float(pair[0])); ys.append(float(pair[1]))
    if not xs:
        return (0, 0, 1, 1)
    return min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys)


# ─────────────────────── 安全区变换 ───────────────────────

def compute_paths_bbox(paths: list) -> tuple:
    """所有 path 原始图形 bbox 的并集（用 collect 阶段记录的 bbox，精确）。"""
    if not paths:
        return (0, 0, 108, 108)
    xs0 = [p["bbox"][0] for p in paths]
    ys0 = [p["bbox"][1] for p in paths]
    xs1 = [p["bbox"][0] + p["bbox"][2] for p in paths]
    ys1 = [p["bbox"][1] + p["bbox"][3] for p in paths]
    return min(xs0), min(ys0), max(xs1) - min(xs0), max(ys1) - min(ys0)


def build_scale_for_adaptive(paths: list, safe: float) -> tuple:
    """
    按 path 真实 bbox 计算缩放+平移，把图形外缘落到 108 viewport 的中心 safe 区内。
    安全区：外缘落在 [54-safe/2, 54+safe/2] 内，四周留 (108-safe)/2 dp。
    返回 (scale, dx, dy)。
    """
    minx, miny, w, h = compute_paths_bbox(paths)
    scale = safe / max(w, h) if max(w, h) > 0 else 1.0
    cx = minx + w / 2
    cy = miny + h / 2
    dx = 54 - cx * scale
    dy = 54 - cy * scale
    return scale, dx, dy


def transform_path_d(d: str, scale: float, dx: float, dy: float) -> str:
    """对 pathData 应用仿射变换（缩放+平移）。仅处理 M/L/m/l/H/V 数字。"""
    def repl(m):
        nums = m.group(2).replace("-", " -").replace(",", " ").split()
        nums = [str(round(float(n) * scale + (dx if m.group(1).isupper() else 0), 2)) for n in nums]
        # 平移只对绝对坐标生效；为简化，我们的 logo 用绝对坐标
        return m.group(1) + ",".join(nums)

    # 简单按命令拆分处理（M/L/a）
    out = []
    tokens = re.split(r"([MLAmla])\s*", d)
    # tokens 形如 ['', 'M', ' x,y', 'L', ' x,y', 'a', ' r,r ...', ...]
    i = 1
    result = ""
    parts = re.findall(r"[MLAHVZCSQTAmlahvzcsqt][^MLAHVZCSQTAmlahvzcsqtz]*", d)
    for part in parts:
        cmd = part[0]
        args_str = part[1:].strip()
        if cmd in ("M", "L"):
            nums = re.findall(r"-?\d+\.?\d*", args_str)
            transformed = []
            for j in range(0, len(nums), 2):
                if j + 1 < len(nums):
                    x = float(nums[j]) * scale + dx
                    y = float(nums[j + 1]) * scale + dy
                    transformed.append(f"{x:.2f},{y:.2f}")
            result += f"{cmd} " + " ".join(transformed) + " "
        elif cmd == "a":
            # 圆弧：a rx,ry x-rot large-arc sweep dx,dy —— 缩放 rx/ry/dx/dy，flag 不变
            nums = re.findall(r"-?\d+\.?\d*", args_str)
            if len(nums) >= 7:
                rx = float(nums[0]) * scale
                ry = float(nums[1]) * scale
                ex = float(nums[5]) * scale  # 相对位移缩放（a 是相对坐标，不加 dx/dy）
                ey = float(nums[6]) * scale
                result += f"a {rx:.2f},{ry:.2f} {nums[2]} {nums[3]} {nums[4]} {ex:.2f},{ey:.2f} "
        elif cmd == "h":
            nums = re.findall(r"-?\d+\.?\d*", args_str)
            result += "h " + " ".join(f"{float(n)*scale:.2f}" for n in nums) + " "
        elif cmd == "v":
            nums = re.findall(r"-?\d+\.?\d*", args_str)
            result += "v " + " ".join(f"{float(n)*scale:.2f}" for n in nums) + " "
        else:
            result += part + " "
    return result.strip()


# ─────────────────────── 输出 VectorDrawable ───────────────────────

def emit_vector(paths: list, vw: float, vh: float, dp: float, name: str) -> str:
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f'<!-- 由 svg_to_vector.py 从 {name} 自动生成。请勿手改，改 SVG 后重跑脚本。 -->',
        f'<vector {NS}',
        f'    android:width="{dp:g}dp"',
        f'    android:height="{dp:g}dp"',
        f'    android:viewportWidth="{vw:g}"',
        f'    android:viewportHeight="{vh:g}">',
    ]
    for p in paths:
        lines.append("    <path")
        lines.append(f'        android:pathData="{p["d"]}"')
        if p["is_stroke"]:
            lines.append(f'        android:strokeColor="{p["stroke"]}"')
            if p["stroke_width"]:
                lines.append(f'        android:strokeWidth="{p["stroke_width"]}"')
            if p["linecap"]:
                lines.append(f'        android:strokeLineCap="{p["linecap"]}"')
            lines.append('        android:fillColor="#00000000" />')
        else:
            lines.append(f'        android:fillColor="{p["fill"]}"')
            if p["opacity"]:
                lines.append(f'        android:fillAlpha="{float(p["opacity"]):g}"')
            lines[-1] = lines[-1] + " />"
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


# ─────────────────────── 主流程 ───────────────────────

def parse_viewbox(root) -> tuple:
    vb = root.get("viewBox")
    if vb:
        m = re.split(r"[\s,]+", vb.strip())
        return float(m[0]), float(m[1]), float(m[2]), float(m[3])
    w = float(root.get("width", "1024"))
    h = float(root.get("height", "1024"))
    return 0, 0, w, h


def main():
    ap = argparse.ArgumentParser(description="SVG → Android VectorDrawable")
    ap.add_argument("input", help="输入 SVG 文件")
    ap.add_argument("output", help="输出 VectorDrawable XML")
    ap.add_argument("--adaptive", action="store_true",
                    help="自适应图标模式：缩放+居中到 108 viewport 安全区")
    ap.add_argument("--safe", type=float, default=60,
                    help="自适应模式安全区大小(dp)，外缘落在中心该尺寸内（默认 60）")
    ap.add_argument("--dp", type=float, default=108,
                    help="输出 vector 的 width/height(dp)，自适应模式默认 108")
    args = ap.parse_args()

    tree = ET.parse(args.input)
    root = tree.getroot()
    gradients = collect_gradients(root)
    gradient_idx = [False]
    paths = []
    collect_paths(root, {}, gradients, gradient_idx, paths)

    if not paths:
        print("[错误] 未从 SVG 提取到任何图形元素", file=sys.stderr)
        sys.exit(1)

    viewbox = parse_viewbox(root)

    if args.adaptive:
        # 自适应图标：按图形真实 bbox 缩放+居中到 108 viewport 安全区
        scale, dx, dy = build_scale_for_adaptive(paths, args.safe)
        for p in paths:
            p["d"] = transform_path_d(p["d"], scale, dx, dy)
            if p["stroke_width"]:
                p["stroke_width"] = f"{float(p['stroke_width']) * scale:.2f}"
        vw = vh = 108.0
        dp = 108
    else:
        # 普通转换：viewport 保持 SVG viewBox
        vw, vh = viewbox[2], viewbox[3]
        dp = args.dp

    xml = emit_vector(paths, vw, vh, dp, args.input)
    with open(args.output, "w") as f:
        f.write(xml)
    print(f"[完成] {args.input} → {args.output}")
    print(f"       viewport={vw:g}x{vh:g}, dp={dp:g}, paths={len(paths)}")
    if args.adaptive:
        print(f"       自适应模式：内容缩进安全区 {args.safe}dp（中心，四周留 {108-args.safe:.0f}dp）")


if __name__ == "__main__":
    main()
