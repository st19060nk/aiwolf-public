#!/usr/bin/env python3
"""Phase 21：プレイヤー側の行動ログを、セッション × フェーズごとに集計する（Study 3 用）。

    python analysis/player_behaviour.py "<world>/aiwolf-research" [-o <出力先>] [--near 7.0]

読むもの: ``sessions.jsonl`` / ``player-events.jsonl`` / ``player-samples.jsonl``、
FEED の重複検査のために ``events.jsonl``。CSV は既定で ``<ログ>/analysis/`` に出る。

| 出力 | 内容 |
|---|---|
| ``phase_segments.csv`` | セッションとフェーズの区間（開始・終了・長さ） |
| ``player_events.csv`` | ``player-events.jsonl`` の 1 行 1 行動（``details`` も列に展開） |
| ``player_event_rates.csv`` | セッション × フェーズ × 飼い主 × 個体ごとの行動の回数と 1 分あたりの回数 |
| ``player_samples_by_phase.csv`` | 同じ単位で、飼い主とオオカミの距離・近くにいた割合・移動・敵の有無 |
| ``feed_check.csv`` | 個体ごとの FEED の数、同じ tick の重複、修正前の行の数 |

1 分あたりの回数の分母は、フェーズの長さ（``sessions.jsonl`` から求めた壁の時間ではなくワールド時間）。
AI が止まっていた時間は差し引かない（プレイヤーの行動は AI の停止と関係なく起きるため）。
AI の稼働時間で割りたい場合は ``ai_availability.py`` の ``ai_availability_by_phase.csv`` を使う。

**個人情報**：ログ自体にプレイヤー名・UUID・座標がないので、ここでも匿名の ``subjectId`` だけを扱う。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import study_logs  # noqa: E402
from study_logs import NO_PHASE, read_jsonl  # noqa: E402

PLAYER_EVENT_TYPES = [
    "PLAYER_PET",
    "PLAYER_FEED",
    "PLAYER_ATTACK_HOSTILE",
    "PLAYER_PROTECTED_WOLF",
    "PLAYER_DAMAGED",
    "PLAYER_KILLED_HOSTILE",
]

# 飼い主の「そば」とみなす距離の既定値 [blocks]。FOLLOW_NORMAL で保とうとする距離（AiWolfParameters）と同じ。
DEFAULT_NEAR_DISTANCE = 7.0

# セッションの外で記録された行の表示名。
NO_SESSION = "(no session)"

UNIT = ["runId", "sessionId", "phaseId", "subjectId", "wolfSubjectId"]


def flatten_player_events(records: list[dict]) -> pd.DataFrame:
    """``player-events.jsonl`` を 1 行 1 行動の表にする。``context`` と ``details`` は列に展開する。"""
    rows = []
    for record in records:
        if record.get("recordType", "player_event") != "player_event":
            continue
        row = {key: record.get(key) for key in (
            "runId", "sessionId", "phaseId", "subjectId", "wolfSubjectId", "eventType",
            "worldTime", "epochMillis", "elapsedTicksFromSessionStart", "experimentalCondition",
            "playerLogSchemaVersion")}
        for key, value in (record.get("context") or {}).items():
            row[key] = value
        for key, value in (record.get("details") or {}).items():
            row[f"detail_{key}"] = value
        rows.append(row)
    frame = pd.DataFrame(rows)
    if not frame.empty:
        frame["sessionId"] = frame["sessionId"].where(frame["sessionId"].notna(), NO_SESSION)
        frame["phaseId"] = study_logs.normalise_phase(frame["phaseId"])
    return frame


def flatten_player_samples(records: list[dict]) -> pd.DataFrame:
    """``player-samples.jsonl`` を 1 行 1 サンプルの表にする。"""
    rows = []
    for record in records:
        if record.get("recordType", "player_sample") != "player_sample":
            continue
        row = {key: record.get(key) for key in (
            "runId", "sessionId", "phaseId", "subjectId", "wolfSubjectId", "worldTime",
            "experimentalCondition", "wolfCurrentBehavior", "wolfAiStatus")}
        context = record.get("context") or {}
        motion = record.get("motion") or {}
        row.update({
            "ownerWolfDistance": context.get("ownerWolfDistance"),
            "hostileNearby": context.get("hostileNearby"),
            "wolfInCombat": context.get("wolfInCombat"),
            "playerSpeed": motion.get("playerSpeed"),
            "wolfSpeed": motion.get("wolfSpeed"),
            "playerMoving": motion.get("playerMoving"),
            "wolfMoving": motion.get("wolfMoving"),
        })
        rows.append(row)
    frame = pd.DataFrame(rows)
    if not frame.empty:
        frame["sessionId"] = frame["sessionId"].where(frame["sessionId"].notna(), NO_SESSION)
        frame["phaseId"] = study_logs.normalise_phase(frame["phaseId"])
        for column in ("ownerWolfDistance", "playerSpeed", "wolfSpeed"):
            frame[column] = pd.to_numeric(frame[column], errors="coerce")
    return frame


def event_rates(events: pd.DataFrame, samples: pd.DataFrame, segments: pd.DataFrame) -> pd.DataFrame:
    """セッション × フェーズ × 飼い主 × 個体ごとの行動の回数と 1 分あたりの回数。

    行動が 1 回もなかった組も 0 として残すため、組はサンプルと行動の両方から集める
    （サンプルはセッション中 1 秒ごとに出るので、セッション中にいた組はほぼ必ず現れる）。
    セッションの外の行動は回数だけ出し、分母がないので 1 分あたりは NaN にする。
    """
    pairs = pd.concat([frame[UNIT] for frame in (events, samples) if not frame.empty and set(UNIT) <= set(frame.columns)],
                      ignore_index=True)
    if pairs.empty:
        return pd.DataFrame()
    pairs = pairs.drop_duplicates()
    if events.empty:
        counts = pd.DataFrame(columns=UNIT)
    else:
        counts = events.groupby(UNIT + ["eventType"]).size().unstack(fill_value=0).reset_index()
    table = pairs.merge(counts, on=UNIT, how="left")
    for event_type in PLAYER_EVENT_TYPES:
        table[event_type] = table[event_type].fillna(0).astype(int) if event_type in table else 0

    totals = study_logs.phase_totals(segments)
    table = table.merge(totals, on=["runId", "sessionId", "phaseId"], how="left")
    for event_type in PLAYER_EVENT_TYPES:
        table[f"perMin_{event_type}"] = table[event_type] / table["minutes"].where(table["minutes"] > 0)
    columns = UNIT[:2] + ["sessionLabel"] + UNIT[2:] + ["minutes"] + PLAYER_EVENT_TYPES + [
        f"perMin_{t}" for t in PLAYER_EVENT_TYPES]
    return table[columns].sort_values(UNIT).reset_index(drop=True)


def sample_summary(samples: pd.DataFrame, near_distance: float) -> pd.DataFrame:
    """セッション × フェーズ × 飼い主 × 個体ごとの、飼い主とオオカミの関係の要約。

    割合の分母はサンプル数。ただし距離に関わる割合は距離のあるサンプル（同じワールドにいた）だけ、
    移動の割合は移動量のあるサンプル（2 回目以降）だけを分母にする。
    """
    if samples.empty:
        return pd.DataFrame()
    frame = samples.copy()
    frame["near"] = (frame["ownerWolfDistance"] <= near_distance).where(frame["ownerWolfDistance"].notna())
    frame["aiActive"] = (frame["wolfAiStatus"] == "ACTIVE").where(frame["wolfAiStatus"].notna())
    for column in ("hostileNearby", "wolfInCombat", "playerMoving", "wolfMoving"):
        frame[column] = frame[column].map({True: 1.0, False: 0.0})
    frame["near"] = frame["near"].map({True: 1.0, False: 0.0})
    frame["aiActive"] = frame["aiActive"].map({True: 1.0, False: 0.0})

    grouped = frame.groupby(UNIT)
    summary = grouped.agg(
        samples=("worldTime", "size"),
        firstWorldTime=("worldTime", "min"),
        lastWorldTime=("worldTime", "max"),
        meanOwnerWolfDistance=("ownerWolfDistance", "mean"),
        medianOwnerWolfDistance=("ownerWolfDistance", "median"),
        fractionNear=("near", "mean"),
        fractionPlayerMoving=("playerMoving", "mean"),
        fractionWolfMoving=("wolfMoving", "mean"),
        meanPlayerSpeed=("playerSpeed", "mean"),
        meanWolfSpeed=("wolfSpeed", "mean"),
        fractionHostileNearby=("hostileNearby", "mean"),
        fractionWolfInCombat=("wolfInCombat", "mean"),
        fractionAiActive=("aiActive", "mean"),
    ).reset_index()
    # AI が稼働していたサンプルだけでの「そばにいた割合」。AI が座って止まっている間の距離を混ぜないため。
    active = frame[frame["aiActive"] == 1.0].groupby(UNIT)["near"].mean().rename("fractionNearWhileAiActive")
    summary = summary.merge(active.reset_index(), on=UNIT, how="left")
    summary.insert(summary.columns.get_loc("fractionNear") + 1, "nearDistance", near_distance)
    return summary.sort_values(UNIT).reset_index(drop=True)


def feed_check(directory: Path) -> pd.DataFrame:
    """``events.jsonl`` の FEED を個体ごとに数え、同じ tick の重複と修正前の行を検出する。

    255a617 より前は、1 回の餌やりで UseEntityCallback が 2 回（INTERACT_AT と INTERACT）発火し、
    FEED が同じ個体・同じ worldTime で 2 件書かれていた（撫ではクールダウンがあるので影響なし）。
    修正前の行は封筒（``logEnvelopeVersion``）を持たないので、それで見分ける。
    ``feedDeduplicated`` は同じ個体・同じ tick の FEED を 1 件と数え直した値。
    """
    rows = []
    for record in read_jsonl(directory / "events.jsonl"):
        if record.get("eventType") != "FEED":
            continue
        rows.append({
            "wolfUuid": record.get("wolfUuid"),
            "wolfSubjectId": record.get("wolfSubjectId"),
            "worldTime": record.get("worldTime"),
            "preFix": "logEnvelopeVersion" not in record,
        })
    if not rows:
        return pd.DataFrame()
    feeds = pd.DataFrame(rows)
    per_tick = feeds.groupby(["wolfUuid", "worldTime"]).size()
    summary = feeds.groupby("wolfUuid").agg(
        wolfSubjectId=("wolfSubjectId", "first"),
        feedRows=("worldTime", "size"),
        preFixRows=("preFix", "sum"),
    )
    summary["sameTickDuplicates"] = (per_tick[per_tick > 1] - 1).groupby(level=0).sum()
    summary["sameTickDuplicates"] = summary["sameTickDuplicates"].fillna(0).astype(int)
    summary["feedDeduplicated"] = summary["feedRows"] - summary["sameTickDuplicates"]
    return summary.reset_index()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("directory", type=Path, help="ワールドの aiwolf-research/")
    parser.add_argument("-o", "--output", type=Path, help="出力先（既定 <directory>/analysis）")
    parser.add_argument("--near", type=float, default=DEFAULT_NEAR_DISTANCE,
                        help=f"飼い主の「そば」とみなす距離 [blocks]（既定 {DEFAULT_NEAR_DISTANCE}）")
    args = parser.parse_args(argv)

    directory = args.directory
    output = args.output or directory / "analysis"
    output.mkdir(parents=True, exist_ok=True)
    written = []

    events = flatten_player_events(read_jsonl(directory / "player-events.jsonl"))
    samples = flatten_player_samples(read_jsonl(directory / "player-samples.jsonl"))
    segments = study_logs.phase_segments(read_jsonl(directory / "sessions.jsonl"),
                                         study_logs.last_world_time_by_run(events, samples))

    if not segments.empty:
        segments.to_csv(output / "phase_segments.csv", index=False)
        written.append("phase_segments.csv")
        print("=== セッションとフェーズ ===")
        print(study_logs.phase_totals(segments).round(2).to_string(index=False))
        unclosed = segments[segments["endReason"] == "END_OF_LOG"]
        if not unclosed.empty:
            print(f"!! SESSION_STOP のないセッション {unclosed['sessionId'].nunique()} 件"
                  "（ログの最後の時刻で閉じた。クラッシュの可能性）")
        print()
    elif not samples.empty or not events.empty:
        print("!! sessions.jsonl がない、または空。1 分あたりの回数は出せない", file=sys.stderr)

    if not events.empty:
        events.to_csv(output / "player_events.csv", index=False)
        written.append("player_events.csv")
        outside = int((events["sessionId"] == NO_SESSION).sum())
        if outside:
            print(f"note: セッションの外で記録された行動 {outside} 件（回数だけ集計、1 分あたりは NaN）")

    rates = event_rates(events, samples, segments)
    if not rates.empty:
        rates.to_csv(output / "player_event_rates.csv", index=False)
        written.append("player_event_rates.csv")
        print("=== プレイヤーの行動（回数） ===")
        shown = rates[["sessionLabel", "phaseId", "subjectId", "minutes"] + PLAYER_EVENT_TYPES].copy()
        shown.columns = [c.replace("PLAYER_", "") for c in shown.columns]
        print(shown.round(2).to_string(index=False))
        print()

    summary = sample_summary(samples, args.near)
    if not summary.empty:
        summary.to_csv(output / "player_samples_by_phase.csv", index=False)
        written.append("player_samples_by_phase.csv")
        print(f"=== 飼い主とオオカミの関係（そば = {args.near} blocks 以内） ===")
        print(summary[["phaseId", "subjectId", "samples", "meanOwnerWolfDistance", "fractionNear",
                       "fractionNearWhileAiActive", "fractionPlayerMoving", "fractionAiActive"]]
              .round(3).to_string(index=False))
        print()

    feeds = feed_check(directory)
    if not feeds.empty:
        feeds.to_csv(output / "feed_check.csv", index=False)
        written.append("feed_check.csv")
        duplicates = int(feeds["sameTickDuplicates"].sum())
        pre_fix = int(feeds["preFixRows"].sum())
        print("=== FEED の重複検査（events.jsonl） ===")
        print(f"FEED {int(feeds['feedRows'].sum())} 件、うち修正前の行 {pre_fix} 件、同じ tick の重複 {duplicates} 件")
        if duplicates:
            print("!! 同じ個体・同じ tick の FEED がある。修正前（255a617 より前）のデータでは 1 回の餌やりが 2 件になっている。")
            print("   回数は feedDeduplicated を使うこと。FEED は state・記憶・選好にも 2 回入っているので、")
            print("   その個体の学習結果そのものも 2 倍の餌やりを経験した状態になっている点に注意")
        print()

    print("書き出したファイル: " + (", ".join(written) if written else "なし"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
