"""Phase 17：個体内・個体間距離と分離比の解析と Figure 3。

入力は ``./gradlew individuality`` の出力 ``separation_by_seed.csv``。

    python analysis/individuality.py research-output/individuality/separation_by_seed.csv \\
        --minutes 30 --delay 60 --out research-output/figures

出力：
  * individuality_summary.csv / 標準出力の Markdown … 条件 × 育成時間 × 評価までの間隔ごとの
    個体内距離・個体間距離・分離比（Euclidean / Cosine / Jensen–Shannon）の平均 ± 標準偏差（n = シード数）
  * fig3_within_between_<minutes>min_delay<delay>.svg … 個体内と個体間の距離（Figure 3）

分離比はシードごとに計算してから平均する（個体内・個体間を先に平均してから割るのとは一致しない）。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import svgplot  # noqa: E402

ORDER = ["static", "state_only", "state_spatial", "state_episodic", "state_memory",
         "state_preference", "adaptive"]
METRICS = {"Euclidean": "Euclidean", "Cosine": "Cosine", "JS": "JS"}


def summarise(frame: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for (minutes, delay, condition), group in frame.groupby(["minutes", "delayMinutes", "condition"]):
        row = {"minutes": minutes, "delayMinutes": delay, "condition": condition, "n": len(group)}
        for metric in METRICS:
            for kind in ("within", "between", "ratio"):
                column = f"{kind}{metric}"
                row[f"{column}Mean"] = float(group[column].mean())
                row[f"{column}Sd"] = float(group[column].std(ddof=1))
        rows.append(row)
    table = pd.DataFrame(rows)
    table["order"] = table.condition.map({c: i for i, c in enumerate(ORDER)})
    return table.sort_values(["minutes", "delayMinutes", "order"]).drop(columns="order")


def to_markdown(table: pd.DataFrame) -> str:
    lines = []
    for (minutes, delay), group in table.groupby(["minutes", "delayMinutes"]):
        lines.append(f"### 育成 {minutes} 分 / 評価まで {delay} 分（平均 ± SD、n = シード数）\n")
        lines.append("| 条件 | n | 個体内 (Euclid) | 個体間 (Euclid) | 分離比 (Euclid) | 分離比 (Cosine) | 分離比 (JS) |")
        lines.append("|---|---:|---:|---:|---:|---:|---:|")
        for _, r in group.iterrows():
            lines.append(
                f"| {r.condition} | {r.n} | {r.withinEuclideanMean:.3f} ± {r.withinEuclideanSd:.3f} | "
                f"{r.betweenEuclideanMean:.3f} ± {r.betweenEuclideanSd:.3f} | "
                f"{r.ratioEuclideanMean:.2f} ± {r.ratioEuclideanSd:.2f} | "
                f"{r.ratioCosineMean:.2f} ± {r.ratioCosineSd:.2f} | {r.ratioJSMean:.2f} ± {r.ratioJSSd:.2f} |")
        lines.append("")
    return "\n".join(lines)


def figure3(table: pd.DataFrame, out: Path, minutes: int, delay: int) -> Path:
    data = table[(table.minutes == minutes) & (table.delayMinutes == delay)]
    conditions = data.condition.tolist()
    n = int(data.n.iloc[0])
    return svgplot.bar_chart(
        out / f"fig3_within_between_{minutes}min_delay{delay}.svg",
        f"Figure 3. Within vs between individual distance ({minutes} min rearing, evaluated after {delay} min)",
        "Condition", "Euclidean distance of behavioral signature (mean ± SD)", conditions,
        [("within (same wolf, 3 sessions)", data.withinEuclideanMean.tolist(), data.withinEuclideanSd.tolist()),
         ("between (different patterns)", data.betweenEuclideanMean.tolist(), data.betweenEuclideanSd.tolist())],
        note=f"n = {n} seeds per condition; 4 rearing patterns per seed; 200 decisions per session.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("csv", type=Path, help="separation_by_seed.csv")
    parser.add_argument("--minutes", type=int, default=30)
    parser.add_argument("--delay", type=int, default=60)
    parser.add_argument("--out", type=Path, default=Path("research-output/figures"))
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    table = summarise(pd.read_csv(args.csv))
    args.out.mkdir(parents=True, exist_ok=True)
    table.to_csv(args.out / "individuality_summary.csv", index=False)
    print(f"wrote {figure3(table, args.out, args.minutes, args.delay)}", file=sys.stderr)
    print(to_markdown(table))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
