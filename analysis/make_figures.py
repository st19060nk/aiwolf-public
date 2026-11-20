"""Figure 1〜4 と要約表を、シミュレーションの出力から一度に作り直す。

前提：プロジェクトのルートで次の 3 つを実行済みであること（Minecraft は不要）。

    ./gradlew saturation
    ./gradlew individuality
    ./gradlew demoEstimate

そのうえで

    python analysis/make_figures.py                  # research-output/figures/ に出力
    python analysis/make_figures.py --out docs/figures/<日付>

| 図 | 内容 | 入力 |
|---|---|---|
| Figure 1 | 内部状態の時間変化（パターンごと） | research-output/state-saturation/ |
| Figure 2 | Demo A の APPROACH_PLAYER のスコア成分 | research-output/score-breakdown/demo_a_breakdown.csv |
| Figure 3 | 個体内 vs 個体間の距離 | research-output/individuality/separation_by_seed.csv |
| Figure 4 | アブレーション条件ごとの分離比 | 同上 |
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import ablation  # noqa: E402
import individuality  # noqa: E402
import score_contribution  # noqa: E402
import state_saturation  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", type=Path, default=Path("research-output"))
    parser.add_argument("--out", type=Path, default=Path("research-output/figures"))
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    written = []

    saturation = args.input / "state-saturation"
    mean = pd.read_csv(saturation / "state_trajectory_mean.csv")
    for pattern in mean.pattern.unique():
        written.append(state_saturation.figure1(mean, out, pattern, "typical"))

    demo = pd.read_csv(args.input / "score-breakdown" / "demo_a_breakdown.csv")
    written.append(score_contribution.figure2_from_demo(demo, out, "approach_player"))

    separation = pd.read_csv(args.input / "individuality" / "separation_by_seed.csv")
    written.append(individuality.figure3(individuality.summarise(separation), out, 30, 60))
    written.append(ablation.figure4(separation, out, "ratioEuclidean", 60))

    for path in written:
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
