"""Phase 15：内部状態の天井効果の解析と Figure 1。

入力は ``./gradlew saturation`` の出力ディレクトリ（既定 research-output/state-saturation）。

    python analysis/state_saturation.py research-output/state-saturation --out research-output/figures

出力：
  * fig1_state_trajectory_<pattern>_<intensity>.svg … 内部状態 7 つの平均の時間変化（Figure 1）
  * saturation_table.csv / 標準出力の Markdown … 状態ごとの 0.90 / 0.95 / 0.99 到達時刻の中央値、
    到達したシード数、0.99 以上だった時間の割合（パターン × 強さの比較）
"""
from __future__ import annotations

import argparse
import sys
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import svgplot  # noqa: E402

STATES = ["trust", "attachment", "fear", "stress", "curiosity", "autonomy", "energy"]
THRESHOLDS = ["timeTo0.90Min", "timeTo0.95Min", "timeTo0.99Min"]


def fmt1(value: float) -> str:
    """小数第 1 位へ四捨五入（half-up）。Java 側のレポート（String.format）と表記を揃えるため。"""
    return str(Decimal(repr(float(value))).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP))


def saturation_table(summary: pd.DataFrame, initial: dict[str, float] | None = None) -> pd.DataFrame:
    """シードごとの指標から、パターン × 強さ × 状態の要約を作る。

    到達時刻は「到達したシードの中央値」と「到達したシード数」。到達しなかったシードは
    中央値に含めない（含めると打ち切りの扱いで値が変わってしまう）。
    initial（状態 → 初期値）を渡すと、初期値の時点で閾値を満たす状態（energy = 1.0 など）に
    印を付ける（「0 分で飽和」と読めてしまうのを防ぐ）。
    """
    rows = []
    for (pattern, intensity, state), group in summary.groupby(["pattern", "intensity", "state"], sort=False):
        row = {"pattern": pattern, "intensity": intensity, "state": state, "n": len(group),
               "initialValue": (initial or {}).get(state, float("nan"))}
        for column in THRESHOLDS:
            reached = group[column].dropna()
            row[column.replace("Min", "MedianMin")] = float(reached.median()) if len(reached) else float("nan")
            row[column.replace("Min", "Reached")] = int(len(reached))
        row["percentAtCeiling0.99"] = 100.0 * float(group["fractionAtCeiling0.99"].mean())
        row["percentAtFloor0.01"] = 100.0 * float(group["fractionAtFloor0.01"].mean())
        rows.append(row)
    return pd.DataFrame(rows)


def to_markdown(table: pd.DataFrame, only_saturating: bool = True) -> str:
    lines = ["| パターン | 強さ | 状態 | 0.90 到達 [分] | 0.95 到達 [分] | 0.99 到達 [分] | ≥0.99 の時間 [%] | ≤0.01 の時間 [%] |",
             "|---|---|---|---:|---:|---:|---:|---:|"]
    for _, row in table.iterrows():
        if only_saturating and row["timeTo0.90Reached"] == 0 and row["percentAtFloor0.01"] < 1.0:
            continue

        def cell(name: str) -> str:
            median = row[f"timeTo{name}MedianMin"]
            reached = row[f"timeTo{name}Reached"]
            if row.initialValue >= float(name):
                return "初期値で該当"
            return "—" if reached == 0 else f"{fmt1(median)} ({reached}/{row.n})"

        lines.append(f"| {row.pattern} | {row.intensity} | {row.state} | {cell('0.90')} | {cell('0.95')} | "
                     f"{cell('0.99')} | {fmt1(row['percentAtCeiling0.99'])} | {fmt1(row['percentAtFloor0.01'])} |")
    return "\n".join(lines)


def figure1(mean: pd.DataFrame, out: Path, pattern: str, intensity: str) -> Path:
    data = mean[(mean.pattern == pattern) & (mean.intensity == intensity)]
    minutes = sorted(data.minute.unique())
    series = []
    for state in STATES:
        values = data[data.state == state].sort_values("minute")["mean"].tolist()
        series.append((state, values))
    n = int(data["n"].iloc[0]) if len(data) else 0
    return svgplot.line_chart(
        out / f"fig1_state_trajectory_{pattern}_{intensity}.svg",
        f"Figure 1. Internal state trajectory ({pattern}, {intensity}, mean of {n} seeds)",
        "Time since rearing start [min]", "State value [0-1]", minutes, series, y_range=(0.0, 1.0),
        note="PARAMETER_SET_VERSION=2, condition=ADAPTIVE. Source: state_trajectory_mean.csv")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("directory", type=Path, help="research-output/state-saturation")
    parser.add_argument("--out", type=Path, default=Path("research-output/figures"))
    parser.add_argument("--intensity", default="typical", choices=["typical", "intensive", "all"])
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    summary = pd.read_csv(args.directory / "saturation_summary.csv")
    mean = pd.read_csv(args.directory / "state_trajectory_mean.csv")
    start = mean[mean.minute == 0].groupby("state")["mean"].first().to_dict()
    table = saturation_table(summary, start)
    args.out.mkdir(parents=True, exist_ok=True)
    table.to_csv(args.out / "saturation_table.csv", index=False)

    intensities = ["typical", "intensive"] if args.intensity == "all" else [args.intensity]
    for intensity in intensities:
        for pattern in mean.pattern.unique():
            print(f"wrote {figure1(mean, args.out, pattern, intensity)}", file=sys.stderr)
    print(to_markdown(table[table.intensity.isin(intensities)]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
