"""依存ライブラリなしで論文・実験ノート用の図を SVG で描く小さなモジュール。

matplotlib が入っていない環境でも図を再生成できるようにするためのもの。
描けるのは折れ線グラフと（誤差棒つき）集合棒グラフだけで、意図的に機能を絞っている。

図の約束（実験ノートの決まりと同じ）：
  * タイトルに条件、軸ラベルに量と単位を必ず書く
  * 色は色覚の違いに配慮した Okabe–Ito パレット
  * 凡例は図の右側に置き、データと重ならないようにする
"""
from __future__ import annotations

import math
from pathlib import Path
from typing import Iterable, Sequence
from xml.sax.saxutils import escape

PALETTE = ["#0072B2", "#E69F00", "#009E73", "#D55E00", "#CC79A7", "#56B4E9", "#F0E442", "#000000"]
DASHES = ["", "6 3", "2 2", "8 3 2 3", "", "6 3", "2 2", "8 3 2 3"]

WIDTH, HEIGHT = 860, 480
LEFT, RIGHT, TOP, BOTTOM = 80, 220, 56, 96


def nice_ticks(low: float, high: float, count: int = 5) -> list[float]:
    """見やすい目盛り（1, 2, 2.5, 5 × 10^n 刻み）を返す。"""
    if high <= low:
        high = low + 1.0
    raw = (high - low) / max(1, count)
    magnitude = 10 ** math.floor(math.log10(raw))
    step = next(m * magnitude for m in (1, 2, 2.5, 5, 10) if m * magnitude >= raw)
    start = math.ceil(low / step - 1e-9) * step
    ticks = []
    value = start
    while value <= high + 1e-9:
        ticks.append(round(value, 10))
        value += step
    return ticks


def _fmt(value: float) -> str:
    return f"{value:g}"


class _Canvas:
    def __init__(self, title: str, x_label: str, y_label: str, y_low: float, y_high: float):
        self.parts: list[str] = []
        self.y_low, self.y_high = y_low, y_high
        self.plot_w = WIDTH - LEFT - RIGHT
        self.plot_h = HEIGHT - TOP - BOTTOM
        self.parts.append(
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}" '
            f'viewBox="0 0 {WIDTH} {HEIGHT}" font-family="sans-serif" font-size="12">')
        self.parts.append(f'<rect width="{WIDTH}" height="{HEIGHT}" fill="#ffffff"/>')
        self.parts.append(f'<text x="{WIDTH / 2}" y="24" text-anchor="middle" font-size="15" '
                          f'font-weight="bold">{escape(title)}</text>')
        self.parts.append(f'<text x="{LEFT + self.plot_w / 2}" y="{HEIGHT - 18}" text-anchor="middle">'
                          f'{escape(x_label)}</text>')
        cy = TOP + self.plot_h / 2
        self.parts.append(f'<text x="18" y="{cy}" text-anchor="middle" transform="rotate(-90 18 {cy})">'
                          f'{escape(y_label)}</text>')
        for tick in nice_ticks(y_low, y_high):
            y = self.y(tick)
            self.parts.append(f'<line x1="{LEFT}" y1="{y:.1f}" x2="{LEFT + self.plot_w}" y2="{y:.1f}" '
                              f'stroke="#e5e5e5"/>')
            self.parts.append(f'<text x="{LEFT - 8}" y="{y + 4:.1f}" text-anchor="end">{_fmt(tick)}</text>')
        self.parts.append(f'<rect x="{LEFT}" y="{TOP}" width="{self.plot_w}" height="{self.plot_h}" '
                          f'fill="none" stroke="#333333"/>')

    def y(self, value: float) -> float:
        span = self.y_high - self.y_low
        clamped = min(max(value, self.y_low), self.y_high)
        return TOP + self.plot_h * (1.0 - (clamped - self.y_low) / span)

    def legend(self, names: Sequence[str], kind: str) -> None:
        x = LEFT + self.plot_w + 16
        for i, name in enumerate(names):
            y = TOP + 8 + i * 20
            color = PALETTE[i % len(PALETTE)]
            if kind == "line":
                dash = DASHES[i % len(DASHES)]
                self.parts.append(f'<line x1="{x}" y1="{y}" x2="{x + 24}" y2="{y}" stroke="{color}" '
                                  f'stroke-width="2.5" stroke-dasharray="{dash}"/>')
            else:
                self.parts.append(f'<rect x="{x}" y="{y - 7}" width="16" height="14" fill="{color}"/>')
            self.parts.append(f'<text x="{x + 30}" y="{y + 4}">{escape(name)}</text>')

    def save(self, path: Path, note: str | None = None) -> Path:
        if note:
            self.parts.append(f'<text x="{LEFT}" y="{HEIGHT - 4}" font-size="10" fill="#555555">'
                              f'{escape(note)}</text>')
        self.parts.append("</svg>")
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(self.parts), encoding="utf-8")
        return path


def _range(values: Iterable[float], y_range: tuple[float, float] | None) -> tuple[float, float]:
    if y_range is not None:
        return y_range
    finite = [v for v in values if v is not None and math.isfinite(v)]
    low, high = min(finite + [0.0]), max(finite + [1.0])
    ticks = nice_ticks(low, high)
    step = ticks[1] - ticks[0] if len(ticks) > 1 else 1.0
    # 軸の端をデータの最大・最小ちょうどにせず、次の目盛りまで広げる（棒が枠に張り付いて見えないように）。
    top = ticks[-1] if ticks[-1] >= high - 1e-12 else ticks[-1] + step
    bottom = ticks[0] if ticks[0] <= low + 1e-12 else ticks[0] - step
    return bottom, top


def line_chart(path: Path, title: str, x_label: str, y_label: str, x_values: Sequence[float],
               series: Sequence[tuple[str, Sequence[float]]], y_range: tuple[float, float] | None = None,
               note: str | None = None) -> Path:
    """折れ線グラフ。series = [(凡例名, y の列), ...]。x は数値（等間隔でなくてよい）。"""
    low, high = _range([v for _, ys in series for v in ys], y_range)
    canvas = _Canvas(title, x_label, y_label, low, high)
    x_low, x_high = min(x_values), max(x_values)
    span = (x_high - x_low) or 1.0

    def x_of(value: float) -> float:
        return LEFT + canvas.plot_w * (value - x_low) / span

    for tick in nice_ticks(x_low, x_high, 8):
        x = x_of(tick)
        canvas.parts.append(f'<line x1="{x:.1f}" y1="{TOP + canvas.plot_h}" x2="{x:.1f}" '
                            f'y2="{TOP + canvas.plot_h + 5}" stroke="#333333"/>')
        canvas.parts.append(f'<text x="{x:.1f}" y="{TOP + canvas.plot_h + 20}" text-anchor="middle">'
                            f'{_fmt(tick)}</text>')
    for i, (_, ys) in enumerate(series):
        points = " ".join(f"{x_of(x):.1f},{canvas.y(y):.1f}" for x, y in zip(x_values, ys)
                          if y is not None and math.isfinite(y))
        canvas.parts.append(f'<polyline points="{points}" fill="none" stroke="{PALETTE[i % len(PALETTE)]}" '
                            f'stroke-width="2.5" stroke-dasharray="{DASHES[i % len(DASHES)]}"/>')
    canvas.legend([name for name, _ in series], "line")
    return canvas.save(path, note)


def bar_chart(path: Path, title: str, x_label: str, y_label: str, categories: Sequence[str],
              series: Sequence[tuple[str, Sequence[float], Sequence[float] | None]],
              y_range: tuple[float, float] | None = None, reference: float | None = None,
              note: str | None = None) -> Path:
    """集合棒グラフ。series = [(凡例名, 値の列, 誤差の列 or None), ...]。

    誤差は ±（対称）で描く。reference を与えると水平の点線を引く（例：分離比 1）。
    負の値は 0 から下へ伸ばす。
    """
    values = [v for _, vs, _ in series for v in vs]
    errors = [v + e for _, vs, es in series if es for v, e in zip(vs, es)]
    low, high = _range(values + errors + ([reference] if reference is not None else []), y_range)
    canvas = _Canvas(title, x_label, y_label, low, high)
    group_w = canvas.plot_w / max(1, len(categories))
    bar_w = group_w * 0.8 / max(1, len(series))
    zero = canvas.y(max(low, min(0.0, high)))
    for c, category in enumerate(categories):
        gx = LEFT + c * group_w
        lx, ly = gx + group_w / 2, TOP + canvas.plot_h + 16
        if len(category) * 6.5 > group_w:
            # 収まらない名前は斜めにして重ならないようにする。
            canvas.parts.append(f'<text x="{lx:.1f}" y="{ly:.1f}" text-anchor="end" font-size="11" '
                                f'transform="rotate(-20 {lx:.1f} {ly:.1f})">{escape(category)}</text>')
        else:
            canvas.parts.append(f'<text x="{lx:.1f}" y="{ly + 2:.1f}" text-anchor="middle" font-size="11">'
                                f'{escape(category)}</text>')
        for s, (_, vs, es) in enumerate(series):
            value = vs[c]
            if value is None or not math.isfinite(value):
                continue
            x = gx + group_w * 0.1 + s * bar_w
            top = canvas.y(value)
            y, h = (top, zero - top) if top < zero else (zero, top - zero)
            canvas.parts.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{bar_w * 0.92:.1f}" height="{max(h, 0.5):.1f}" '
                                f'fill="{PALETTE[s % len(PALETTE)]}"/>')
            if es is not None and es[c] is not None and math.isfinite(es[c]):
                cx = x + bar_w * 0.46
                canvas.parts.append(f'<line x1="{cx:.1f}" y1="{canvas.y(value - es[c]):.1f}" x2="{cx:.1f}" '
                                    f'y2="{canvas.y(value + es[c]):.1f}" stroke="#222222" stroke-width="1.3"/>')
    if reference is not None:
        y = canvas.y(reference)
        canvas.parts.append(f'<line x1="{LEFT}" y1="{y:.1f}" x2="{LEFT + canvas.plot_w}" y2="{y:.1f}" '
                            f'stroke="#555555" stroke-dasharray="4 3"/>')
    canvas.legend([name for name, _, _ in series], "bar")
    return canvas.save(path, note)
