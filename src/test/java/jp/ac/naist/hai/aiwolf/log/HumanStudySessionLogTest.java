package jp.ac.naist.hai.aiwolf.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.demo.DemoMode;
import jp.ac.naist.hai.aiwolf.study.PlayerLogJson;
import jp.ac.naist.hai.aiwolf.study.SessionLogJson;
import jp.ac.naist.hai.aiwolf.study.StudyPhase;
import jp.ac.naist.hai.aiwolf.study.StudySession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 20：セッションとフェーズが全研究ログに正しく付くことの検証。 */
final class HumanStudySessionLogTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 14, 21, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final Pattern SESSION_ID = Pattern.compile("session-\\d{8}T\\d{6}Z-[0-9a-f]{6}");

    @BeforeEach
    @AfterEach
    void clean() {
        StudySession.reset();
        DemoMode.reset();
        for (String file : List.of(ResearchLogger.DECISIONS_FILE, ResearchLogger.EVENTS_FILE,
                ResearchLogger.PLAYER_EVENTS_FILE, ResearchLogger.PLAYER_SAMPLES_FILE, ResearchLogger.SESSIONS_FILE,
                ResearchLogger.DAMAGE_FILE, ResearchLogger.SIGNATURES_FILE)) {
            ResearchLogger.drainForTest(file, false);
            ResearchLogger.drainForTest(file, true);
        }
    }

    private static String field(String line, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":(null|\"([^\"]*)\")").matcher(line);
        assertTrue(matcher.find(), "missing " + name + " in " + line);
        return matcher.group(2);
    }

    private static String samplePlayerEvent() {
        return PlayerLogJson.event(PlayerLogJson.PlayerEventType.PLAYER_PET,
                new PlayerLogJson.Subjects("subject-0123456789ab", "wolf-ba9876543210", ExperimentalCondition.ADAPTIVE),
                StudySession.current(), 1_000L, 0L,
                new PlayerLogJson.Relation(3.5, 1.0, 1.0, 0, false), Map.of());
    }

    @Test
    @DisplayName("session start で内部 ID が生成され、ラベルとは別に記録される")
    void startGeneratesASessionId() {
        StudySession.Active session = StudySession.start("p001-s01", 500L, 0L, NOW);
        assertTrue(SESSION_ID.matcher(session.sessionId()).matches(), session.sessionId());
        assertTrue(session.sessionId().startsWith("session-20261114T120000Z-"), "UTC で採番する");
        assertEquals("p001-s01", session.label());
        assertNull(session.phaseId());
        assertEquals(250L, session.elapsedTicks(750L));
        assertThrows(IllegalStateException.class, () -> StudySession.start("p001-s02", 0L, 0L, NOW),
                "二重開始は拒否する");
    }

    @Test
    @DisplayName("ラベルの制限：空・長すぎ・記号・UUID の形は拒否する")
    void labelsAreRestricted() {
        assertTrue(StudySession.isValidLabel("p001-s01"));
        assertFalse(StudySession.isValidLabel(""));
        assertFalse(StudySession.isValidLabel("a".repeat(65)));
        assertFalse(StudySession.isValidLabel("Taro Yamada"));
        assertFalse(StudySession.isValidLabel("p1-99999999-1234-4abc-8def-999999999999"));
    }

    @Test
    @DisplayName("セッション中の決定・出来事・プレイヤーのログに同じ sessionId が入り、フェーズの変更が以降に反映される")
    void sessionAndPhaseReachEveryLog() {
        StudySession.Active session = StudySession.start("p001-s01", 0L, 0L, NOW);
        ResearchLogger.logDecision("{\"recordType\":\"decision\"}");
        StudySession.setPhase(StudyPhase.TRAINING);
        ResearchLogger.logEvent("{\"recordType\":\"event\"}");
        ResearchLogger.logPlayerEvent(samplePlayerEvent());
        ResearchLogger.logDamage("{\"recordType\":\"damage\"}");
        ResearchLogger.logSignature("{\"recordType\":\"signature\"}");

        String decision = ResearchLogger.drainForTest(ResearchLogger.DECISIONS_FILE, false).get(0);
        String event = ResearchLogger.drainForTest(ResearchLogger.EVENTS_FILE, false).get(0);
        String player = ResearchLogger.drainForTest(ResearchLogger.PLAYER_EVENTS_FILE, false).get(0);
        String damage = ResearchLogger.drainForTest(ResearchLogger.DAMAGE_FILE, false).get(0);
        String signature = ResearchLogger.drainForTest(ResearchLogger.SIGNATURES_FILE, false).get(0);
        for (String line : List.of(decision, event, player, damage, signature)) {
            assertEquals(session.sessionId(), field(line, "sessionId"), line);
            assertTrue(line.startsWith("{\"logEnvelopeVersion\":" + AiWolfParameters.LOG_ENVELOPE_VERSION
                    + ",\"runId\":"), line);
        }
        assertNull(field(decision, "phaseId"), "フェーズ設定前の行はフェーズなし");
        assertEquals("TRAINING", field(event, "phaseId"));
        assertEquals("TRAINING", field(player, "phaseId"));
        assertTrue(player.contains("\"elapsedTicksFromSessionStart\":1000"));
    }

    @Test
    @DisplayName("session stop 後の行には sessionId が付かない（null）")
    void noSessionAfterStop() {
        StudySession.start("p001-s01", 0L, 0L, NOW);
        StudySession.stop();
        ResearchLogger.logDecision("{\"recordType\":\"decision\"}");
        String line = ResearchLogger.drainForTest(ResearchLogger.DECISIONS_FILE, false).get(0);
        assertNull(field(line, "sessionId"));
        assertNull(field(line, "phaseId"));
        assertThrows(IllegalStateException.class, StudySession::stop);
    }

    @Test
    @DisplayName("再起動（reset）後に古いセッションが続かない")
    void restartDoesNotContinueTheOldSession() {
        StudySession.start("p001-s01", 0L, 0L, NOW);
        StudySession.setPhase(StudyPhase.EVALUATION);
        StudySession.reset();   // AiWolfMod がサーバの起動・停止で呼ぶ
        assertNull(StudySession.current());
        ResearchLogger.logEvent("{\"recordType\":\"event\"}");
        assertNull(field(ResearchLogger.drainForTest(ResearchLogger.EVENTS_FILE, false).get(0), "sessionId"));
    }

    @Test
    @DisplayName("sessions.jsonl：開始・フェーズ変更・終了の行が境界を復元できる項目を持つ")
    void sessionLifecycleLines() {
        StudySession.Active session = StudySession.start("p001-s01", 100L, 0L, NOW);
        String start = SessionLogJson.toJsonLine(SessionLogJson.SESSION_START, session, 100L, 5L, Map.of());
        StudySession.Active training = StudySession.setPhase(StudyPhase.TRAINING);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("fromPhase", null);
        change.put("toPhase", "TRAINING");
        String changed = SessionLogJson.toJsonLine(SessionLogJson.PHASE_CHANGED, training, 700L, 6L, change);
        String stop = SessionLogJson.toJsonLine(SessionLogJson.SESSION_STOP, training, 1300L, 7L,
                Map.of("durationTicks", 1200L));

        assertTrue(start.contains("\"event\":\"SESSION_START\"") && start.contains("\"sessionLabel\":\"p001-s01\""));
        assertTrue(changed.contains("\"toPhase\":\"TRAINING\"") && !changed.contains("fromPhase"),
                "null の項目は省略");
        assertTrue(stop.contains("\"durationTicks\":1200") && stop.contains("\"elapsedTicksFromSessionStart\":1200"));
        for (String line : List.of(start, changed, stop)) {
            assertEquals(session.sessionId(), field(line, "targetSessionId"));
        }
    }

    @Test
    @DisplayName("Demo Mode 中もセッションの項目は付き、デモ用の出力先へ分かれる")
    void demoModeKeepsSessionFields() {
        StudySession.start("pilot-demo", 0L, 0L, NOW);
        DemoMode.setEnabled(true);
        ResearchLogger.logPlayerEvent(samplePlayerEvent());
        assertTrue(ResearchLogger.drainForTest(ResearchLogger.PLAYER_EVENTS_FILE, false).isEmpty());
        String demo = ResearchLogger.drainForTest(ResearchLogger.PLAYER_EVENTS_FILE, true).get(0);
        assertTrue(demo.startsWith("{\"demoMode\":true"));
        assertNotEquals(null, field(demo, "sessionId"));
    }
}
