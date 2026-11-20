"""Human Study のログ（Phase 20〜24）を読む共通部品。

``player_behaviour.py`` と ``ai_availability.py`` が使う。単体では実行しない。

* ``sessions.jsonl`` から、セッションとフェーズの区間（開始・終了のワールド時刻）を作る
* 区間どうしの重なり [ticks] を求める

時刻はすべてワールド時刻 ``worldTime`` [ticks]（20 ticks = 1 秒）。``worldTime`` はワールドに保存され
サーバを再起動しても続くが、区間は念のため ``runId`` の中だけで閉じる（別の起動をまたがない）。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pandas as pd

TICKS_PER_MINUTE = 20 * 60

# phaseId が null の区間（セッション開始直後・フェーズ解除後）の表示名。
NO_PHASE = "(none)"

SEGMENT_COLUMNS = ["runId", "sessionId", "sessionLabel", "phaseId", "start", "end",
                   "durationTicks", "minutes", "endReason"]


def read_jsonl(path: Path, include_demo: bool = False) -> list[dict]:
    """JSONL を読む。ファイルがなければ空リスト。

    Demo Mode の行（``"demoMode": true``）は本実験の解析に混ぜないため、既定では除く。
    最後の行が書き込み途中で切れていることがあるので、読めない行は警告して読み飛ばす。
    """
    if not path.exists():
        return []
    records = []
    skipped_demo = 0
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                print(f"  warning: {path.name}:{line_number} を読み飛ばしました ({error})", file=sys.stderr)
                continue
            if record.get("demoMode") and not include_demo:
                skipped_demo += 1
                continue
            records.append(record)
    if skipped_demo:
        print(f"  note: {path.name} の Demo Mode の行 {skipped_demo} 件を除きました", file=sys.stderr)
    return records


def last_world_time_by_run(*frames: pd.DataFrame) -> dict[str, int]:
    """ログ全体で、実行（runId）ごとの最後のワールド時刻。閉じていない区間の終わりに使う。"""
    last: dict[str, int] = {}
    for frame in frames:
        if frame is None or frame.empty or "runId" not in frame.columns:
            continue
        for run_id, value in frame.groupby("runId")["worldTime"].max().items():
            last[run_id] = max(last.get(run_id, value), int(value))
    return last


def record_order(record: dict) -> tuple:
    """同じ実行の中で記録した順に並べるためのキー。

    ``logSequence``（封筒の版 3、Phase 25B）があればそれを使う。同じ tick に書かれた行の前後も復元できる。
    古い行（版 2 以前）では ``worldTime`` → ``epochMillis`` で近似する。
    """
    sequence = record.get("logSequence")
    if sequence is not None:
        return (record.get("runId") or "", 0, int(sequence), 0)
    return (record.get("runId") or "", 1, record.get("worldTime", 0), record.get("epochMillis", 0))


def phase_segments(sessions: list[dict], last_time: dict[str, int] | None = None) -> pd.DataFrame:
    """``sessions.jsonl`` の記録から、フェーズの区間を 1 行 1 区間で作る。

    SESSION_START でフェーズなし（``(none)``）の区間が始まり、PHASE_CHANGED のたびに区間が切り替わり、
    SESSION_STOP で閉じる。SESSION_STOP がない（クラッシュなど）セッションは、その実行のログの
    最後の時刻で閉じ、``endReason`` を ``END_OF_LOG`` にする。

    ``study_control`` の行（割付・記憶条件など）は区間を変えないので読まない。
    """
    last_time = last_time or {}
    rows = []
    open_segments: dict[tuple[str, str], dict] = {}

    def close(key, end, reason):
        segment = open_segments.pop(key)
        segment["end"] = int(end)
        segment["endReason"] = reason
        rows.append(segment)

    ordered = sorted((r for r in sessions if r.get("recordType", "session") == "session"),
                     key=record_order)
    for record in ordered:
        run_id = record.get("runId")
        session_id = record.get("targetSessionId") or record.get("sessionId")
        key = (run_id, session_id)
        event = record.get("event")
        time = int(record["worldTime"])
        if event == "SESSION_START":
            if key in open_segments:
                close(key, time, "RESTARTED")
            open_segments[key] = _segment(run_id, session_id, record.get("sessionLabel"), None, time)
        elif event == "PHASE_CHANGED":
            label = record.get("sessionLabel")
            if key in open_segments:
                label = open_segments[key]["sessionLabel"]
                close(key, time, "PHASE_CHANGED")
            open_segments[key] = _segment(run_id, session_id, label, record.get("toPhase"), time)
        elif event == "SESSION_STOP":
            if key in open_segments:
                close(key, time, record.get("reason") or "SESSION_STOP")

    for key in list(open_segments):
        start = open_segments[key]["start"]
        close(key, max(start, last_time.get(key[0], start)), "END_OF_LOG")

    if not rows:
        return pd.DataFrame(columns=SEGMENT_COLUMNS)
    frame = pd.DataFrame(rows)
    frame["durationTicks"] = frame["end"] - frame["start"]
    frame["minutes"] = frame["durationTicks"] / TICKS_PER_MINUTE
    return frame[SEGMENT_COLUMNS].sort_values(["runId", "start"]).reset_index(drop=True)


def _segment(run_id, session_id, label, phase, start):
    return {"runId": run_id, "sessionId": session_id, "sessionLabel": label,
            "phaseId": phase if phase else NO_PHASE, "start": start}


def phase_totals(segments: pd.DataFrame) -> pd.DataFrame:
    """同じセッション・同じフェーズの区間をまとめた長さ（フェーズを行き来した場合に合算する）。"""
    if segments.empty:
        return pd.DataFrame(columns=["runId", "sessionId", "sessionLabel", "phaseId", "durationTicks", "minutes"])
    return (segments.groupby(["runId", "sessionId", "sessionLabel", "phaseId"], dropna=False)[
        ["durationTicks", "minutes"]].sum().reset_index())


def overlap(start_a: int, end_a: int, start_b: int, end_b: int) -> int:
    """2 つの半開区間 [start, end) の重なり [ticks]。"""
    return max(0, min(end_a, end_b) - max(start_a, start_b))


def normalise_phase(series: pd.Series) -> pd.Series:
    """ログの ``phaseId``（null を含む）を区間表と同じ表記にそろえる。"""
    return series.where(series.notna(), NO_PHASE)
