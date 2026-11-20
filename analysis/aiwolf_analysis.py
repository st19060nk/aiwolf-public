#!/usr/bin/env python3
"""AI Wolf Research Mod のログを解析する。

使い方:

    python aiwolf_analysis.py <ログディレクトリ> [-o <出力先>]

ログディレクトリは、ワールド内の ``aiwolf-research/`` を指す。
``signatures.jsonl`` / ``decisions.jsonl`` / ``events.jsonl`` と、
``export-*/`` の中の ``wolves.json`` / ``memories.jsonl`` を読む。

図は描かない。出力した CSV から、論文用の図はあらためて意図を持って作ること。
ここでは数値と検定に必要な表だけを出す。
"""

from __future__ import annotations

import argparse
import itertools
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

# 研究ログに現れるマクロ行動。BehaviorType と同じ順序。
BEHAVIOURS = [
    "follow_close",
    "follow_normal",
    "explore_nearby",
    "approach_player",
    "defend_player",
    "retreat",
    "avoid_location",
    "rest_near_player",
]

STATES = ["trust", "attachment", "fear", "stress", "curiosity", "autonomy", "energy"]

# スコアの成分。「なぜその行動をしたのか」を説明するために内訳ごと残してある。
COMPONENTS = ["base", "state", "context", "memory", "preference", "persistence", "penalty"]
# decisionLogSchemaVersion 2 以降にだけある context の内訳。古い行では NaN（0 ではない）にする。
CONTEXT_BREAKDOWN = ["contextSpatialMemory", "contextEmergency", "contextOther"]


def read_jsonl(path: Path) -> list[dict]:
    """JSONL を読む。ファイルがなければ空リストを返す。"""
    if not path.exists():
        return []
    records = []
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as error:
                # 書き込み中に読んだ場合、最後の行が途中で切れていることがある。
                print(f"  warning: {path.name}:{line_number} を読み飛ばしました ({error})",
                      file=sys.stderr)
    return records


def short_id(series: pd.Series) -> pd.Series:
    """UUID を表示用に短縮する。

    UUID は先頭が共通していることが多く（テスト用や連番生成では特に）、
    先頭を取ると別個体が同じ表示になってしまう。末尾を使う。
    """
    return series.astype(str).str.slice(-8)


def total_variation(left: pd.Series, right: pd.Series) -> float:
    """2 つの行動分布の total variation distance。0 で一致、1 で共通行動なし。"""
    return float(np.abs(left.values - right.values).sum() / 2.0)


def load_signatures(directory: Path) -> pd.DataFrame:
    """signatures.jsonl を1行1測定の表にする。"""
    records = read_jsonl(directory / "signatures.jsonl")
    if not records:
        return pd.DataFrame()

    rows = []
    for record in records:
        row = {
            "wolfUuid": record["wolfUuid"],
            "subjectId": record.get("subjectId"),
            "condition": record["experimentalCondition"],
            "label": record.get("label"),
            "sessionIndex": record["sessionIndex"],
            "worldTime": record["worldTime"],
            "epochMillis": record["epochMillis"],
            "sampleCount": record["sampleCount"],
            "meanOwnerDistance": record["meanOwnerDistance"],
        }
        row.update({f"p_{name}": record["behaviourProportions"].get(name, 0.0)
                    for name in BEHAVIOURS})
        row.update({f"s_{name}": record["state"].get(name, float("nan")) for name in STATES})
        rows.append(row)
    return pd.DataFrame(rows).sort_values(["label", "wolfUuid", "sessionIndex"])


def signature_distances(signatures: pd.DataFrame) -> pd.DataFrame:
    """指紋の総当たり距離を求める。

    個体内距離は「同じ個体・同じラベル・別セッション」の組、
    群間距離は「別個体・同じラベル」の組とする。ラベルで揃えるのは、
    育成フェーズと識別フェーズで指紋が変わるのは当然であり、
    それを個体差として数えてはいけないからである。
    """
    proportion_columns = [f"p_{name}" for name in BEHAVIOURS]
    rows = []
    for label, group in signatures.groupby("label", dropna=False):
        for (left_index, left), (right_index, right) in itertools.combinations(group.iterrows(), 2):
            rows.append({
                "label": label,
                "kind": "within" if left["wolfUuid"] == right["wolfUuid"] else "between",
                "wolfA": left["wolfUuid"],
                "wolfB": right["wolfUuid"],
                "sessionA": left["sessionIndex"],
                "sessionB": right["sessionIndex"],
                "distance": total_variation(left[proportion_columns], right[proportion_columns]),
                "ownerDistanceDiff": abs(left["meanOwnerDistance"] - right["meanOwnerDistance"]),
            })
    return pd.DataFrame(rows)


def load_decisions(directory: Path) -> pd.DataFrame:
    """decisions.jsonl から、選択された行動だけを 1 行 1 決定で取り出す。"""
    records = read_jsonl(directory / "decisions.jsonl")
    if not records:
        return pd.DataFrame()
    rows = []
    for record in records:
        row = {
            "wolfUuid": record["wolfUuid"],
            "subjectId": record.get("subjectId"),
            "condition": record["experimentalCondition"],
            "worldTime": record["worldTime"],
            "decisionIndex": record["decisionIndex"],
            "selectedBehavior": record["selectedBehavior"],
            "selectedProbability": record["selectedProbability"],
            "biome": record.get("biome"),
            "x": record.get("x"),
            "y": record.get("y"),
            "z": record.get("z"),
            "memoryCount": len(record.get("relevantMemoryIds") or []),
        }
        row.update({f"s_{name}": record["state"].get(name, float("nan")) for name in STATES})
        rows.append(row)
    return pd.DataFrame(rows)


def load_score_components(directory: Path) -> pd.DataFrame:
    """全候補行動のスコア内訳を 1 行 1 候補で取り出す。

    「なぜその行動をしたのか」を説明するための表。選ばれた行動だけでなく
    選ばれなかった候補も含むので、どの成分が選択を決めたのかを比較できる。
    """
    records = read_jsonl(directory / "decisions.jsonl")
    rows = []
    for record in records:
        for score in record.get("behaviorScores", []):
            row = {
                "wolfUuid": record["wolfUuid"],
                "condition": record["experimentalCondition"],
                "worldTime": record["worldTime"],
                "decisionIndex": record["decisionIndex"],
                "behavior": score["behavior"],
                "probability": score["probability"],
                "selected": score["selected"],
                "finalScore": score["finalScore"],
            }
            row.update({name: score.get(name, 0.0) for name in COMPONENTS})
            row.update({name: score.get(name, float("nan")) for name in CONTEXT_BREAKDOWN})
            rows.append(row)
    return pd.DataFrame(rows)


def load_events(directory: Path) -> pd.DataFrame:
    """events.jsonl を 1 行 1 出来事の表にする。"""
    records = read_jsonl(directory / "events.jsonl")
    if not records:
        return pd.DataFrame()
    rows = []
    for record in records:
        row = {
            "wolfUuid": record["wolfUuid"],
            "subjectId": record.get("subjectId"),
            "condition": record["experimentalCondition"],
            "worldTime": record["worldTime"],
            "eventType": record["eventType"],
            "valence": record["valence"],
            "arousal": record["arousal"],
            "salience": record["salience"],
            "region": record.get("region"),
            "combatId": record.get("metadata", {}).get("combatId"),
            "hostileType": record.get("metadata", {}).get("hostileType"),
            "combatTrigger": record.get("metadata", {}).get("combatTrigger"),
            "damageOrigin": record.get("metadata", {}).get("damageOrigin"),
            "damageTotal": record.get("metadata", {}).get("damageTotal"),
            "damageAttributed": record.get("metadata", {}).get("damageAttributed"),
            "damageSourceCount": record.get("metadata", {}).get("damageSourceCount"),
            "hostileId": record.get("metadata", {}).get("hostileId"),
            "createdMemoryId": record.get("createdMemoryId"),
        }
        row.update({f"after_{name}": record.get("stateAfter", {}).get(name, float("nan"))
                    for name in STATES})
        # schemaVersion 3 以降は適用前の値と差分も入っている。適用後だけでは
        # 「その出来事がどれだけ効いたか」を測れない（飽和した個体では
        # 同じ出来事でも実際の変化量がまるで違う）。
        row.update({f"before_{name}": record.get("stateBefore", {}).get(name, float("nan"))
                    for name in STATES})
        row.update({f"delta_{name}": record.get("stateDelta", {}).get(name, float("nan"))
                    for name in STATES})
        for behaviour, value in record.get("preferenceDelta", {}).items():
            row[f"prefDelta_{behaviour}"] = value
        rows.append(row)
    return pd.DataFrame(rows)


def load_damage(directory: Path) -> pd.DataFrame:
    """damage.jsonl を 1 行 1 被弾の表にする。学習には使われない検証用のログ。"""
    records = read_jsonl(directory / "damage.jsonl")
    if not records:
        return pd.DataFrame()
    return pd.DataFrame(records)


def report_separation(distances: pd.DataFrame) -> pd.DataFrame:
    """ラベルごとに個体内距離と群間距離をまとめる。"""
    if distances.empty:
        return pd.DataFrame()
    summary = distances.pivot_table(index="label", columns="kind", values="distance",
                                    aggfunc=["mean", "std", "count"])
    summary.columns = ["_".join(str(part) for part in column) for column in summary.columns]
    if "mean_within" in summary and "mean_between" in summary:
        summary["separation_ratio"] = summary["mean_between"] / summary["mean_within"]
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("directory", type=Path, help="aiwolf-research ディレクトリ")
    parser.add_argument("-o", "--output", type=Path, default=None,
                        help="CSV の出力先（既定: <directory>/analysis）")
    arguments = parser.parse_args()

    directory: Path = arguments.directory
    if not directory.is_dir():
        print(f"ディレクトリが見つかりません: {directory}", file=sys.stderr)
        return 1
    output: Path = arguments.output or directory / "analysis"
    output.mkdir(parents=True, exist_ok=True)

    print(f"読み込み元: {directory}")
    print(f"出力先    : {output}\n")

    signatures = load_signatures(directory)
    decisions = load_decisions(directory)
    components = load_score_components(directory)
    events = load_events(directory)

    written: list[str] = []

    # --- 行動指紋と個体内 / 群間距離（仮説 H1・H2）-----------------------
    if signatures.empty:
        print("signatures.jsonl がありません。ゲーム内で /wolfai signature <label> を実行してください。\n")
    else:
        signatures.to_csv(output / "signatures.csv", index=False)
        written.append("signatures.csv")

        distances = signature_distances(signatures)
        if not distances.empty:
            distances.to_csv(output / "signature_distances.csv", index=False)
            written.append("signature_distances.csv")
            summary = report_separation(distances)
            summary.to_csv(output / "separation_summary.csv")
            written.append("separation_summary.csv")

            print("=== 行動指紋の距離（total variation）===")
            print(summary.to_string())
            print()
            print("individual signatures:")
            display_columns = ["wolfUuid", "label", "sessionIndex", "meanOwnerDistance"] \
                + [f"p_{name}" for name in BEHAVIOURS]
            frame = signatures[display_columns].copy()
            frame["wolfUuid"] = short_id(frame["wolfUuid"])
            print(frame.to_string(index=False))
            print()

    # --- 自由行動での行動分布（生態学的妥当性の確認）---------------------
    if decisions.empty:
        print("decisions.jsonl がありません。しばらくプレイしてから実行してください。\n")
    else:
        decisions.to_csv(output / "decisions.csv", index=False)
        written.append("decisions.csv")
        free_play = (decisions.groupby(["wolfUuid", "selectedBehavior"]).size()
                     .unstack(fill_value=0))
        free_play = free_play.div(free_play.sum(axis=1), axis=0)
        free_play = free_play.reindex(columns=BEHAVIOURS, fill_value=0.0)
        free_play.to_csv(output / "free_play_behaviour.csv")
        written.append("free_play_behaviour.csv")

        print("=== 自由行動での行動分布（参考。文脈が個体間で揃っていないので指紋の代わりにはならない）===")
        shown = free_play.copy()
        shown.index = short_id(pd.Series(shown.index)).values
        print(shown.round(3).to_string())
        print(f"\n決定回数: {len(decisions)}  個体数: {decisions['wolfUuid'].nunique()}\n")

    # --- スコア内訳（「なぜその行動をしたのか」）-------------------------
    if not components.empty:
        components.to_csv(output / "score_components.csv", index=False)
        written.append("score_components.csv")
        contribution = (components[components["selected"]]
                        .groupby("behavior")[COMPONENTS].mean()
                        .reindex(BEHAVIOURS))
        contribution.to_csv(output / "selected_score_components.csv")
        written.append("selected_score_components.csv")
        print("=== 選択された行動のスコア内訳の平均（一度も選ばれなかった行動は省略）===")
        print(contribution.dropna(how="all").round(3).to_string())
        print()

    # --- 出来事の内訳（履歴そのもの）------------------------------------
    if not events.empty:
        events.to_csv(output / "events.csv", index=False)
        written.append("events.csv")
        counts = events.groupby(["wolfUuid", "eventType"]).size().unstack(fill_value=0)
        counts.to_csv(output / "events_by_wolf.csv")
        written.append("events_by_wolf.csv")
        print("=== 個体ごとの経験した出来事 ===")
        shown = counts.copy()
        shown.index = short_id(pd.Series(shown.index)).values
        print(shown.to_string())
        print()

        # 1 回の戦闘から 2 件以上のイベントが出ていないかを検査する。
        # 出ていれば state・記憶・選好の 3 経路すべてに重複して入っており、
        # その個体のデータは 1 回の戦闘から過剰に学習している。
        combat = events.dropna(subset=["combatId"])
        if not combat.empty:
            per_combat = combat.groupby(["wolfUuid", "combatId"]).size()
            duplicated = per_combat[per_combat > 1]
            combat_summary = combat.groupby(["wolfUuid", "eventType"]).size().unstack(fill_value=0)
            combat_summary.to_csv(output / "combat_events.csv")
            written.append("combat_events.csv")
            print("=== 戦闘イベント ===")
            print(f"戦闘エピソード数: {len(per_combat)}  発火イベント数: {len(combat)}")
            if "combatTrigger" in combat.columns and combat["combatTrigger"].notna().any():
                print("開始のきっかけ:")
                print(combat["combatTrigger"].value_counts().to_string())
            if duplicated.empty:
                print("重複発火なし（1 戦闘 1 イベント）")
            else:
                print("!! 同一 combatId から複数のイベントが発火している:")
                print(duplicated.to_string())
            print()

    # --- 出来事ごとの実効的な影響量 ------------------------------------
    if not events.empty:
        delta_columns = [c for c in events.columns if c.startswith("delta_")]
        if delta_columns and events[delta_columns].notna().any().any():
            impact = events.groupby("eventType")[delta_columns].mean()
            impact.to_csv(output / "event_state_impact.csv")
            written.append("event_state_impact.csv")
            print("=== 出来事ごとの内部状態の平均変化量 ===")
            print("（適用前後の差分。飽和した個体では同じ出来事でも小さくなる）")
            print(impact.round(4).to_string())
            print()

    # --- ダメージの出どころ -------------------------------------------
    if not events.empty and "damageOrigin" in events.columns:
        damage = events.dropna(subset=["damageOrigin"])
        if not damage.empty:
            damage.to_csv(output / "damage_events.csv", index=False)
            written.append("damage_events.csv")
            print("=== 被弾の出どころ ===")
            print(damage["damageOrigin"].value_counts().to_string())
            multi = damage[damage["damageSourceCount"].astype(float) > 1]
            if not multi.empty:
                print(f"複数の相手から同時に受けた区間: {len(multi)} 件")
                print("（damageTotal と damageAttributed の差が、代表以外の相手からの被害）")
            print()

    # --- 被弾 1 発ごとの記録 -------------------------------------------
    damage = load_damage(directory)
    if not damage.empty:
        damage.to_csv(output / "damage_hits.csv", index=False)
        written.append("damage_hits.csv")
        definitions = set(damage.get("damageDefinition", pd.Series(dtype=str)).dropna())
        print("=== 被弾 1 発ごとの記録 ===")
        print(f"記録数: {len(damage)}  damage の定義: {', '.join(sorted(definitions)) or '不明'}")
        if "targetType" in damage.columns:
            print(damage["targetType"].value_counts().to_string())
        if "attackerType" in damage.columns:
            print("加害者の区分:")
            print(damage["attackerType"].value_counts().to_string())
        if "fatal" in damage.columns:
            print(f"致死攻撃: {int(damage['fatal'].fillna(False).astype(bool).sum())} 件")
        if len(definitions) > 1:
            print("!! damage の定義が途中で変わっている。解析前に確認すること")
        print()

    print("書き出したファイル: " + (", ".join(written) if written else "なし"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
