#!/usr/bin/env python3
"""Phase 25D/E：Human Study の 1 セッションのデータ品質を検査する。

    python analysis/check_session_integrity.py "<world>/aiwolf-research" <sessionId>
    python analysis/check_session_integrity.py "<world>/aiwolf-research" <sessionId> --forbidden-player-name Researcher
    python analysis/check_session_integrity.py "<world>/aiwolf-research" latest

``sessionId`` に ``latest`` を渡すと、``sessions.jsonl`` の最後に始まったセッションを検査する。

最後に次のどれかを出す（終了コードも同じ順に 0 / 1 / 2）。

    PILOT DATA QUALITY: PASS      … そのまま解析に使ってよい
    PILOT DATA QUALITY: WARNING   … 使えるが、注意点を確認してから使う（理由を一覧で出す）
    PILOT DATA QUALITY: FAIL      … 解析に使えない。原因を直してから取り直す

検査するもの：セッションの開始・終了、版の記録、フェーズ、各ログの件数と範囲、logSequence の重複・逆行、
決定の数と行動の分布、AI の稼働割合、プレイヤーのサンプリングの欠損、プレイヤーの行動の件数、
FEED の二重記録、個人情報の混入。閾値は下の定数（README に説明あり）。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ai_availability  # noqa: E402
import study_logs  # noqa: E402
from study_logs import TICKS_PER_MINUTE  # noqa: E402

# ----------------------------------------------------------------------
# 閾値（README「check_session_integrity.py の閾値」）。変えたら README も直すこと。
# ----------------------------------------------------------------------

#: 必ずあるはずのフェーズ。1 つでもなければ WARNING。
EXPECTED_PHASES = ["BASELINE", "TRAINING", "MEMORY_TEST", "EVALUATION"]
#: TRAINING がこれより短ければ WARNING [分]。
MIN_TRAINING_MINUTES = 10.0
#: プレイヤーのサンプリング間隔 [ticks]（AiWolfParameters.PLAYER_SAMPLE_INTERVAL_TICKS と同じ）。
SAMPLE_INTERVAL_TICKS = 20
#: 期待されるサンプル数に対する実際の割合がこれ未満なら WARNING。
MIN_SAMPLING_COVERAGE = 0.95
#: サンプルの間隔がこれ以上の区間があれば WARNING [秒]（1 秒ごとのはずなので 10 秒は明らかな欠損）。
MAX_SAMPLE_GAP_SECONDS = 10.0
#: 1 ファイルで JSON として読めない行がこれより多ければ FAIL。書き込み途中で切れた最後の 1 行は起こりうるので、
#: 1 行までは WARNING にとどめる。
MAX_PARSE_ERRORS_PER_FILE = 1
#: AI が稼働していた割合がこれ未満なら WARNING。
MIN_AI_ACTIVE_RATIO = 0.80
#: AI が稼働していた時間 1 分あたりの決定数がこれ未満なら WARNING（決定間隔 2 秒なら約 30）。
MIN_DECISIONS_PER_ACTIVE_MINUTE = 20.0
#: SESSION_START に必ずあるべき版の項目。1 つでもなければ FAIL。
REQUIRED_VERSION_FIELDS = ["modVersion", "buildRevision", "protocolVersion", "parameterSetVersion"]

#: ファイルがなければ WARNING にするもの（ないと検査の一部ができない）。damage / signatures は戦闘や測定が
#: なければ作られないので、なくても注記だけにする。
WARN_IF_MISSING = {"events.jsonl", "player-events.jsonl", "ai-availability.jsonl"}

#: 検査するファイル。required が True のファイルにセッションの行が 1 件もなければ FAIL（主要ログの欠落）。
LOG_FILES = [
    ("sessions.jsonl", True),
    ("decisions.jsonl", True),
    ("events.jsonl", False),
    ("damage.jsonl", False),
    ("player-events.jsonl", False),
    ("player-samples.jsonl", True),
    ("ai-availability.jsonl", False),
    ("signatures.jsonl", False),
]

PLAYER_EVENT_TYPES = ["PLAYER_PET", "PLAYER_FEED", "PLAYER_ATTACK_HOSTILE", "PLAYER_PROTECTED_WOLF",
                      "PLAYER_DAMAGED", "PLAYER_KILLED_HOSTILE"]

UUID_DASHED = re.compile(r"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
UUID_PLAIN = re.compile(r"(?i)(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
IPV4 = re.compile(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])")
IPV6 = re.compile(r"(?i)(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}(?![0-9a-f:])")

PASS, WARNING, FAIL = "PASS", "WARNING", "FAIL"
_LEVEL_ORDER = {PASS: 0, WARNING: 1, FAIL: 2}


@dataclass
class Line:
    """1 行。``raw`` は個人情報の検査に、``order`` はファイル内の順序の検査に使う。"""
    file: str
    order: int
    raw: str
    record: dict
    demo: bool = False


@dataclass
class Report:
    findings: list[tuple[str, str, str]] = field(default_factory=list)

    def add(self, level: str, category: str, message: str) -> None:
        self.findings.append((level, category, message))

    @property
    def verdict(self) -> str:
        return max((level for level, _, _ in self.findings), key=_LEVEL_ORDER.get, default=PASS)


# ----------------------------------------------------------------------
# 読み込み
# ----------------------------------------------------------------------

#: 読み込みの記録（load_all が毎回作り直す）。ファイル名 → 読めなかった行の数 / なかったファイル。
PARSE_ERRORS: Counter = Counter()
MISSING_FILES: set[str] = set()


def read_lines(path: Path, demo: bool = False) -> list[Line]:
    """JSONL をファイルの順のまま読む。読めない行は数えて読み飛ばす（書き込み途中の最後の行など）。"""
    if not path.exists():
        if not demo:
            MISSING_FILES.add(path.name)
        return []
    lines = []
    with path.open(encoding="utf-8") as handle:
        for number, raw in enumerate(handle, start=1):
            raw = raw.strip()
            if not raw:
                continue
            try:
                record = json.loads(raw)
            except json.JSONDecodeError as error:
                print(f"  warning: {path.name}:{number} を読み飛ばしました ({error})", file=sys.stderr)
                PARSE_ERRORS[("demo/" if demo else "") + path.name] += 1
                continue
            lines.append(Line(path.name, number, raw, record, demo or bool(record.get("demoMode"))))
    return lines


def load_all(directory: Path) -> dict[str, list[Line]]:
    """本実験のログと、Demo Mode のログ（``demo/``）を読む。Demo の行は通し番号の検査にだけ使う。"""
    PARSE_ERRORS.clear()
    MISSING_FILES.clear()
    logs = {name: read_lines(directory / name) for name, _ in LOG_FILES}
    demo_directory = directory / "demo"
    logs["__demo__"] = [line for name, _ in LOG_FILES for line in read_lines(demo_directory / name, demo=True)]
    return logs


def resolve_session_id(logs: dict[str, list[Line]], requested: str) -> str | None:
    starts = [line.record for line in logs["sessions.jsonl"]
              if line.record.get("recordType", "session") == "session" and line.record.get("event") == "SESSION_START"]
    if requested != "latest":
        return requested
    if not starts:
        return None
    starts.sort(key=lambda r: (r.get("epochMillis", 0), r.get("logSequence", 0)))
    return starts[-1].get("targetSessionId")


def session_lines(lines: list[Line], session_id: str) -> list[Line]:
    """そのセッションの行（封筒の sessionId、または sessions.jsonl の targetSessionId が一致）。Demo の行は除く。"""
    return [line for line in lines if not line.demo
            and (line.record.get("sessionId") == session_id or line.record.get("targetSessionId") == session_id)]


# ----------------------------------------------------------------------
# 検査
# ----------------------------------------------------------------------

def check_lifecycle(report: Report, session: list[Line], session_id: str):
    """SESSION_START と SESSION_STOP が 1 件ずつで、開始 < 終了。開始の行（版の記録あり）を返す。"""
    records = [line.record for line in session if line.record.get("recordType", "session") == "session"
               and line.record.get("targetSessionId") == session_id]
    starts = [r for r in records if r.get("event") == "SESSION_START"]
    stops = [r for r in records if r.get("event") == "SESSION_STOP"]
    print("=== セッション ===")
    if len(starts) != 1:
        report.add(FAIL, "lifecycle", f"SESSION_START が {len(starts)} 件（1 件のはず）")
    if len(stops) != 1:
        report.add(FAIL, "lifecycle", f"SESSION_STOP が {len(stops)} 件（1 件のはず）")
    if not starts:
        print("SESSION_START がない")
        return None, None
    start = starts[0]
    stop = stops[-1] if stops else None
    print(f"sessionId: {session_id}  label: {start.get('sessionLabel')}  runId: {start.get('runId')}")
    print(f"start worldTime: {start['worldTime']}  logSequence: {start.get('logSequence')}")
    if stop is not None:
        duration = stop["worldTime"] - start["worldTime"]
        print(f"stop  worldTime: {stop['worldTime']}  logSequence: {stop.get('logSequence')}  reason: {stop.get('reason')}")
        print(f"duration: {duration / TICKS_PER_MINUTE:.2f} min")
        if duration <= 0:
            report.add(FAIL, "lifecycle", f"セッションの長さが 0 以下（{duration} ticks）")
        if (start.get("logSequence") is not None and stop.get("logSequence") is not None
                and start.get("runId") == stop.get("runId") and stop["logSequence"] <= start["logSequence"]):
            report.add(FAIL, "lifecycle", "SESSION_STOP の logSequence が SESSION_START より前")
        if start.get("runId") != stop.get("runId"):
            report.add(FAIL, "lifecycle", "開始と終了が別の runId（サーバの再起動をまたいでいる）")
        if stop.get("reason") == "SERVER_STOPPING":
            report.add(WARNING, "lifecycle", "セッションはコマンドではなくサーバ停止で閉じられた（予定どおりか確認）")
    print()
    return start, stop


def check_versions(report: Report, start: dict | None) -> None:
    print("=== 版 ===")
    if start is None:
        report.add(FAIL, "version", "SESSION_START がないので版を確認できない")
        print()
        return
    for name in REQUIRED_VERSION_FIELDS + ["buildDirty", "sessionLogSchemaVersion", "logEnvelopeVersion"]:
        print(f"{name}: {start.get(name, '(なし)')}")
    missing = [name for name in REQUIRED_VERSION_FIELDS if start.get(name) in (None, "")]
    if missing:
        report.add(FAIL, "version", "SESSION_START に版の項目がない: " + ", ".join(missing))
    if start.get("buildRevision") == "UNKNOWN":
        report.add(WARNING, "version", "buildRevision が UNKNOWN（Git 情報のないビルド。どのコードか特定できない）")
    if str(start.get("buildDirty")) == "true":
        report.add(WARNING, "version", "buildDirty=true（未コミットの変更を含むビルド。buildRevision だけではコードを再現できない）")
    print()


def check_phases(report: Report, sessions: list[Line], start: dict | None, last_time: dict[str, int],
                 expected: list[str]):
    print("=== フェーズ ===")
    if start is None:
        print()
        return study_logs.phase_segments([])
    session_id = start.get("targetSessionId")
    records = [line.record for line in sessions if line.record.get("targetSessionId") == session_id]
    segments = study_logs.phase_segments(records, last_time)
    # 開始と同じ tick にフェーズを設定した場合の、長さ 0 の「フェーズなし」区間は表示しない。
    segments = segments[(segments.durationTicks > 0) | (segments.phaseId != study_logs.NO_PHASE)]
    if segments.empty:
        print("(区間なし)")
    else:
        for segment in segments.itertuples(index=False):
            print(f"{segment.phaseId:<12} {segment.start:>10} → {segment.end:>10}  {segment.minutes:6.2f} min"
                  f"  ({segment.endReason})")
    present = set(segments["phaseId"]) if not segments.empty else set()
    for phase in expected:
        if phase not in present:
            report.add(WARNING, "phase", f"フェーズ {phase} がない")
    if not segments.empty and "TRAINING" in present:
        training = segments[segments.phaseId == "TRAINING"]["minutes"].sum()
        if training < MIN_TRAINING_MINUTES:
            report.add(WARNING, "phase", f"TRAINING が {training:.1f} 分（{MIN_TRAINING_MINUTES:g} 分未満）")
    print()
    return segments


def check_logs(report: Report, logs: dict[str, list[Line]], session_id: str) -> dict[str, list[Line]]:
    """各ファイルのセッションの行の件数・時刻と番号の範囲。主要ログが空なら FAIL。"""
    print("=== ログ ===")
    print(f"{'file':<24}{'records':>9}{'first worldTime':>17}{'last worldTime':>16}{'first seq':>11}{'last seq':>10}")
    per_file = {}
    for name, required in LOG_FILES:
        lines = session_lines(logs[name], session_id)
        per_file[name] = lines
        times = [line.record.get("worldTime") for line in lines if line.record.get("worldTime") is not None]
        sequences = [line.record.get("logSequence") for line in lines if line.record.get("logSequence") is not None]
        print(f"{name:<24}{len(lines):>9}{_fmt(min(times, default=None)):>17}{_fmt(max(times, default=None)):>16}"
              f"{_fmt(min(sequences, default=None)):>11}{_fmt(max(sequences, default=None)):>10}")
        if required and not lines:
            report.add(FAIL, "logs", f"{name} にこのセッションの行が 1 件もない")
        if lines and len(sequences) < len(lines):
            report.add(WARNING, "logs", f"{name}：logSequence のない行が {len(lines) - len(sequences)} 件"
                       "（封筒の版 3 より前のビルド。ファイル間の順序は worldTime で近似するしかない）")
    print()
    return per_file


def check_sequences(report: Report, logs: dict[str, list[Line]], run_id: str | None) -> None:
    """実行（runId）の中で、logSequence の重複がなく、各ファイル内で逆行していないこと。欠番は数えるだけ。"""
    print("=== logSequence ===")
    if run_id is None:
        print()
        return
    by_value: dict[int, list[str]] = defaultdict(list)
    groups: dict[str, list[Line]] = defaultdict(list)
    for key, lines in logs.items():
        prefix = "demo/" if key == "__demo__" else ""
        for line in lines:
            groups[prefix + line.file].append(line)
    for name, lines in groups.items():
        previous = None
        backwards = 0
        for line in lines:
            if line.record.get("runId") != run_id or line.record.get("logSequence") is None:
                continue
            value = int(line.record["logSequence"])
            by_value[value].append(f"{name}:{line.order}")
            if previous is not None and value <= previous:
                backwards += 1
            previous = value
        if backwards:
            report.add(FAIL, "sequence", f"{name}：ファイル内で logSequence が逆行・重複した箇所 {backwards} 件")
    duplicates = {value: places for value, places in by_value.items() if len(places) > 1}
    if duplicates:
        example = next(iter(sorted(duplicates.items())))
        report.add(FAIL, "sequence", f"同じ runId で logSequence の重複 {len(duplicates)} 件（例：{example[0]} → {example[1]}）")
    if by_value:
        low, high = min(by_value), max(by_value)
        gaps = (high - low + 1) - len(by_value)
        print(f"runId {run_id}: {len(by_value)} 行、範囲 {low}–{high}、欠番 {gaps}（欠番は書き込み失敗か、"
              "検査対象外のファイルの行。エラーにはしない）")
    print()


def check_files(report: Report) -> None:
    """読めなかった行と、なかったファイル。"""
    print("=== ファイル ===")
    for name, count in sorted(PARSE_ERRORS.items()):
        level = FAIL if count > MAX_PARSE_ERRORS_PER_FILE else WARNING
        report.add(level, "parse", f"{name}：JSON として読めない行 {count} 件"
                   + ("（書き込み途中で切れた最後の行の可能性）" if level == WARNING else ""))
    for name in sorted(MISSING_FILES):
        if name in WARN_IF_MISSING:
            report.add(WARNING, "logs", f"{name} がない（このファイルを使う検査はできない）")
        else:
            print(f"note: {name} がない（このセッションで該当する記録がなければ作られない）")
    if not PARSE_ERRORS and not MISSING_FILES:
        print("すべてのファイルがあり、すべての行を読めた")
    print()


def check_decisions_by_phase(per_file: dict[str, list[Line]], segments) -> None:
    """フェーズごとの決定の数・1 分あたり・行動の分布（全個体の合計）。"""
    print("=== フェーズごとの決定 ===")
    decisions = [line.record for line in per_file["decisions.jsonl"]]
    if not decisions or segments is None or segments.empty:
        print("(なし)")
        print()
        return
    minutes = segments.groupby("phaseId")["minutes"].sum().to_dict()
    by_phase: dict[str, list[dict]] = defaultdict(list)
    for record in decisions:
        by_phase[record.get("phaseId") or study_logs.NO_PHASE].append(record)
    order = list(dict.fromkeys(list(segments["phaseId"]) + sorted(by_phase)))
    for phase in order:
        records = by_phase.get(phase, [])
        length = minutes.get(phase, 0.0)
        rate = f"{len(records) / length:.1f}" if length > 0 else "-"
        distribution = Counter(r.get("selectedBehavior") for r in records)
        shown = ", ".join(f"{name} {count / len(records):.0%}" for name, count in distribution.most_common(4))
        print(f"{phase:<12} decisions: {len(records):>5}  per min: {rate:>5}  {shown}")
    print()


def session_window(start: dict | None, stop: dict | None, last_time: dict[str, int]) -> tuple[int, int] | None:
    if start is None:
        return None
    begin = int(start["worldTime"])
    end = int(stop["worldTime"]) if stop is not None else last_time.get(start.get("runId"), begin)
    return begin, end


def check_decisions_and_availability(report: Report, per_file: dict[str, list[Line]], logs: dict[str, list[Line]],
                                     start: dict | None, window: tuple[int, int] | None,
                                     last_time: dict[str, int]) -> None:
    print("=== オオカミの決定と AI の稼働 ===")
    if window is None or window[1] <= window[0]:
        print()
        return
    run_id = start.get("runId")
    begin, end = window
    minutes = (end - begin) / TICKS_PER_MINUTE
    decisions = [line.record for line in per_file["decisions.jsonl"]]
    wolves = sorted({r.get("wolfSubjectId") for name in ("decisions.jsonl", "player-samples.jsonl", "ai-availability.jsonl")
                     for r in (line.record for line in per_file[name]) if r.get("wolfSubjectId")})
    if not wolves:
        report.add(FAIL, "decisions", "このセッションに研究オオカミの記録がない")
    availability_records = [line.record for line in logs["ai-availability.jsonl"]
                            if not line.demo and line.record.get("runId") == run_id]
    intervals = ai_availability.suspension_intervals(availability_records, last_time)
    for wolf in wolves:
        mine = [r for r in decisions if r.get("wolfSubjectId") == wolf]
        suspended_by_reason: Counter = Counter()
        if not intervals.empty:
            for row in intervals[intervals.wolfSubjectId == wolf].itertuples(index=False):
                suspended_by_reason[row.reason] += study_logs.overlap(begin, end, row.start, row.end)
        suspended = sum(suspended_by_reason.values())
        active = (end - begin) - suspended
        ratio = active / (end - begin)
        active_minutes = active / TICKS_PER_MINUTE
        rate_active = len(mine) / active_minutes if active_minutes > 0 else float("nan")
        print(f"[{wolf}]")
        print(f"  decisions: {len(mine)}  per session minute: {len(mine) / minutes:.1f}"
              f"  per active minute: {rate_active:.1f}")
        print(f"  active: {active_minutes:.2f} min  suspended: {suspended / TICKS_PER_MINUTE:.2f} min"
              f"  active ratio: {ratio:.1%}")
        if suspended_by_reason:
            print("  suspension reasons: " + ", ".join(
                f"{reason} {ticks / TICKS_PER_MINUTE:.2f} min" for reason, ticks in suspended_by_reason.most_common()))
        distribution = Counter(r.get("selectedBehavior") for r in mine)
        if distribution:
            print("  selected behaviour: " + ", ".join(
                f"{name} {count / len(mine):.1%}" for name, count in distribution.most_common()))
        if ratio < MIN_AI_ACTIVE_RATIO:
            report.add(WARNING, "availability", f"{wolf}：AI の稼働割合 {ratio:.1%}（{MIN_AI_ACTIVE_RATIO:.0%} 未満）")
        if active_minutes > 0 and rate_active < MIN_DECISIONS_PER_ACTIVE_MINUTE:
            report.add(WARNING, "decisions", f"{wolf}：稼働 1 分あたりの決定 {rate_active:.1f}"
                       f"（{MIN_DECISIONS_PER_ACTIVE_MINUTE:g} 未満。記録されていない停止の可能性）")
    print()


def check_sampling(report: Report, per_file: dict[str, list[Line]], window: tuple[int, int] | None) -> None:
    print("=== プレイヤーのサンプリング ===")
    if window is None or window[1] <= window[0]:
        print()
        return
    begin, end = window
    expected = (end - begin) // SAMPLE_INTERVAL_TICKS
    by_pair: dict[tuple, list[int]] = defaultdict(list)
    for line in per_file["player-samples.jsonl"]:
        record = line.record
        by_pair[(record.get("subjectId"), record.get("wolfSubjectId"))].append(int(record["worldTime"]))
    for (subject, wolf), times in sorted(by_pair.items(), key=lambda item: str(item[0])):
        times.sort()
        intervals = [b - a for a, b in zip(times, times[1:])]
        coverage = len(times) / expected if expected else float("nan")
        ordered = sorted(intervals)
        median = ordered[len(ordered) // 2] / 20 if ordered else float("nan")
        p95 = ordered[min(len(ordered) - 1, int(round(0.95 * (len(ordered) - 1))))] / 20 if ordered else float("nan")
        # 開始直後・終了直前の欠けも欠損として数える。
        edges = [times[0] - begin, end - times[-1]]
        max_gap = max(intervals + edges) / 20
        print(f"[{subject} / {wolf}] expected: {expected}  actual: {len(times)}  coverage: {coverage:.1%}"
              f"  median interval: {median:.2f} s  p95 interval: {p95:.2f} s  max gap: {max_gap:.1f} s")
        if coverage < MIN_SAMPLING_COVERAGE:
            report.add(WARNING, "sampling", f"{wolf}：サンプリングの網羅率 {coverage:.1%}（{MIN_SAMPLING_COVERAGE:.0%} 未満）")
        if max_gap >= MAX_SAMPLE_GAP_SECONDS:
            long_gaps = sum(1 for gap in intervals if gap / 20 >= MAX_SAMPLE_GAP_SECONDS)
            report.add(WARNING, "sampling", f"{wolf}：{MAX_SAMPLE_GAP_SECONDS:g} 秒以上の欠損 {max(long_gaps, 1)} 区間"
                       f"（最大 {max_gap:.1f} 秒）")
    print()


def check_player_events(report: Report, per_file: dict[str, list[Line]]) -> None:
    print("=== プレイヤーの行動 ===")
    counts = Counter(line.record.get("eventType") for line in per_file["player-events.jsonl"])
    for event_type in PLAYER_EVENT_TYPES:
        print(f"{event_type:<24}{counts.get(event_type, 0):>6}")
    if not counts:
        report.add(WARNING, "player", "プレイヤーの行動が 0 件（撫で・餌やりをしなかったか、記録されていない）")
    print()


def check_feed_duplicates(report: Report, per_file: dict[str, list[Line]]) -> None:
    """同じ個体・同じ tick の FEED / PLAYER_FEED が 2 件以上なら FAIL（255a617 より前の二重記録の再発）。"""
    for name, event_type in (("events.jsonl", "FEED"), ("player-events.jsonl", "PLAYER_FEED")):
        keys = Counter((line.record.get("wolfSubjectId") or line.record.get("wolfUuid"), line.record.get("worldTime"))
                       for line in per_file[name] if line.record.get("eventType") == event_type)
        duplicated = {key: count for key, count in keys.items() if count > 1}
        if duplicated:
            report.add(FAIL, "feed", f"{name}：同じ個体・同じ tick の {event_type} が {len(duplicated)} 組（二重記録）")


def _strings(value, key=None):
    """JSON の値から (key, 文字列) を再帰的に取り出す。"""
    if isinstance(value, dict):
        for child_key, child in value.items():
            yield child_key, child_key
            yield from _strings(child, child_key)
    elif isinstance(value, list):
        for child in value:
            yield from _strings(child, key)
    elif isinstance(value, str):
        yield key, value


def scan_privacy(report: Report, lines: list[Line], player_names: list[str], player_uuids: list[str]) -> None:
    """個人情報の混入を検査する。

    * オオカミのエンティティ UUID（``wolfUuid``）は既存のログとの互換のため残しているので、
      同じ実行で ``wolfUuid`` に現れた UUID（記憶 ID ``<wolfUuid>#m<n>`` の中を含む）だけは許す。
      それ以外の UUID の形（ハイフンあり・なし）はプレイヤー UUID の可能性があるので FAIL。
    * ``--forbidden-player-uuid`` / ``--forbidden-player-name`` で渡した値は、行のどこに出ても FAIL（大文字小文字を区別しない）。
      UUID は先頭部分（最初の 8 桁 + ハイフン、ハイフンなしの先頭 12 桁）も探す。
    * メールアドレス・IP アドレスの形の文字列は FAIL。
    """
    print("=== 個人情報 ===")
    wolf_uuids = {str(line.record["wolfUuid"]).lower() for line in lines if line.record.get("wolfUuid")}
    forbidden = [value.lower() for value in player_names if value]
    for value in player_uuids:
        if value:
            plain = value.replace("-", "").lower()
            # 生の UUID と、その先頭部分（ハイフンありの最初の 8 桁・ハイフンなしの先頭 12 桁）も探す。
            forbidden += [value.lower(), plain, value.lower().split("-")[0] + "-", plain[:12]]
    hits: Counter = Counter()
    examples: dict[str, str] = {}
    for line in lines:
        lowered = line.raw.lower()
        for value in forbidden:
            if value in lowered:
                hits["指定したプレイヤー名・UUID"] += 1
                examples.setdefault("指定したプレイヤー名・UUID", f"{line.file}:{line.order}")
        for key, text in _strings(line.record):
            remainder = text.lower()
            for known in wolf_uuids:
                remainder = remainder.replace(known, "")
            for label, pattern in (("UUID の形（wolfUuid 以外）", UUID_DASHED), ("32 桁の 16 進（UUID の可能性）", UUID_PLAIN),
                                   ("メールアドレスの形", EMAIL), ("IPv4 アドレスの形", IPV4),
                                   ("IPv6 アドレスの形", IPV6)):
                if pattern.search(remainder):
                    hits[label] += 1
                    examples.setdefault(label, f"{line.file}:{line.order} の {key}")
    print(f"検査した行: {len(lines)}  許可したオオカミの UUID: {len(wolf_uuids)}"
          f"  指定した名前・UUID: {len(player_names)} / {len(player_uuids)}")
    if not player_names and not player_uuids:
        print("note: --forbidden-player-name / --forbidden-player-uuid を指定すると、テスト環境のプレイヤーの名前と UUID も直接探す")
    for label, count in hits.items():
        report.add(FAIL, "privacy", f"{label} が {count} 件（例：{examples[label]}）")
    if not hits:
        print("見つからなかった")
    print()


def _fmt(value) -> str:
    return "-" if value is None else str(value)


# ----------------------------------------------------------------------

def check(directory: Path, requested_session: str, player_names: list[str] | None = None,
          player_uuids: list[str] | None = None, expected_phases: list[str] | None = None) -> Report:
    report = Report()
    logs = load_all(directory)
    session_id = resolve_session_id(logs, requested_session)
    if session_id is None:
        report.add(FAIL, "lifecycle", "sessions.jsonl にセッションがない")
        return report

    sessions_for_session = session_lines(logs["sessions.jsonl"], session_id)
    start, stop = check_lifecycle(report, sessions_for_session, session_id)
    run_id = start.get("runId") if start else None
    last_time: dict[str, int] = {}
    for lines in logs.values():
        for line in lines:
            record = line.record
            if record.get("runId") and record.get("worldTime") is not None:
                last_time[record["runId"]] = max(last_time.get(record["runId"], 0), int(record["worldTime"]))

    check_versions(report, start)
    segments = check_phases(report, sessions_for_session, start, last_time,
                            EXPECTED_PHASES if expected_phases is None else expected_phases)
    check_files(report)
    per_file = check_logs(report, logs, session_id)
    check_sequences(report, logs, run_id)
    window = session_window(start, stop, last_time)
    check_decisions_and_availability(report, per_file, logs, start, window, last_time)
    check_decisions_by_phase(per_file, segments)
    check_sampling(report, per_file, window)
    check_player_events(report, per_file)
    check_feed_duplicates(report, per_file)
    # 個人情報は、そのセッションの全行と、同じ実行の実験者の操作（割付など、セッションの外の行を含む）を検査する。
    controls = [line for line in logs["sessions.jsonl"]
                if line.record.get("runId") == run_id and line not in sessions_for_session]
    scan_privacy(report, [line for lines in per_file.values() for line in lines] + controls,
                 player_names or [], player_uuids or [])
    return report


def print_summary(report: Report) -> None:
    print("=== 判定の理由 ===")
    if not report.findings:
        print("問題なし")
    for level in (FAIL, WARNING):
        for finding_level, category, message in report.findings:
            if finding_level == level:
                print(f"[{level}] {category}: {message}")
    print()
    print(f"PILOT DATA QUALITY: {report.verdict}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("directory", type=Path, help="ワールドの aiwolf-research/")
    parser.add_argument("session_id", help="検査する sessionId（latest で最後のセッション）")
    parser.add_argument("--forbidden-player-name", "--player-name", dest="player_name", action="append", default=[],
                        help="ログに出てはならないプレイヤー名（テスト環境の名前。複数指定可）")
    parser.add_argument("--forbidden-player-uuid", "--player-uuid", dest="player_uuid", action="append", default=[],
                        help="ログに出てはならないプレイヤー UUID（複数指定可）")
    parser.add_argument("--expect-phases", default=",".join(EXPECTED_PHASES),
                        help="必ずあるはずのフェーズ（カンマ区切り。空文字で検査しない）")
    args = parser.parse_args(argv)
    expected = [p.strip().upper() for p in args.expect_phases.split(",") if p.strip()]
    report = check(args.directory, args.session_id, args.player_name, args.player_uuid, expected)
    print_summary(report)
    return _LEVEL_ORDER[report.verdict]


if __name__ == "__main__":
    # Windows のコンソール（cp932）でも日本語を出せるようにする。
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
