"""Phase 16/17: アブレーション条件の分離比を、シードごとの対応のある比較で検定する。

入力は ``./gradlew individuality`` の出力 ``separation_by_seed.csv``。
条件間で出来事列・乱数列が同一（同じシード）なので、シードを単位とした
対応のある比較（paired）ができる。

    python analysis/ablation.py research-output/individuality/separation_by_seed.csv

出力（標準出力・Markdown の表）：
  * 条件ごとの分離比の平均 ± 標準偏差（n = シード数）
  * 基準条件（既定 state_only）との差の平均、95 % 信頼区間（t 分布）、
    差が正だったシードの数、対応のある t 検定の t 値と p 値（両側、多重比較の補正なし）

依存：pandas, numpy のみ（t 分布は標準ライブラリで計算するので scipy は不要）
"""
from __future__ import annotations

import argparse
import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import svgplot  # noqa: E402

def _betacf(a: float, b: float, x: float) -> float:
    """不完全ベータ関数の連分数（Numerical Recipes 6.4）。"""
    qab, qap, qam = a + b, a + 1.0, a - 1.0
    c, d = 1.0, 1.0 - qab * x / qap
    d = 1.0 / (d if abs(d) > 1e-300 else 1e-300)
    h = d
    for m in range(1, 300):
        m2 = 2 * m
        aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1.0 + aa * d
        d = 1.0 / (d if abs(d) > 1e-300 else 1e-300)
        c = 1.0 + aa / c if abs(c) > 1e-300 else 1e300
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1.0 + aa * d
        d = 1.0 / (d if abs(d) > 1e-300 else 1e-300)
        c = 1.0 + aa / c if abs(c) > 1e-300 else 1e300
        delta = d * c
        h *= delta
        if abs(delta - 1.0) < 1e-12:
            break
    return h


def _betainc(a: float, b: float, x: float) -> float:
    """正則化不完全ベータ関数 I_x(a, b)。"""
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    front = math.exp(math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b)
                     + a * math.log(x) + b * math.log(1.0 - x))
    if x < (a + 1.0) / (a + b + 2.0):
        return front * _betacf(a, b, x) / a
    return 1.0 - front * _betacf(b, a, 1.0 - x) / b


def t_two_sided_p(t: float, df: int) -> float:
    """自由度 df の t 分布の両側 p 値。"""
    return _betainc(df / 2.0, 0.5, df / (df + t * t))


def t_critical(df: int, alpha: float = 0.05) -> float:
    """両側 alpha の t 分布の臨界値（二分法）。"""
    low, high = 0.0, 100.0
    for _ in range(200):
        mid = (low + high) / 2.0
        if t_two_sided_p(mid, df) > alpha:
            low = mid
        else:
            high = mid
    return (low + high) / 2.0


ORDER = ["static", "state_only", "state_spatial", "state_episodic", "state_memory",
         "state_preference", "adaptive"]


def paired_table(frame: pd.DataFrame, metric: str, baseline: str) -> pd.DataFrame:
    rows = []
    for (minutes, delay), group in frame.groupby(["minutes", "delayMinutes"]):
        pivot = group.pivot(index="seed", columns="condition", values=metric)
        if baseline not in pivot:
            continue
        base = pivot[baseline]
        for condition in [c for c in ORDER if c in pivot.columns]:
            values = pivot[condition]
            diff = values - base
            n = int(diff.notna().sum())
            mean = float(diff.mean())
            sd = float(diff.std(ddof=1)) if n > 1 else float("nan")
            half = t_critical(n - 1) * sd / math.sqrt(n) if n > 1 else float("nan")
            if n > 1 and condition != baseline and sd > 0:
                t = mean / (sd / math.sqrt(n))
                p = t_two_sided_p(t, n - 1)
            else:
                t, p = float("nan"), float("nan")
            rows.append({
                "minutes": minutes, "delayMinutes": delay, "condition": condition, "n": n,
                "mean": float(values.mean()), "sd": float(values.std(ddof=1)),
                "diffVsBaseline": mean, "ci95Low": mean - half, "ci95High": mean + half,
                "seedsHigher": int((diff > 0).sum()), "t": float(t), "p": float(p),
            })
    return pd.DataFrame(rows)


def to_markdown(table: pd.DataFrame, metric: str, baseline: str) -> str:
    lines = []
    for (minutes, delay), group in table.groupby(["minutes", "delayMinutes"]):
        lines.append(f"### 育成 {minutes} 分 / 評価まで {delay} 分（{metric}、基準 {baseline}）\n")
        lines.append("| 条件 | n | 分離比 平均 ± SD | 基準との差 | 95% CI | 差 > 0 のシード | t | p |")
        lines.append("|---|---:|---:|---:|---:|---:|---:|---:|")
        for _, row in group.iterrows():
            lines.append(
                f"| {row.condition} | {row.n} | {row['mean']:.2f} ± {row.sd:.2f} | {row.diffVsBaseline:+.2f} | "
                f"[{row.ci95Low:+.2f}, {row.ci95High:+.2f}] | {row.seedsHigher}/{row.n} | "
                f"{row.t:.2f} | {row.p:.2g} |")
        lines.append("")
    return "\n".join(lines)


def figure4(frame: pd.DataFrame, out: Path, metric: str, delay: int) -> Path:
    """Figure 4：条件ごとの分離比（平均 ± 95 % 信頼区間、育成時間ごとに棒を並べる）。"""
    data = frame[frame.delayMinutes == delay]
    conditions = [c for c in ORDER if c in set(data.condition)]
    series = []
    for minutes in sorted(data.minutes.unique()):
        means, halves = [], []
        for condition in conditions:
            values = data[(data.minutes == minutes) & (data.condition == condition)][metric]
            n = len(values)
            means.append(float(values.mean()))
            halves.append(t_critical(n - 1) * float(values.std(ddof=1)) / math.sqrt(n) if n > 1 else float("nan"))
        series.append((f"{minutes} min rearing", means, halves))
    n = int(data.groupby(["minutes", "condition"]).size().min())
    return svgplot.bar_chart(
        Path(out) / f"fig4_ablation_{metric}_delay{delay}.svg",
        f"Figure 4. Separation ratio by ablation condition ({metric}, evaluated after {delay} min)",
        "Condition", "Separation ratio = between / within (mean ± 95% CI)", conditions, series, reference=1.0,
        note=f"n = {n} seeds per bar. Dashed line: ratio 1 (no individuality). Source: separation_by_seed.csv")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("csv", type=Path, help="separation_by_seed.csv")
    parser.add_argument("--metric", default="ratioEuclidean",
                        choices=["ratioEuclidean", "ratioCosine", "ratioJS"])
    parser.add_argument("--baseline", default="state_only")
    parser.add_argument("--csv-out", type=Path, help="結果の表を CSV でも保存する")
    parser.add_argument("--figure-dir", type=Path, help="Figure 4 を SVG で書き出すディレクトリ")
    parser.add_argument("--delay", type=int, default=60, help="Figure 4 に使う評価までの間隔 [分]")
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")  # Windows のコンソール既定（cp932）で化けないように
    frame = pd.read_csv(args.csv)
    table = paired_table(frame, args.metric, args.baseline)
    if args.csv_out:
        table.to_csv(args.csv_out, index=False)
    if args.figure_dir:
        print(f"wrote {figure4(frame, args.figure_dir, args.metric, args.delay)}", file=sys.stderr)
    print(to_markdown(table, args.metric, args.baseline))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
