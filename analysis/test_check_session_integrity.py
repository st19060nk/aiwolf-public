"""check_session_integrity.py の単体テスト（Phase 25D/E）。

    python -m unittest discover -s analysis -p "test_*.py"

20 分のセッション（BASELINE 3 分・TRAINING 12 分・MEMORY_TEST 3 分・EVALUATION 2 分）を合成し、
壊し方ごとに PASS / WARNING / FAIL が正しく出ることを確かめる。
"""
from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_session_integrity as checker  # noqa: E402
import study_logs  # noqa: E402

RUN = "run-20261114T120000Z-aaaaaa"
SESSION = "session-20261114T120100Z-bbbbbb"
SUBJECT = "subject-0123456789ab"
WOLF = "wolf-ba9876543210"
WOLF_UUID = "0f0e0d0c-0b0a-4908-8706-050403020100"
PLAYER_UUID = "99999999-1234-4abc-8def-999999999999"
MINUTE = 1200
PHASES = [("BASELINE", 0), ("TRAINING", 3), ("MEMORY_TEST", 15), ("EVALUATION", 18)]
END = 20 * MINUTE

VERSIONS = {"modVersion": "0.1.0-phase1", "buildRevision": "1b8deb0", "buildDirty": "false",
            "protocolVersion": 1, "parameterSetVersion": 2}


def phase_at(time: int) -> str | None:
    current = None
    for phase, minute in PHASES:
        if time >= minute * MINUTE:
            current = phase
    return current


def build_logs(**options) -> dict[str, list[dict]]:
    """ファイル名 → 行。logSequence は全ファイルをまたいで時刻順に振る。"""
    files: dict[str, list[dict]] = {name: [] for name, _ in checker.LOG_FILES}

    def add(name, time, record, session=True, phase=...):
        files[name].append({"runId": RUN, "sessionId": SESSION if session else None,
                            "phaseId": phase_at(time) if phase is ... else phase, "worldTime": time, **record})

    add("sessions.jsonl", 0, {"recordType": "session", "event": "SESSION_START", "targetSessionId": SESSION,
                              "sessionLabel": "p001-s01", "epochMillis": 0,
                              **({} if options.get("no_versions") else VERSIONS)}, phase=None)
    for phase, minute in PHASES:
        add("sessions.jsonl", minute * MINUTE, {"recordType": "session", "event": "PHASE_CHANGED",
                                                 "targetSessionId": SESSION, "toPhase": phase, "epochMillis": 1})
    if not options.get("no_stop"):
        add("sessions.jsonl", END, {"recordType": "session", "event": "SESSION_STOP", "targetSessionId": SESSION,
                                    "reason": "COMMAND", "epochMillis": 2})
    add("sessions.jsonl", 0, {"recordType": "study_control", "event": "ASSIGNMENT_SET",
                              "assignmentId": options.get("assignment", "A-01")}, session=False, phase=None)

    for time in range(0, END, 40):
        add("decisions.jsonl", time, {"wolfUuid": WOLF_UUID, "wolfSubjectId": WOLF,
                                      "selectedBehavior": "follow_close" if time % 120 else "explore_nearby",
                                      "relevantMemoryIds": [WOLF_UUID + "#m3"]})
    gap = options.get("sample_gap")
    for time in range(0, END, 20):
        if gap and gap[0] <= time < gap[1]:
            continue
        add("player-samples.jsonl", time, {"recordType": "player_sample", "subjectId": SUBJECT,
                                           "wolfSubjectId": WOLF, "wolfAiStatus": "ACTIVE",
                                           "context": {"ownerWolfDistance": 2.0}})
    if not options.get("no_player_events"):
        for time, event_type in ((4 * MINUTE, "PLAYER_PET"), (5 * MINUTE, "PLAYER_FEED")):
            add("player-events.jsonl", time, {"recordType": "player_event", "eventType": event_type,
                                              "subjectId": SUBJECT, "wolfSubjectId": WOLF,
                                              "details": options.get("details")})
    feeds = 2 if options.get("double_feed") else 1
    for _ in range(feeds):
        add("events.jsonl", 5 * MINUTE, {"eventType": "FEED", "wolfUuid": WOLF_UUID, "wolfSubjectId": WOLF,
                                         "createdMemoryId": WOLF_UUID + "#m4"})
    if options.get("suspended_minutes"):
        start = 6 * MINUTE
        add("ai-availability.jsonl", start, {"recordType": "ai_availability", "event": "AI_SUSPENDED",
                                             "reason": "SITTING", "wolfSubjectId": WOLF})
        add("ai-availability.jsonl", start + options["suspended_minutes"] * MINUTE,
            {"recordType": "ai_availability", "event": "AI_RESUMED", "previousReason": "SITTING",
             "wolfSubjectId": WOLF})

    # 全ファイルをまたいで、時刻順（同じ時刻ならファイル順）に通し番号を振る。
    everything = sorted(((record["worldTime"], index, name, record)
                         for index, (name, records) in enumerate(files.items()) for record in records),
                        key=lambda item: (item[0], item[1]))
    for sequence, (_, _, _, record) in enumerate(everything, start=1):
        record["logSequence"] = sequence
        record["logEnvelopeVersion"] = 3
    for records in files.values():
        records.sort(key=lambda r: r["logSequence"])
    return files


def write(directory: Path, files: dict[str, list[dict]]) -> None:
    for name, records in files.items():
        (directory / name).write_text("".join(json.dumps(r) + "\n" for r in records), encoding="utf-8")


def run_check(files, *, session=SESSION, **kwargs) -> checker.Report:
    with tempfile.TemporaryDirectory() as directory:
        write(Path(directory), files)
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return checker.check(Path(directory), session, **kwargs)


def categories(report: checker.Report, level: str) -> set[str]:
    return {category for finding_level, category, _ in report.findings if finding_level == level}


class IntegrityCheckerTest(unittest.TestCase):
    def test_clean_session_passes(self):
        report = run_check(build_logs(), player_names=["Researcher"], player_uuids=[PLAYER_UUID])
        self.assertEqual(report.verdict, checker.PASS, report.findings)

    def test_latest_resolves_to_the_last_session(self):
        self.assertEqual(run_check(build_logs(), session="latest").verdict, checker.PASS)

    def test_missing_stop_fails(self):
        report = run_check(build_logs(no_stop=True))
        self.assertEqual(report.verdict, checker.FAIL)
        self.assertIn("lifecycle", categories(report, checker.FAIL))

    def test_missing_version_fields_fail(self):
        report = run_check(build_logs(no_versions=True))
        self.assertIn("version", categories(report, checker.FAIL))

    def test_duplicate_log_sequence_fails(self):
        files = build_logs()
        files["player-events.jsonl"][0]["logSequence"] = files["decisions.jsonl"][5]["logSequence"]
        report = run_check(files)
        self.assertIn("sequence", categories(report, checker.FAIL))

    def test_sequence_going_backwards_within_a_file_fails(self):
        files = build_logs()
        samples = files["player-samples.jsonl"]
        samples[10], samples[11] = samples[11], samples[10]
        self.assertIn("sequence", categories(run_check(files), checker.FAIL))

    def test_missing_main_log_fails(self):
        files = build_logs()
        files["player-samples.jsonl"] = []
        self.assertIn("logs", categories(run_check(files), checker.FAIL))

    def test_sampling_gap_warns(self):
        report = run_check(build_logs(sample_gap=(8 * MINUTE, 8 * MINUTE + 60 * 20)))
        self.assertEqual(report.verdict, checker.WARNING, report.findings)
        self.assertIn("sampling", categories(report, checker.WARNING))

    def test_long_suspension_warns(self):
        report = run_check(build_logs(suspended_minutes=6))
        self.assertIn("availability", categories(report, checker.WARNING))

    def test_no_player_events_warns(self):
        report = run_check(build_logs(no_player_events=True))
        self.assertEqual(report.verdict, checker.WARNING)
        self.assertIn("player", categories(report, checker.WARNING))

    def test_missing_phase_warns(self):
        report = run_check(build_logs(), expected_phases=checker.EXPECTED_PHASES + ["FREE_PLAY"])
        self.assertIn("phase", categories(report, checker.WARNING))

    def test_double_feed_fails(self):
        self.assertIn("feed", categories(run_check(build_logs(double_feed=True)), checker.FAIL))

    def test_wolf_uuid_and_memory_ids_are_allowed(self):
        # build_logs は wolfUuid と「<wolfUuid>#m<n>」の記憶 ID を含む。それだけでは FAIL にならない。
        self.assertNotIn("privacy", categories(run_check(build_logs()), checker.FAIL))

    def test_player_uuid_is_detected(self):
        report = run_check(build_logs(details={"attackerId": PLAYER_UUID}))
        self.assertIn("privacy", categories(report, checker.FAIL))
        report = run_check(build_logs(details={"note": PLAYER_UUID.replace("-", "")}))
        self.assertIn("privacy", categories(report, checker.FAIL))

    def test_player_name_email_and_ip_are_detected(self):
        self.assertIn("privacy", categories(
            run_check(build_logs(details={"who": "Researcher"}), player_names=["Researcher"]), checker.FAIL))
        self.assertIn("privacy", categories(run_check(build_logs(assignment="taro@example.com")), checker.FAIL))
        self.assertIn("privacy", categories(run_check(build_logs(details={"ip": "192.168.0.12"})), checker.FAIL))

    def test_main_prints_verdict_and_exit_code(self):
        with tempfile.TemporaryDirectory() as directory:
            write(Path(directory), build_logs(no_player_events=True))
            output = io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
                code = checker.main([directory, SESSION])
            self.assertEqual(code, 1)
            self.assertTrue(output.getvalue().rstrip().endswith("PILOT DATA QUALITY: WARNING"))


class Phase25AdditionsTest(unittest.TestCase):
    def test_player_uuid_prefix_is_detected(self):
        report = run_check(build_logs(details={"note": PLAYER_UUID.split("-")[0] + "-x"}), player_uuids=[PLAYER_UUID])
        self.assertIn("privacy", categories(report, checker.FAIL))

    def test_many_unreadable_lines_fail_and_one_warns(self):
        with tempfile.TemporaryDirectory() as directory:
            write(Path(directory), build_logs())
            with (Path(directory) / "decisions.jsonl").open("a", encoding="utf-8") as handle:
                handle.write('{"truncated":')
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                one = checker.check(Path(directory), SESSION)
            self.assertIn("parse", categories(one, checker.WARNING))
            with (Path(directory) / "decisions.jsonl").open("a", encoding="utf-8") as handle:
                handle.write('\nnot json\nnot json either\n')
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                many = checker.check(Path(directory), SESSION)
            self.assertIn("parse", categories(many, checker.FAIL))

    def test_missing_optional_files(self):
        files = build_logs()
        del files["damage.jsonl"]
        del files["signatures.jsonl"]
        self.assertEqual(run_check(files).verdict, checker.PASS, "damage / signatures がないのは正常")
        files = build_logs()
        del files["ai-availability.jsonl"]
        self.assertIn("logs", categories(run_check(files), checker.WARNING))

    def test_old_logs_without_sequence_are_tolerated(self):
        files = build_logs()
        for record in files["events.jsonl"]:
            del record["logSequence"]
        report = run_check(files)
        self.assertNotIn("logs", categories(report, checker.FAIL))
        self.assertIn("logs", categories(report, checker.WARNING))

    def test_gap_threshold_is_ten_seconds(self):
        self.assertEqual(checker.MAX_SAMPLE_GAP_SECONDS, 10.0)
        short = run_check(build_logs(sample_gap=(8 * MINUTE, 8 * MINUTE + 8 * 20)))
        self.assertNotIn("sampling", categories(short, checker.WARNING), "9 秒の空白は WARNING にしない")


class RecordOrderTest(unittest.TestCase):
    def test_log_sequence_orders_records_within_the_same_tick(self):
        records = [{"runId": RUN, "worldTime": 100, "logSequence": 8, "event": "b"},
                   {"runId": RUN, "worldTime": 100, "logSequence": 7, "event": "a"}]
        self.assertEqual([r["event"] for r in sorted(records, key=study_logs.record_order)], ["a", "b"])


if __name__ == "__main__":
    unittest.main()
