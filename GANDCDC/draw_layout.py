# -*- coding: utf-8 -*-
"""
从立创EDA 导出的 PCB JSON 生成"带位号标注的布局图"（仓库文档用图）。
用法: python draw_layout.py [PCB.json] [输出.png]
依赖: matplotlib
"""
import json
import os
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Polygon, Rectangle

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False

U = 0.254
SEP = "#" + "@" + "$"

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SRC = os.path.join(HERE, "PCB_PCB_GaN-DCDC降压模块-ISG3201_2026-09-28new.json")
DEFAULT_OUT = os.path.join(HERE, "LAYOUT_annotated.png")


def pairs(txt):
    out = []
    for tok in txt.replace("M", " ").replace("L", " ").replace("Z", " ").replace(",", " ").split():
        try:
            out.append(float(tok))
        except ValueError:
            pass
    return [(out[i], out[i + 1]) for i in range(0, len(out) - 1, 2)]


def main(src=DEFAULT_SRC, out=DEFAULT_OUT):
    data = json.load(open(src, encoding="utf-8"))
    shapes = data["shape"]

    outline = None
    for s in shapes:
        if isinstance(s, str) and s.startswith("TRACK") and s.split("~")[2] == "10":
            outline = pairs(s.split("~")[4])
            break
    if not outline:
        raise SystemExit("未找到板框")
    x0 = min(p[0] for p in outline)
    y0 = min(p[1] for p in outline)

    def P(pt):
        return ((pt[0] - x0) * U, -(pt[1] - y0) * U)

    pours = []
    vias = []
    comps = []
    for s in shapes:
        if not isinstance(s, str):
            continue
        if s.startswith("COPPERAREA"):
            f = s.split("~")
            pours.append((f[2], f[3], pairs(f[4])))
        elif s.startswith("VIA"):
            f = s.split("~")
            vias.append((float(f[1]), float(f[2]), f[4]))
        elif s.startswith("LIB"):
            des = ""
            for seg in s.split(SEP)[1:]:
                if seg.startswith("TEXT~"):
                    f = seg.split("~")
                    if len(f) > 10 and f[1] == "P" and f[10]:
                        des = f[10]
            if not des:
                continue
            bb = None
            for seg in s.split(SEP)[1:]:
                if not seg.startswith("PAD~"):
                    continue
                pts = pairs(seg.split("~")[10])
                if not pts:
                    continue
                xs = [p[0] for p in pts]
                ys = [p[1] for p in pts]
                if bb is None:
                    bb = [min(xs), min(ys), max(xs), max(ys)]
                else:
                    bb = [min(bb[0], min(xs)), min(bb[1], min(ys)),
                          max(bb[2], max(xs)), max(bb[3], max(ys))]
            if bb:
                comps.append((des, bb))

    fig, ax = plt.subplots(figsize=(14, 10))

    style = {
        "21": ("#7ec87e", 0.20, 1.0, "-"),
        "22": ("#f0c060", 0.20, 1.0, "-"),
        "2": ("#4a90e2", 0.35, 0.9, "--"),
        "1": ("#e05a5a", 0.35, 0.9, "-"),
    }
    order = {"21": 1, "22": 2, "2": 3, "1": 4}
    for layer, net, pts in pours:
        if layer not in style:
            continue
        face, alpha, lw, ls = style[layer]
        ax.add_patch(Polygon([P(p) for p in pts], closed=True, facecolor=face, edgecolor="k",
                             alpha=alpha, linewidth=lw, linestyle=ls, zorder=order[layer]))

    vcolor = {"GND": "#9aa0a6", "VIN": "#c0392b", "VOUT": "#1f4fd8", "U4_1": "#e67e22", "R30_1": "#8e44ad"}
    for vx, vy, net in vias:
        x, y = P((vx, vy))
        ax.plot(x, y, marker="o", markersize=1.5, markeredgewidth=0,
                color=vcolor.get(net, "#555555"), zorder=6)

    for des, bb in comps:
        xa, ya = P((bb[0], bb[3]))
        xb, yb = P((bb[2], bb[1]))
        ax.add_patch(Rectangle((xa, ya), xb - xa, yb - ya, facecolor="#ffffff",
                               edgecolor="#222222", alpha=0.88, linewidth=0.8, zorder=7))
        ax.text((xa + xb) / 2, (ya + yb) / 2, des, ha="center", va="center",
                fontsize=5.5, zorder=8, color="#111111")

    box = dict(boxstyle="round,pad=0.3", facecolor="#ffffffdd", edgecolor="#0b2a5b")
    st = dict(fontsize=9, color="#0b2a5b", zorder=10, bbox=box)
    ax.text(22, 4, "控制 / 辅助电源区\n(U2 控制器 + U3 5V + 配置/补偿/采样滤波)", ha="center", **st)
    ax.text(45, 24, "功率级 U5 (GaN 半桥合封)", ha="center", **st)
    ax.text(46, 3.5, "输入电容组 8×4.7µF\n(对称贴近 VIN/PGND)", ha="center", **st)
    ax.text(60, 27, "主电感 U4 (10µH)", ha="center", **st)
    ax.text(60.5, 12, "采样电阻 R30 (5mΩ)\n+ 差分滤波", ha="center", **st)
    ax.text(63, 2, "输出电容组\n8×4.7µF + 3×电解 + 0.1µF", ha="center", **st)
    ax.text(40, 40, "高 dv/dt 节点：SW 铜皮（顶层 + 底层）\n高 di/dt 环路：输入电容 至 U5 VIN/PGND",
            ha="center", fontsize=9, color="#8b0000", zorder=10,
            bbox=dict(boxstyle="round,pad=0.3", facecolor="#ffe9e9", edgecolor="#8b0000"))

    ax.set_aspect("equal")
    ax.set_xlabel("X (mm)")
    ax.set_ylabel("Y (mm)")
    ax.set_title("GANDCDC　GaN 同步 Buck 降压模块　布局标注图（4 层 / 66×43mm）\n"
                 "红=顶层铜皮　蓝(虚线)=底层铜皮　绿=Inner1 整片 GND　黄=Inner2 VIN/VOUT 条　点=过孔",
                 fontsize=12, pad=12)

    handles = [
        Polygon([(0, 0)], facecolor="#e05a5a", alpha=0.35, label="顶层铜皮 (L1)"),
        Polygon([(0, 0)], facecolor="#4a90e2", alpha=0.35, label="底层铜皮 (L2)"),
        Polygon([(0, 0)], facecolor="#7ec87e", alpha=0.35, label="Inner1：整片 GND"),
        Polygon([(0, 0)], facecolor="#f0c060", alpha=0.35, label="Inner2：VIN/VOUT 条"),
    ]
    for net, c in vcolor.items():
        handles.append(plt.Line2D([], [], marker="o", linestyle="", color=c, markersize=4, label="过孔 " + net))
    ax.legend(handles=handles, loc="lower left", fontsize=8, framealpha=0.9, ncol=2)

    fig.tight_layout()
    fig.savefig(out, dpi=170)
    print("saved:", out)
    print("components:", len(comps), "vias:", len(vias), "pours:", len(pours))


if __name__ == "__main__":
    a = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC
    b = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT
    main(a, b)
