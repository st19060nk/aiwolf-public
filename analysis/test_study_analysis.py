"""Human Study の解析（player_behaviour.py / ai_availability.py）の単体テスト。

    python -m unittest discover -s analysis -p "test_*.py"

ログの行は Java 側（SessionLogJson / PlayerLogJson / AiAvailabilityLogJson / LogEnvelope）が書く形に合わせた最小限のもの。
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
import ai_availability  # noqa: E402
import player_behaviour  # noqa: E402
import study_logs  # noqa: E402

RUN = "run-20261114T120000Z-aaaaaa"
SESSION = "session-20261114T120100Z-bbbbbb"
SUBJECT = "subject-000000000001"
WOLF = "wolf-000000000001"
MINUTE = 1200


def envelope(record: dict, phase: str | None, session: str | None = SESSION) -> dict:
    return {"logEnvelopeVersion": 2, "runId": RUN, "sessionId": session, "phaseId": phase, **record}


def session_line(event: str, time: int, phase: str | None, **details) -> dict:
    return envelope({"recordType": "session", "event": event, "targetSessionId": SESSION,
                     "sessionLabel": "P01-S1", "worldTime": time, "epochMillis": time, **details}, phase)


# セッション: 0–2 分はフェーズなし、2–7 分 TRAINING、7–10 分 MEMORY_TEST、10 分で終了。
SESSIONS = [
    session_line("SESSION_START", 0, None),
    session_line("PHASE_CHANGED", 2 * MINUTE, "TRAINING", fromPhase=None, toPhase="TRAINING"),
    session_line("PHASE_CHANGED", 7 * MINUTE, "MEMORY_TEST", fromPhase="TRAINING", toPhase="MEMORY_TEST"),
    session_line("SESSION_STOP", 10 * MINUTE, "MEMORY_TEST", reason="COMMAND", lastPhase="MEMORY_TEST"),
]


def player_event(event_type: str, time: int, phase: str | None, session: str | None = SESSION) -> dict:
    return envelope({"recordType": "player_event", "playerLogSchemaVersion": 1, "eventType": event_type,
                     "subjectId": SUBJECT, "wolfSubjectId": WOLF, "worldTime": time,
                     "experimentalCondition": "full",
                     "context": {"ownerWolfDistance": 2.0, "wolfHealthRatio": 1.0, "hostileNearby": False,
                                 "hostileCount": 0, "wolfInCombat": False},
                     "details": {"hostileType": "minecraft:zombie"} if "HOSTILE" in event_type else None},
                    phase, session)


def sample(time: int, phase: str, distance: float, moving: bool, status: str) -> dict:
    return envelope({"recordType": "player_sample", "playerLogSchemaVersion": 1, "subjectId": SUBJECT,
                     "wolfSubjectId": WOLF, "worldTime": time, "experimentalCondition": "full",
                     "context": {"ownerWolfDistance": distance, "wolfHealthRatio": 1.0, "hostileNearby": False,
                                 "hostileCount": 0, "wolfInCombat": False},
                     "motion": {"intervalTicks": 20, "playerDistanceMoved": 1.0 if moving else 0.0,
                                "playerSpeed": 1.0 if moving else 0.0, "playerMoving": moving},
                     "wolfAiStatus": status}, phase)


def availability(event: str, time: int, phase: str | None, reason=None, previous=None, duration=None) -> dict:
    record = {"recordType": "ai_availability", "aiAvailabilityLogSchemaVersion": 1, "event": event,
              "subjectId": SUBJECT, "wolfSubjectId": WOLF, "experimentalCondition": "full", "worldTime": time}
    if reason:
        record["reason"] = reason
    if previous:
        record["previousReason"] = previous
    if duration is not None:
        record["suspendedDurationTicks"] = duration
    return envelope(record, phase)


def write_jsonl(path: Path, records: list[dict]) -> None:
    path.write_text("".join(json.dumps(r) + "\n" for r in records), encoding="utf-8")


class PhaseSegmentTest(unittest.TestCase):
    def test_segments_follow_phase_changes(self):
        segments = study_logs.phase_segments(SESSIONS)
        self.assertEqual(list(segments.phaseId), [study_logs.NO_PHASE, "TRAINING", "MEMORY_TEST"])
        self.assertEqual(list(segments.minutes), [2.0, 5.0, 3.0])
        self.assertEqual(list(segments.endReason), ["PHASE_CHANGED", "PHASE_CHANGED", "COMMAND"])

    def test_session_without_stop_is_closed_at_end_of_log(self):
        segments = study_logs.phase_segments(SESSIONS[:2], {RUN: 4 * MINUTE})
        self.assertEqual(segments.iloc[-1].end, 4 * MINUTE)
        self.assertEqual(segments.iloc[-1].endReason, "END_OF_LOG")

    def test_overlap(self):
        self.assertEqual(study_logs.overlap(0, 10, 5, 20), 5)
        self.assertEqual(study_logs.overlap(0, 10, 10, 20), 0)

    def test_demo_lines_are_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "x.jsonl"
            path.write_text('{"demoMode":true,"a":1}\n{"a":2}\n{"a":', encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(study_logs.read_jsonl(path), [{"a": 2}])


class PlayerBehaviourTest(unittest.TestCase):
    def setUp(self):
        self.events = player_behaviour.flatten_player_events([
            player_event("PLAYER_PET", 3 * MINUTE, "TRAINING"),
            player_event("PLAYER_PET", 4 * MINUTE, "TRAINING"),
            player_event("PLAYER_FEED", 5 * MINUTE, "TRAINING"),
            player_event("PLAYER_ATTACK_HOSTILE", 8 * MINUTE, "MEMORY_TEST"),
            player_event("PLAYER_PET", 20 * MINUTE, None, session=None),
        ])
        self.samples = player_behaviour.flatten_player_samples([
            sample(3 * MINUTE, "TRAINING", 2.0, True, "ACTIVE"),
            sample(3 * MINUTE + 20, "TRAINING", 10.0, False, "ACTIVE"),
            sample(3 * MINUTE + 40, "TRAINING", 3.0, False, "SUSPENDED:SITTING"),
            sample(3 * MINUTE + 60, "TRAINING", 20.0, True, "SUSPENDED:SITTING"),
            sample(8 * MINUTE, "MEMORY_TEST", 1.0, False, "ACTIVE"),
        ])
        self.segments = study_logs.phase_segments(SESSIONS)

    def test_rates_use_phase_length_and_keep_zero_counts(self):
        rates = player_behaviour.event_rates(self.events, self.samples, self.segments).set_index("phaseId")
        training = rates.loc["TRAINING"]
        self.assertEqual(training.PLAYER_PET, 2)
        self.assertEqual(training.PLAYER_FEED, 1)
        self.assertEqual(training.PLAYER_ATTACK_HOSTILE, 0)
        self.assertAlmostEqual(training.perMin_PLAYER_PET, 2 / 5)
        self.assertAlmostEqual(rates.loc["MEMORY_TEST"].perMin_PLAYER_ATTACK_HOSTILE, 1 / 3)
        outside = rates[rates.sessionId == player_behaviour.NO_SESSION].iloc[0]
        self.assertEqual(outside.PLAYER_PET, 1)
        self.assertTrue(outside.perMin_PLAYER_PET != outside.perMin_PLAYER_PET)  # NaN

    def test_sample_summary(self):
        summary = player_behaviour.sample_summary(self.samples, near_distance=7.0).set_index("phaseId")
        training = summary.loc["TRAINING"]
        self.assertEqual(training.samples, 4)
        self.assertAlmostEqual(training.meanOwnerWolfDistance, 35.0 / 4)
        self.assertAlmostEqual(training.fractionNear, 0.5)
        self.assertAlmostEqual(training.fractionNearWhileAiActive, 0.5)
        self.assertAlmostEqual(training.fractionPlayerMoving, 0.5)
        self.assertAlmostEqual(training.fractionAiActive, 0.5)

    def test_feed_check_finds_same_tick_duplicates_from_before_the_fix(self):
        with tempfile.TemporaryDirectory() as directory:
            old = {"wolfUuid": "u1", "eventType": "FEED", "worldTime": 100}
            write_jsonl(Path(directory) / "events.jsonl", [
                old, old, {**old, "worldTime": 500},
                envelope({"wolfUuid": "u1", "wolfSubjectId": WOLF, "eventType": "FEED", "worldTime": 900}, None),
                {"wolfUuid": "u1", "eventType": "PET", "worldTime": 100},
            ])
            check = player_behaviour.feed_check(Path(directory)).iloc[0]
            self.assertEqual(check.feedRows, 4)
            self.assertEqual(check.preFixRows, 3)
            self.assertEqual(check.sameTickDuplicates, 1)
            self.assertEqual(check.feedDeduplicated, 3)

    def test_main_writes_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_jsonl(root / "sessions.jsonl", SESSIONS)
            write_jsonl(root / "player-events.jsonl", [player_event("PLAYER_PET", 3 * MINUTE, "TRAINING")])
            write_jsonl(root / "player-samples.jsonl", [sample(3 * MINUTE, "TRAINING", 2.0, True, "ACTIVE")])
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(player_behaviour.main([str(root)]), 0)
            for name in ("phase_segments.csv", "player_events.csv", "player_event_rates.csv",
                         "player_samples_by_phase.csv"):
                self.assertTrue((root / "analysis" / name).exists(), name)


class AiAvailabilityTest(unittest.TestCase):
    # 6 分に座る → 8 分にリードへ（理由の変更）→ 9 分に再開。停止は TRAINING と MEMORY_TEST をまたぐ。
    RECORDS = [
        availability("AI_SUSPENDED", 6 * MINUTE, "TRAINING", reason="SITTING"),
        availability("AI_SUSPENSION_REASON_CHANGED", 8 * MINUTE, "MEMORY_TEST", reason="LEASHED", previous="SITTING"),
        availability("AI_RESUMED", 9 * MINUTE, "MEMORY_TEST", previous="LEASHED", duration=3 * MINUTE),
    ]

    def test_intervals_split_by_reason(self):
        intervals = ai_availability.suspension_intervals(self.RECORDS)
        self.assertEqual(list(intervals.reason), ["SITTING", "LEASHED"])
        self.assertEqual(list(intervals.durationTicks), [2 * MINUTE, MINUTE])
        self.assertEqual(intervals.suspensionId.nunique(), 1)
        self.assertEqual(intervals.durationTicks.sum(), intervals.loggedSuspendedDurationTicks.max())

    def test_unresumed_suspension_is_closed_at_end_of_log(self):
        intervals = ai_availability.suspension_intervals(self.RECORDS[:1], {RUN: 10 * MINUTE})
        self.assertEqual(intervals.iloc[0].end, 10 * MINUTE)
        self.assertEqual(intervals.iloc[0].endEvent, "END_OF_LOG")

    def test_availability_by_phase_splits_suspension_across_phases(self):
        segments = study_logs.phase_segments(SESSIONS)
        intervals = ai_availability.suspension_intervals(self.RECORDS)
        decisions = ai_availability.pd.DataFrame([
            {"runId": RUN, "sessionId": SESSION, "phaseId": "TRAINING", "wolfSubjectId": WOLF,
             "worldTime": 3 * MINUTE, "selectedBehavior": behaviour}
            for behaviour in ["follow_close"] * 3 + ["explore_nearby"]])
        wolves = ai_availability.session_wolves(decisions)
        table = ai_availability.availability_by_phase(segments, intervals, wolves, decisions).set_index("phaseId")

        training = table.loc["TRAINING"]
        self.assertAlmostEqual(training.minutes, 5.0)
        self.assertAlmostEqual(training.suspendedMinutes_SITTING, 1.0)
        self.assertAlmostEqual(training.availableMinutes, 4.0)
        self.assertEqual(training.decisions, 4)
        self.assertAlmostEqual(training.decisionsPerAvailableMinute, 1.0)
        self.assertAlmostEqual(training.p_follow_close, 0.75)

        memory = table.loc["MEMORY_TEST"]
        self.assertAlmostEqual(memory.suspendedMinutes_SITTING, 1.0)
        self.assertAlmostEqual(memory.suspendedMinutes_LEASHED, 1.0)
        self.assertAlmostEqual(memory.availableMinutes, 1.0)
        self.assertEqual(memory.decisions, 0)
        self.assertAlmostEqual(table.loc[study_logs.NO_PHASE].fractionAvailable, 1.0)

    def test_main_writes_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_jsonl(root / "sessions.jsonl", SESSIONS)
            write_jsonl(root / "ai-availability.jsonl", self.RECORDS)
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(ai_availability.main([str(root), "-o", str(root / "out")]), 0)
            for name in ("ai_suspensions.csv", "ai_availability_by_phase.csv"):
                self.assertTrue((root / "out" / name).exists(), name)


if __name__ == "__main__":
    unittest.main()
