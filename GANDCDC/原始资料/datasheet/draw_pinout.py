# -*- coding: utf-8 -*-
"""根据立创商城 ISG3202LA(C41417827) 的封装数据，绘制带引脚编号的焊盘图和本设计接线图。"""
import json
import os
from PIL import Image, ImageDraw, ImageFont

U = 0.254            # 立创EDA 封装坐标单位 -> mm
SCALE = 150          # 每 mm 像素数
BASE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(BASE, "isg3202la_lcsc_footprint.json")
OUT_PADS = os.path.join(BASE, "ISG3202LA_焊盘引脚编号图.png")
OUT_WIRE = os.path.join(BASE, "ISG3202LA_本设计接线图.png")


def font(size, bold=False):
    for name in (r"C:\Windows\Fonts\msyhbd.ttc" if bold else r"C:\Windows\Fonts\msyh.ttc",
                 r"C:\Windows\Fonts\simhei.ttf", r"C:\Windows\Fonts\simsun.ttc"):
        if os.path.exists(name):
            try:
                return ImageFont.truetype(name, size)
            except Exception:
                pass
    return ImageFont.load_default()


def parse_pads(shapes):
    pads = []
    for s in shapes:
        f = s.split("~")
        if f[0] != "PAD":
            continue
        nums = [float(v) for v in f[10].split() if v.strip()]
        pts = [(nums[i], nums[i + 1]) for i in range(0, len(nums), 2)]
        pads.append({"num": f[8], "type": f[1], "pts": pts})
    return pads


def teeth_at(pts, level, axis):
    """返回某一极值边上的所有朝向外的线段中点（梳齿面）。axis=0 按 x 取极值，axis=1 按 y。"""
    segs = []
    for i in range(len(pts)):
        a, b = pts[i], pts[(i + 1) % len(pts)]
        av, bv = (a[axis], b[axis])
        if abs(av - level) < 0.05 and abs(bv - level) < 0.05:
            segs.append(((a[0] + b[0]) / 2.0, (a[1] + b[1]) / 2.0))
    return segs


def comb_tooth_labels(pad, center):
    """给梳齿焊盘的每个齿分配标注文字。返回 (文字, 齿面坐标, 朝外方向)。"""
    pts = pad["pts"]
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    labels = []
    if pad["num"] == "9":                      # SW 8-17：一排 10 齿
        faces = teeth_at(pts, max(ys), 1)
        faces = [f for f in faces if abs(f[1] - max(ys)) < 1e-6]
        faces.sort(key=lambda p: p[0])
        for i, (x, y) in enumerate(faces):
            labels.append((str(8 + i), x, y, (0, 1)))
    elif pad["num"] == "3":                    # VIN：左 3/4，右 21/22
        for (x, y) in sorted(teeth_at(pts, min(xs), 0), key=lambda p: p[1]):
            labels.append(("3/4", x, y, (-1, 0)))
        for (x, y) in sorted(teeth_at(pts, max(xs), 0), key=lambda p: p[1]):
            labels.append(("21/22", x, y, (1, 0)))
    elif pad["num"] == "5":                    # PGND：左 5/6/7，右 18/19/20
        for (x, y) in sorted(teeth_at(pts, min(xs), 0), key=lambda p: p[1]):
            labels.append(("5/6/7", x, y, (-1, 0)))
        for (x, y) in sorted(teeth_at(pts, max(xs), 0), key=lambda p: p[1]):
            labels.append(("18/19/20", x, y, (1, 0)))
    return labels


def render_land_pattern(pads, path):
    xs = [p[0] for pad in pads for p in pad["pts"]]
    ys = [p[1] for pad in pads for p in pad["pts"]]
    cx, cy = (min(xs) + max(xs)) / 2.0, (min(ys) + max(ys)) / 2.0
    w_mm, h_mm = (max(xs) - min(xs)) * U, (max(ys) - min(ys)) * U
    pad_px = 260
    W = int(w_mm * SCALE) + 2 * pad_px
    H = int(h_mm * SCALE) + 2 * pad_px + 70
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)
    f_pin, f_small, f_title = font(15, True), font(12), font(24, True)

    def to_px(x, y):
        return (pad_px + (x - cx) * U * SCALE, pad_px + 70 + (y - cy) * U * SCALE)

    for pad in pads:
        poly = [to_px(x, y) for (x, y) in pad["pts"]]
        d.polygon(poly, fill=(207, 227, 255), outline=(26, 77, 143))
        if pad["type"] == "RECT":
            px = sum(p[0] for p in poly) / len(poly)
            py = sum(p[1] for p in poly) / len(poly)
            d.text((px, py), pad["num"], font=f_pin, fill=(11, 47, 92), anchor="mm")
    for pad in pads:
        if pad["type"] != "POLYGON":
            continue
        for text, x, y, (dx, dy) in comb_tooth_labels(pad, (cx, cy)):
            px, py = to_px(x, y)
            px += dx * 16
            py += dy * 16
            anchor = {(1, 0): "lm", (-1, 0): "rm", (0, 1): "mt", (0, -1): "mb"}[(dx, dy)]
            d.text((px, py), text, font=f_pin, fill=(11, 47, 92), anchor=anchor)

    d.text((pad_px - 30, 16), "ISG3202LA（立创 C41417827）焊盘与引脚编号（俯视，单位 mm）",
           font=f_title, fill=(20, 20, 20))
    d.text((pad_px - 30, H - 56),
           "同一组内引脚为同网络（VIN=3/4/21/22，PGND=5/6/7/18/19/20，SW=8–17），组内顺序不影响接线；"
           "组外 10 个脚必须逐一对应：1 2 23 24 25 26 27 28 29 30。焊盘间距 0.85mm，焊盘区约 4.87×6.26mm。",
           font=f_small, fill=(60, 60, 60))
    img.save(path)
    return path


def render_wiring(path):
    W, H = 1360, 900
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)
    f_t, f_b, f_s = font(26, True), font(16, True), font(14)
    d.text((30, 20), "GANDCDC 模块：ISG3202LA（U1）接线图", font=f_t, fill=(20, 20, 20))

    box = (520, 250, 840, 650)
    d.rectangle(box, fill=(226, 240, 255), outline=(26, 77, 143), width=3)
    d.text(((box[0] + box[2]) / 2, 300), "U1", font=font(30, True), fill=(11, 47, 92), anchor="mm")
    d.text(((box[0] + box[2]) / 2, 340), "ISG3202LA", font=f_b, fill=(11, 47, 92), anchor="mm")
    d.text(((box[0] + box[2]) / 2, 366), "GaN 半桥 + 驱动", font=f_s, fill=(60, 60, 60), anchor="mm")

    def pin(side, y, text, net_text, color=(0, 110, 60)):
        x0 = box[0] if side == "L" else box[2]
        x1 = x0 - 60 if side == "L" else x0 + 60
        d.line((x0, y, x1, y), fill=(0, 0, 0), width=2)
        tx = x0 + 12 if side == "L" else x0 - 12
        d.text((tx, y - 12), text, font=f_s, fill=(11, 47, 92),
               anchor="la" if side == "L" else "ra")
        if net_text:
            nx = x1 - 12 if side == "L" else x1 + 12
            d.text((nx, y), net_text, font=f_b, fill=color,
                   anchor="ra" if side == "L" else "la")

    pin("L", 420, "27 PWMH", "← U2 LM5148  HO (13)  直连，≤5.5V", (170, 0, 0))
    pin("L", 460, "28 PWML", "← U2 LM5148  LO (11)  直连", (170, 0, 0))
    pin("L", 500, "29 VCC", "← +5.0V（U3 LMR16020 输出）", (170, 0, 0))
    pin("L", 540, "1 LGP / 2 LG", "两者短接（或串 0–5Ω 调低边开通速度）")
    pin("L", 580, "23 HG / 24 HGP", "两者短接（或串 0–5Ω 调高边开通速度）")
    pin("R", 330, "3/4/21/22 VIN", "→ 输入 12–60V ＋ 16×4.7µF 输入电容组")
    pin("R", 380, "8–17 SW", "→ 电感 U4（10µH）→ 输出电容 → 输出端")
    pin("R", 430, "26 SW", "内部已连 8–17，可悬空")
    pin("R", 480, "5/6/7/18/19/20/30 PGND", "→ 功率地（PGND 平面）")
    pin("R", 530, "25 BST", "悬空（内部已集成自举电容，勿外接）", (120, 120, 120))

    d.text((30, 690), "要点：PWMH/PWML 是 5V 逻辑输入（绝对最大 6V）；HG/HGP、LG/LGP 之间用 0Ω 或串阻；",
           font=f_b, fill=(170, 0, 0))
    d.text((30, 720), "BST 不需要外接电容；VIN/PGND/SW 的多脚在芯片外部必须全部连到同一网络（库封装已用梳齿焊盘并联）。",
           font=f_s, fill=(60, 60, 60))
    d.text((30, 750), "上电首测：HO(PWMH) 相对 PGND 的高电平必须 ≤5.5V。", font=f_b, fill=(170, 0, 0))
    d.text((30, 790), "注意：散热片与 PCB 铜皮之间加导热绝缘垫；输入反接会损坏模块。", font=f_s, fill=(60, 60, 60))
    img.save(path)
    return path


if __name__ == "__main__":
    data = json.load(open(SRC, encoding="utf-8"))
    pads = parse_pads(data["shapes"])
    print("copper lands =", sum(1 for p in pads if p["type"] == "RECT")
          + sum(len(comb_tooth_labels(p, (0, 0))) for p in pads if p["type"] == "POLYGON"))
    print(render_land_pattern(pads, OUT_PADS))
    print(render_wiring(OUT_WIRE))
