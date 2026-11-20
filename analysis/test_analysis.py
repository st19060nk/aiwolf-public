"""解析スクリプトの単体テスト（標準ライブラリの unittest、追加の依存なし）。

    python -m unittest discover -s analysis -p "test_*.py"
"""
from __future__ import annotations

import json
import math
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ElementTree
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ablation  # noqa: E402
import individuality  # noqa: E402
import score_contribution  # noqa: E402
import state_saturation  # noqa: E402
import svgplot  # noqa: E402


class TDistributionTest(unittest.TestCase):
    def test_known_values(self):
        self.assertAlmostEqual(ablation.t_two_sided_p(2.045, 29), 0.05, places=3)
        self.assertAlmostEqual(ablation.t_two_sided_p(0.0, 10), 1.0, places=12)
        self.assertAlmostEqual(ablation.t_critical(29), 2.045, places=3)
        self.assertAlmostEqual(ablation.t_critical(9), 2.262, places=3)
        self.assertAlmostEqual(ablation.t_two_sided_p(1.96, 100000), 0.05, places=3)


class PairedTableTest(unittest.TestCase):
    def test_paired_difference(self):
        rows = []
        for seed in range(10):
            rows.append({"condition": "state_only", "minutes": 30, "delayMinutes": 60, "seed": seed,
                         "ratioEuclidean": 5.0 + 0.1 * seed})
            rows.append({"condition": "adaptive", "minutes": 30, "delayMinutes": 60, "seed": seed,
                         "ratioEuclidean": 6.0 + 0.1 * seed + (0.05 if seed % 2 else -0.05)})
        table = ablation.paired_table(pd.DataFrame(rows), "ratioEuclidean", "state_only")
        adaptive = table[table.condition == "adaptive"].iloc[0]
        self.assertAlmostEqual(adaptive.diffVsBaseline, 1.0, places=9)
        self.assertEqual(adaptive.seedsHigher, 10)
        self.assertLess(adaptive.p, 1e-6)
        self.assertLess(adaptive.ci95Low, 1.0)
        self.assertGreater(adaptive.ci95High, 1.0)


class SvgTest(unittest.TestCase):
    def test_charts_are_well_formed_and_labelled(self):
        with tempfile.TemporaryDirectory() as directory:
            line = svgplot.line_chart(Path(directory) / "l.svg", "Title & <test>", "x [min]", "y [0-1]",
                                      [0, 10, 20], [("a", [0.1, 0.5, 1.0]), ("b", [0.9, float("nan"), 0.2])])
            bar = svgplot.bar_chart(Path(directory) / "b.svg", "Bars", "cond", "ratio", ["c1", "a_very_long_name"],
                                    [("s", [1.0, -0.5], [0.1, 0.2])], reference=1.0)
            for path in (line, bar):
                root = ElementTree.parse(path).getroot()
                text = "".join(root.itertext())
                self.assertTrue(root.tag.endswith("svg"))
                self.assertIn("ratio" if path == bar else "y [0-1]", text)
            self.assertIn("Title & <test>", "".join(ElementTree.parse(line).getroot().itertext()))

    def test_axis_extends_past_the_data(self):
        low, high = svgplot._range([0.0, 1.055], None)
        self.assertGreater(high, 1.055)
        self.assertLessEqual(low, 0.0)

    def test_nice_ticks(self):
        self.assertEqual(svgplot.nice_ticks(0, 1), [0.0, 0.2, 0.4, 0.6, 0.8, 1.0])
        self.assertEqual(svgplot.nice_ticks(0, 10, 4), [0.0, 2.5, 5.0, 7.5, 10.0])


class SaturationTableTest(unittest.TestCase):
    def test_median_ignores_seeds_that_never_reach(self):
        summary = pd.DataFrame([
            {"pattern": "A", "intensity": "typical", "state": "trust", "timeTo0.90Min": 10.0,
             "timeTo0.95Min": 12.0, "timeTo0.99Min": None, "fractionAtCeiling0.99": 0.5, "fractionAtFloor0.01": 0.0},
            {"pattern": "A", "intensity": "typical", "state": "trust", "timeTo0.90Min": 20.0,
             "timeTo0.95Min": None, "timeTo0.99Min": None, "fractionAtCeiling0.99": 0.1, "fractionAtFloor0.01": 0.0},
        ])
        row = state_saturation.saturation_table(summary, {"trust": 0.5}).iloc[0]
        self.assertEqual(row["timeTo0.90MedianMin"], 15.0)
        self.assertEqual(row["timeTo0.95Reached"], 1)
        self.assertEqual(row["timeTo0.99Reached"], 0)
        self.assertAlmostEqual(row["percentAtCeiling0.99"], 30.0)

    def test_half_up_rounding_matches_java(self):
        self.assertEqual(state_saturation.fmt1(13.45), "13.5")
        self.assertEqual(state_saturation.fmt1(90.55), "90.6")
        self.assertEqual(state_saturation.fmt1(2.04), "2.0")

    def test_initial_value_is_not_reported_as_saturation(self):
        summary = pd.DataFrame([{"pattern": "A", "intensity": "typical", "state": "energy", "timeTo0.90Min": 0.0167,
                                 "timeTo0.95Min": 0.0167, "timeTo0.99Min": 0.0167, "fractionAtCeiling0.99": 0.4,
                                 "fractionAtFloor0.01": 0.0}])
        markdown = state_saturation.to_markdown(state_saturation.saturation_table(summary, {"energy": 1.0}))
        self.assertIn("初期値で該当", markdown)


class IndividualityTest(unittest.TestCase):
    def test_ratio_is_averaged_per_seed(self):
        frame = pd.DataFrame([
            {"condition": "adaptive", "minutes": 30, "delayMinutes": 60, "seed": 1, "withinEuclidean": 0.1,
             "betweenEuclidean": 1.0, "ratioEuclidean": 10.0, "withinCosine": 0.01, "betweenCosine": 0.2,
             "ratioCosine": 20.0, "withinJS": 0.01, "betweenJS": 0.3, "ratioJS": 30.0},
            {"condition": "adaptive", "minutes": 30, "delayMinutes": 60, "seed": 2, "withinEuclidean": 0.2,
             "betweenEuclidean": 1.0, "ratioEuclidean": 5.0, "withinCosine": 0.01, "betweenCosine": 0.2,
             "ratioCosine": 20.0, "withinJS": 0.01, "betweenJS": 0.3, "ratioJS": 30.0},
        ])
        row = individuality.summarise(frame).iloc[0]
        self.assertAlmostEqual(row.ratioEuclideanMean, 7.5)          # 1 シードずつの比の平均
        self.assertNotAlmostEqual(row.ratioEuclideanMean, 1.0 / 0.15)  # 平均どうしの比ではない


class ScoreContributionTest(unittest.TestCase):
    def test_reads_only_schema_2_and_later(self):
        old = {"decisionLogSchemaVersion": 1, "experimentalCondition": "adaptive", "behaviorScores": []}
        score = {"behavior": "approach_player", "selected": True, "state": 0.5, "memory": 0.2,
                 "contextSpatialMemory": 0.0, "contextEmergency": 0.0, "contextOther": 0.1, "preference": 0.05,
                 "persistence": 0.25, "base": 0.0, "finalScore": 1.1}
        new = {"decisionLogSchemaVersion": 3, "runId": "run-x", "wolfSubjectId": "wolf-1",
               "experimentalCondition": "adaptive", "behaviorScores": [score]}
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "decisions.jsonl").write_text(json.dumps(old) + "\n" + json.dumps(new) + "\n",
                                                          encoding="utf-8")
            frame = score_contribution.load_decision_components(Path(directory))
        self.assertEqual(len(frame), 1)
        summary = score_contribution.summarise_selected(frame)
        self.assertEqual(summary.iloc[0]["episodicMemory"], 0.2)
        self.assertTrue(math.isclose(summary.iloc[0]["state"], 0.5))


if __name__ == "__main__":
    unittest.main()
