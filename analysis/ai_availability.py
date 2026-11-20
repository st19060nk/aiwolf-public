#!/usr/bin/env python3
"""Phase 22：研究 AI が止まっていた区間を求め、フェーズごとの稼働時間と、稼働時間あたりの決定を出す。

    python analysis/ai_availability.py "<world>/aiwolf-research" [-o <出力先>]

読むもの: ``ai-availability.jsonl`` / ``sessions.jsonl`` / ``decisions.jsonl``（と、セッションにいた個体を
集めるための ``player-samples.jsonl``）。CSV は既定で ``<ログ>/analysis/`` に出る。

| 出力 | 内容 |
|---|---|
| ``ai_suspensions.csv`` | 停止区間。理由が途中で変わった停止は理由ごとに分け、同じ ``suspensionId`` を付ける |
| ``ai_availability_by_phase.csv`` | セッション × フェーズ × 個体ごとの、フェーズの長さ・理由別の停止時間・稼働時間・決定の数・行動の割合 |

**なぜ必要か**：決定ログの行動割合が 0 だったとき、「その行動を選ばなかった」のか「AI がそもそも
決定していなかった（座っていた・ロードされていなかった）」のかを区別するため。フェーズ間で行動を比べるときは、
フェーズの長さではなく ``availableMinutes`` を分母にすること。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import study_logs  # noqa: E402
from study_logs import TICKS_PER_MINUTE, read_jsonl  # noqa: E402

# AiAvailability.Reason と同じ順序。
REASONS = ["ENTITY_NOT_LOADED", "NOT_TAMED", "SITTING", "LEASHED", "NO_RECENT_DECISION"]

SUSPENSION_COLUMNS = ["runId", "wolfSubjectId", "suspensionId", "reason", "start", "end",
                      "durationTicks", "minutes", "endEvent", "loggedSuspendedDurationTicks"]


def suspension_intervals(records: list[dict], last_time: dict[str, int] | None = None) -> pd.DataFrame:
    """``ai-availability.jsonl`` から停止区間を作る（1 行 = 1 つの理由が続いた区間）。

    AI_SUSPENDED で開き、AI_SUSPENSION_REASON_CHANGED で理由を切り替え（停止は続く）、AI_RESUMED で閉じる。
    サーバ停止などで AI_RESUMED のない停止は、その実行のログの最後の時刻で閉じ、``endEvent`` を
    ``END_OF_LOG`` にする（本当の長さはそれ以上かもしれない）。

    ``loggedSuspendedDurationTicks`` は AI_RESUMED の行に書かれた停止全体の長さで、停止の最後の区間にだけ入る。
    同じ ``suspensionId`` の ``durationTicks`` の合計と一致するはず。
    """
    last_time = last_time or {}
    rows = []
    open_by_wolf: dict[tuple[str, str], dict] = {}
    counter = 0

    def close(key, end, event, logged=None):
        segment = open_by_wolf.pop(key)
        segment.update(end=int(end), endEvent=event, loggedSuspendedDurationTicks=logged)
        rows.append(segment)

    ordered = sorted((r for r in records if r.get("recordType", "ai_availability") == "ai_availability"),
                     key=study_logs.record_order)
    for record in ordered:
        key = (record.get("runId"), record.get("wolfSubjectId"))
        event = record.get("event")
        time = int(record["worldTime"])
        if event == "AI_SUSPENDED":
            if key in open_by_wolf:
                close(key, time, "SUSPENDED_AGAIN")
            counter += 1
            open_by_wolf[key] = {"runId": key[0], "wolfSubjectId": key[1], "suspensionId": counter,
                                 "reason": record.get("reason"), "start": time}
        elif event == "AI_SUSPENSION_REASON_CHANGED":
            if key in open_by_wolf:
                suspension_id = open_by_wolf[key]["suspensionId"]
                close(key, time, "REASON_CHANGED")
            else:
                # 停止の開始が記録されていない（ログの途中から読んだ）。開始は行の suspendedSinceWorldTime を使えないので、
                # この行から始める。
                counter += 1
                suspension_id = counter
            open_by_wolf[key] = {"runId": key[0], "wolfSubjectId": key[1], "suspensionId": suspension_id,
                                 "reason": record.get("reason"), "start": time}
        elif event == "AI_RESUMED" and key in open_by_wolf:
            close(key, time, "AI_RESUMED", record.get("suspendedDurationTicks"))

    for key in list(open_by_wolf):
        start = open_by_wolf[key]["start"]
        close(key, max(start, last_time.get(key[0], start)), "END_OF_LOG")

    if not rows:
        return pd.DataFrame(columns=SUSPENSION_COLUMNS)
    frame = pd.DataFrame(rows)
    frame["durationTicks"] = frame["end"] - frame["start"]
    frame["minutes"] = frame["durationTicks"] / TICKS_PER_MINUTE
    return frame[SUSPENSION_COLUMNS].sort_values(["runId", "wolfSubjectId", "start"]).reset_index(drop=True)


def load_selected_decisions(directory: Path) -> pd.DataFrame:
    """``decisions.jsonl`` から、集計に要る項目だけを 1 行 1 決定で読む（全候補の内訳は読まない）。"""
    rows = [{
        "runId": record.get("runId"),
        "sessionId": record.get("sessionId"),
        "phaseId": record.get("phaseId"),
        "wolfSubjectId": record.get("wolfSubjectId"),
        "worldTime": record.get("worldTime"),
        "selectedBehavior": record.get("selectedBehavior"),
    } for record in read_jsonl(directory / "decisions.jsonl")]
    frame = pd.DataFrame(rows)
    if not frame.empty:
        frame["phaseId"] = study_logs.normalise_phase(frame["phaseId"])
    return frame


def session_wolves(*frames: pd.DataFrame) -> pd.DataFrame:
    """セッションにいた個体（runId, sessionId, wolfSubjectId）。いずれかのログにそのセッションの行がある個体。"""
    parts = [frame[["runId", "sessionId", "wolfSubjectId"]] for frame in frames
             if frame is not None and not frame.empty and {"runId", "sessionId", "wolfSubjectId"} <= set(frame.columns)]
    if not parts:
        return pd.DataFrame(columns=["runId", "sessionId", "wolfSubjectId"])
    wolves = pd.concat(parts, ignore_index=True).dropna().drop_duplicates()
    return wolves.reset_index(drop=True)


def availability_by_phase(segments: pd.DataFrame, suspensions: pd.DataFrame, wolves: pd.DataFrame,
                          decisions: pd.DataFrame) -> pd.DataFrame:
    """セッション × フェーズ × 個体ごとの停止時間・稼働時間・決定の数・行動の割合。

    停止時間はフェーズの区間と停止区間の重なりで求める（停止がフェーズをまたいでも正しく分かれる）。
    同じフェーズを 2 回以上行き来した場合は合算する。
    """
    if segments.empty or wolves.empty:
        return pd.DataFrame()
    rows = []
    for segment in segments.itertuples(index=False):
        session_wolf_ids = wolves[(wolves.runId == segment.runId) & (wolves.sessionId == segment.sessionId)]
        for wolf in session_wolf_ids["wolfSubjectId"]:
            row = {"runId": segment.runId, "sessionId": segment.sessionId, "sessionLabel": segment.sessionLabel,
                   "phaseId": segment.phaseId, "wolfSubjectId": wolf, "durationTicks": segment.durationTicks}
            mine = suspensions[(suspensions.runId == segment.runId) & (suspensions.wolfSubjectId == wolf)]
            for reason in REASONS:
                row[f"suspendedTicks_{reason}"] = sum(
                    study_logs.overlap(segment.start, segment.end, s.start, s.end)
                    for s in mine[mine.reason == reason].itertuples(index=False))
            rows.append(row)
    if not rows:
        return pd.DataFrame()

    keys = ["runId", "sessionId", "sessionLabel", "phaseId", "wolfSubjectId"]
    table = pd.DataFrame(rows).groupby(keys, dropna=False).sum(numeric_only=True).reset_index()
    reason_columns = [f"suspendedTicks_{reason}" for reason in REASONS]
    table["minutes"] = table["durationTicks"] / TICKS_PER_MINUTE
    table["suspendedMinutes"] = table[reason_columns].sum(axis=1) / TICKS_PER_MINUTE
    for reason in REASONS:
        table[f"suspendedMinutes_{reason}"] = table[f"suspendedTicks_{reason}"] / TICKS_PER_MINUTE
    table["availableMinutes"] = table["minutes"] - table["suspendedMinutes"]
    table["fractionAvailable"] = table["availableMinutes"] / table["minutes"].where(table["minutes"] > 0)

    if decisions.empty:
        table["decisions"] = 0
    else:
        counted = decisions.groupby(["runId", "sessionId", "phaseId", "wolfSubjectId"]).size().rename("decisions")
        table = table.merge(counted.reset_index(), on=["runId", "sessionId", "phaseId", "wolfSubjectId"], how="left")
        table["decisions"] = table["decisions"].fillna(0).astype(int)
        proportions = (decisions.groupby(["runId", "sessionId", "phaseId", "wolfSubjectId"])["selectedBehavior"]
                       .value_counts(normalize=True).unstack(fill_value=0.0))
        proportions.columns = [f"p_{name}" for name in proportions.columns]
        table = table.merge(proportions.reset_index(), on=["runId", "sessionId", "phaseId", "wolfSubjectId"],
                            how="left")
    table["decisionsPerAvailableMinute"] = (
        table["decisions"] / table["availableMinutes"].where(table["availableMinutes"] > 0))

    head = keys + ["minutes", "availableMinutes", "fractionAvailable", "suspendedMinutes"] + [
        f"suspendedMinutes_{reason}" for reason in REASONS] + ["decisions", "decisionsPerAvailableMinute"]
    rest = sorted(c for c in table.columns if c.startswith("p_"))
    return table[head + rest].sort_values(keys).reset_index(drop=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("directory", type=Path, help="ワールドの aiwolf-research/")
    parser.add_argument("-o", "--output", type=Path, help="出力先（既定 <directory>/analysis）")
    args = parser.parse_args(argv)

    directory = args.directory
    output = args.output or directory / "analysis"
    output.mkdir(parents=True, exist_ok=True)
    written = []

    availability_records = read_jsonl(directory / "ai-availability.jsonl")
    availability = pd.DataFrame(availability_records)
    decisions = load_selected_decisions(directory)
    samples = pd.DataFrame(read_jsonl(directory / "player-samples.jsonl"))
    last_time = study_logs.last_world_time_by_run(availability, decisions, samples)

    suspensions = suspension_intervals(availability_records, last_time)
    if not suspensions.empty:
        suspensions.to_csv(output / "ai_suspensions.csv", index=False)
        written.append("ai_suspensions.csv")
        print("=== AI の停止（理由ごとの合計） ===")
        print(suspensions.groupby("reason").agg(count=("start", "size"), minutes=("minutes", "sum"))
              .round(2).to_string())
        unclosed = suspensions[suspensions["endEvent"] == "END_OF_LOG"]
        if not unclosed.empty:
            print(f"note: 再開の記録がない停止 {len(unclosed)} 件（ログの最後の時刻で閉じた。実際はもっと長い可能性）")
        totals = suspensions.groupby("suspensionId").agg(
            computed=("durationTicks", "sum"), logged=("loggedSuspendedDurationTicks", "max"))
        mismatched = totals.dropna(subset=["logged"])
        mismatched = mismatched[mismatched["computed"] != mismatched["logged"]]
        if not mismatched.empty:
            print(f"!! 区間から求めた停止の長さと、AI_RESUMED に記録された長さが合わない停止 {len(mismatched)} 件"
                  "（ログの欠落の可能性）")
        print()

    segments = study_logs.phase_segments(read_jsonl(directory / "sessions.jsonl"), last_time)
    wolves = session_wolves(availability, decisions, samples)
    table = availability_by_phase(segments, suspensions, wolves, decisions)
    if not table.empty:
        table.to_csv(output / "ai_availability_by_phase.csv", index=False)
        written.append("ai_availability_by_phase.csv")
        print("=== フェーズごとの AI の稼働 ===")
        print(table[["sessionLabel", "phaseId", "wolfSubjectId", "minutes", "availableMinutes", "fractionAvailable",
                     "decisions", "decisionsPerAvailableMinute"]].round(3).to_string(index=False))
        print("（decisionsPerAvailableMinute は決定間隔 2 秒なら最大約 30。大きく下回る場合は、記録されていない停止がある）")
        print()
    elif segments.empty:
        print("note: sessions.jsonl がない、または空。フェーズごとの集計は出さない")

    print("書き出したファイル: " + (", ".join(written) if written else "なし"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
