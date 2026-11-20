"""Phase 14：行動スコアの成分ごとの寄与の解析と Figure 2。

2 種類の入力に対応する。

1. 実機の研究ログ（decisions.jsonl のあるディレクトリ、decisionLogSchemaVersion 2 以降）

       python analysis/score_contribution.py --decisions "<world>/aiwolf-research" --out research-output/figures

   行動ごとに、選ばれたときの各成分の平均（state / episodicMemory / spatialMemory / preference /
   context / persistence / emergency）を表と図にする。

2. ``./gradlew demoEstimate`` が書く Demo A の成分分解（オフラインの見積もり）

       python analysis/score_contribution.py --demo research-output/score-breakdown/demo_a_breakdown.csv

   指定した行動（既定 approach_player）の各時点の成分を図にする。

成分は Java 側の ``BehaviorScore.components()`` と同じ定義（context は空間記憶と緊急退避を除いた部分）。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import svgplot  # noqa: E402

PLOTTED = ["state", "episodicMemory", "spatialMemory", "preference", "context"]


def load_decision_components(directory: Path) -> pd.DataFrame:
    """decisions.jsonl から、全候補の成分を 1 行 1 候補で読む（スキーマ 2 以降の行だけ）。"""
    rows = []
    with open(directory / "decisions.jsonl", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            record = json.loads(line)
            if record.get("decisionLogSchemaVersion", 1) < 2:
                continue  # 文脈の内訳がない古い行は混ぜない
            for score in record.get("behaviorScores", []):
                rows.append({
                    "runId": record.get("runId"),
                    "wolf": record.get("wolfSubjectId", record.get("wolfUuid")),
                    "condition": record["experimentalCondition"],
                    "behavior": score["behavior"],
                    "selected": score["selected"],
                    "state": score["state"],
                    "episodicMemory": score["memory"],
                    "spatialMemory": score["contextSpatialMemory"],
                    "emergency": score["contextEmergency"],
                    "context": score["contextOther"],
                    "preference": score["preference"],
                    "persistence": score["persistence"],
                    "base": score["base"],
                    "finalScore": score["finalScore"],
                })
    return pd.DataFrame(rows)


def summarise_selected(frame: pd.DataFrame) -> pd.DataFrame:
    """選ばれた候補だけについて、条件 × 行動ごとの成分の平均と件数。"""
    selected = frame[frame.selected]
    columns = PLOTTED + ["persistence", "emergency", "base", "finalScore"]
    summary = selected.groupby(["condition", "behavior"])[columns].mean()
    summary["n"] = selected.groupby(["condition", "behavior"]).size()
    return summary.reset_index()


def figure2_from_logs(summary: pd.DataFrame, out: Path, condition: str) -> Path:
    data = summary[summary.condition == condition]
    behaviors = data.behavior.tolist()
    series = [(component, data[component].tolist(), None) for component in PLOTTED]
    return svgplot.bar_chart(
        out / f"fig2_score_contribution_{condition}.svg",
        f"Figure 2. Mean score contribution when selected ({condition})",
        "Selected behavior", "Contribution to score (before softmax)", behaviors, series, reference=0.0,
        note="Source: decisions.jsonl (decisionLogSchemaVersion >= 2). n per behavior in the table.")


def figure2_from_demo(demo: pd.DataFrame, out: Path, behavior: str) -> Path:
    data = demo[demo.behavior == behavior]
    timepoints = list(dict.fromkeys(data.timepoint))
    series = []
    for component in PLOTTED:
        values = [float(data[(data.timepoint == t) & (data.component == component)]["value"].iloc[0])
                  for t in timepoints]
        series.append((component, values, None))
    probabilities = [100.0 * float(data[data.timepoint == t]["probability"].iloc[0]) for t in timepoints]
    labels = [f"{t} (P={p:.1f}%)" for t, p in zip(timepoints, probabilities)]
    return svgplot.bar_chart(
        out / f"fig2_score_contribution_demoA_{behavior}.svg",
        f"Figure 2. Score components of {behavior.upper()} during Demo A",
        "Time point (P = selection probability)",
        "Contribution to score (before softmax)", labels, series, reference=0.0,
        note="A0 initial, A1 6 pets+3 feeds, A2 +rescue, A3 +24 pets, A4 30 min later. "
             "Deterministic estimate, context=calm. Source: demo_a_breakdown.csv")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--decisions", type=Path, help="decisions.jsonl のあるディレクトリ")
    parser.add_argument("--demo", type=Path, help="demo_a_breakdown.csv")
    parser.add_argument("--behavior", default="approach_player")
    parser.add_argument("--out", type=Path, default=Path("research-output/figures"))
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if not args.decisions and not args.demo:
        parser.error("--decisions か --demo のどちらかを指定してください")
    args.out.mkdir(parents=True, exist_ok=True)

    if args.demo:
        demo = pd.read_csv(args.demo)
        print(f"wrote {figure2_from_demo(demo, args.out, args.behavior)}", file=sys.stderr)
        pivot = demo[demo.behavior == args.behavior].pivot(index="component", columns="timepoint", values="value")
        print(pivot.round(3).to_string())
    if args.decisions:
        frame = load_decision_components(args.decisions)
        if frame.empty:
            print("decisionLogSchemaVersion 2 以降の決定がありません", file=sys.stderr)
            return 1
        summary = summarise_selected(frame)
        summary.to_csv(args.out / "score_contribution_selected.csv", index=False)
        for condition in summary.condition.unique():
            print(f"wrote {figure2_from_logs(summary, args.out, condition)}", file=sys.stderr)
        print(summary.round(3).to_string(index=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
