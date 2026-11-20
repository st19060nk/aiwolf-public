package jp.ac.naist.hai.aiwolf.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.ac.naist.hai.aiwolf.config.AiWolfConfig;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.AnonymousIdMapper;
import jp.ac.naist.hai.aiwolf.core.BuildInfo;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.demo.DemoMode;
import jp.ac.naist.hai.aiwolf.mc.WolfInteractionHandler;
import jp.ac.naist.hai.aiwolf.study.AiAvailability;
import jp.ac.naist.hai.aiwolf.study.AiAvailabilityLogJson;
import jp.ac.naist.hai.aiwolf.study.PlayerLogJson;
import jp.ac.naist.hai.aiwolf.study.SessionLogJson;
import jp.ac.naist.hai.aiwolf.study.StudyAssignment;
import jp.ac.naist.hai.aiwolf.study.StudyPhase;
import jp.ac.naist.hai.aiwolf.study.StudySession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 25：Human Study のパイロットを始める前の品質保証（版の追跡・通し番号・個人情報・FEED の再発防止）。
 *
 * <p>研究パラメータ・行動・記憶・選好・状態の更新規則には触れない。ログの計測と再現性だけを検証する。</p>
 */
final class PilotReadinessTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 14, 21, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final List<String> ALL_FILES = List.of(ResearchLogger.DECISIONS_FILE, ResearchLogger.EVENTS_FILE,
            ResearchLogger.PLAYER_EVENTS_FILE, ResearchLogger.PLAYER_SAMPLES_FILE, ResearchLogger.SESSIONS_FILE,
            ResearchLogger.DAMAGE_FILE, ResearchLogger.SIGNATURES_FILE, ResearchLogger.AI_AVAILABILITY_FILE);

    /** テスト環境のプレイヤー（開発クライアントの固定ユーザ名と、そのオフライン UUID に相当する値）。 */
    private static final UUID RAW_PLAYER = UUID.fromString("99999999-1234-4abc-8def-999999999999");
    private static final String PLAYER_NAME = "Researcher";
    private static final AnonymousIdMapper MAPPER = AnonymousIdMapper.withSalt(20_261_114L);

    private static final Pattern UUID_DASHED =
            Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern UUID_PLAIN = Pattern.compile("(?i)(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])");
    private static final Pattern SEQUENCE = Pattern.compile("\"logSequence\":(\\d+)");

    @BeforeEach
    @AfterEach
    void clean() {
        StudySession.reset();
        StudySession.setProtocolVersion(AiWolfParameters.HUMAN_STUDY_PROTOCOL_VERSION);
        StudyAssignment.reset();
        DemoMode.reset();
        drainAll();
        ResearchLogger.resetSequenceForTest();
    }

    private static List<String> drainAll() {
        List<String> lines = new ArrayList<>();
        for (String file : ALL_FILES) {
            lines.addAll(ResearchLogger.drainForTest(file, false));
            lines.addAll(ResearchLogger.drainForTest(file, true));
        }
        return lines;
    }

    private static long sequence(String line) {
        Matcher matcher = SEQUENCE.matcher(line);
        assertTrue(matcher.find(), "logSequence がない: " + line);
        return Long.parseLong(matcher.group(1));
    }

    // ------------------------------------------------------------------
    // 25A：ソフトウェアと実験手順の版
    // ------------------------------------------------------------------

    @Test
    @DisplayName("25A: SESSION_START に modVersion / buildRevision / buildDirty / protocolVersion / parameterSetVersion が入る")
    void sessionStartCarriesVersions() {
        StudySession.setProtocolVersion(3);
        StudySession.Active session = StudySession.start("p001-s01", 100L, 0L, NOW);
        String start = SessionLogJson.toJsonLine(SessionLogJson.SESSION_START, session, 100L, 0L, Map.of());
        BuildInfo build = BuildInfo.current();
        for (String expected : List.of("\"modVersion\":\"" + build.modVersion() + "\"",
                "\"buildRevision\":\"" + build.buildRevision() + "\"",
                "\"buildDirty\":\"" + build.buildDirty() + "\"",
                "\"protocolVersion\":3",
                "\"parameterSetVersion\":" + AiWolfParameters.PARAMETER_SET_VERSION,
                "\"sessionLogSchemaVersion\":" + AiWolfParameters.SESSION_LOG_SCHEMA_VERSION)) {
            assertTrue(start.contains(expected), "missing " + expected + " in " + start);
        }
        String stop = SessionLogJson.toJsonLine(SessionLogJson.SESSION_STOP, session, 200L, 0L, Map.of());
        assertFalse(stop.contains("buildRevision"), "版は開始の行だけに書く");
    }

    @Test
    @DisplayName("25A: Gradle が埋め込んだ版を読める。リソースがなければ省略せず UNKNOWN")
    void buildInfoIsEmbeddedOrUnknown() {
        BuildInfo build = BuildInfo.current();
        assertFalse(build.modVersion().isBlank());
        assertTrue(build.buildRevision().equals(BuildInfo.UNKNOWN) || build.buildRevision().matches("[0-9a-f]{7,40}"),
                build.buildRevision());
        assertTrue(List.of("true", "false", BuildInfo.UNKNOWN).contains(build.buildDirty()), build.buildDirty());

        BuildInfo missing = BuildInfo.load(null);
        assertEquals(BuildInfo.UNKNOWN, missing.modVersion());
        assertEquals(BuildInfo.UNKNOWN, missing.buildRevision());
        assertEquals(BuildInfo.UNKNOWN, missing.buildDirty());

        BuildInfo partial = BuildInfo.load(new StringReader("modVersion=0.1.0\nbuildRevision=\n"));
        assertEquals("0.1.0", partial.modVersion());
        assertEquals(BuildInfo.UNKNOWN, partial.buildRevision(), "空の値も UNKNOWN");
    }

    @Test
    @DisplayName("25A: 実験手順の版は設定ファイルで変えられ、壊れた値・欠けた値は既定値になる")
    void protocolVersionIsConfigurable() throws Exception {
        assertEquals(4, AiWolfConfig.load(new StringReader("humanStudyProtocolVersion=4\n")).humanStudyProtocolVersion());
        assertEquals(AiWolfParameters.HUMAN_STUDY_PROTOCOL_VERSION,
                AiWolfConfig.load(new StringReader("")).humanStudyProtocolVersion());
        assertEquals(AiWolfParameters.HUMAN_STUDY_PROTOCOL_VERSION,
                AiWolfConfig.load(new StringReader("humanStudyProtocolVersion=0\n")).humanStudyProtocolVersion());
        assertEquals(AiWolfParameters.HUMAN_STUDY_PROTOCOL_VERSION,
                AiWolfConfig.load(new StringReader("humanStudyProtocolVersion=two\n")).humanStudyProtocolVersion());
    }

    // ------------------------------------------------------------------
    // 25B：通し番号
    // ------------------------------------------------------------------

    @Test
    @DisplayName("25B: logSequence は 1 から始まり、ファイルの種類と Demo Mode をまたいで記録順に単調増加する")
    void sequenceIsSharedAcrossFilesAndDemoMode() {
        StudySession.start("p001-s01", 0L, 0L, NOW);
        ResearchLogger.logSession("{\"recordType\":\"session\"}");
        ResearchLogger.logDecision("{\"recordType\":\"decision\"}");
        DemoMode.setEnabled(true);
        ResearchLogger.logEvent("{\"recordType\":\"event\"}");
        DemoMode.setEnabled(false);
        ResearchLogger.logPlayerSample("{\"recordType\":\"player_sample\"}");
        ResearchLogger.logAiAvailability("{\"recordType\":\"ai_availability\"}");

        assertEquals(1L, sequence(ResearchLogger.drainForTest(ResearchLogger.SESSIONS_FILE, false).get(0)));
        assertEquals(2L, sequence(ResearchLogger.drainForTest(ResearchLogger.DECISIONS_FILE, false).get(0)));
        String demo = ResearchLogger.drainForTest(ResearchLogger.EVENTS_FILE, true).get(0);
        assertEquals(3L, sequence(demo));
        assertTrue(demo.startsWith("{\"demoMode\":true"), "Demo Mode の行も同じ番号列を使う");
        assertEquals(4L, sequence(ResearchLogger.drainForTest(ResearchLogger.PLAYER_SAMPLES_FILE, false).get(0)));
        assertEquals(5L, sequence(ResearchLogger.drainForTest(ResearchLogger.AI_AVAILABILITY_FILE, false).get(0)));
    }

    @Test
    @DisplayName("25B: 封筒は版 3 で、logSequence を含み、worldTime など元の項目は残る")
    void envelopeVersionThree() {
        assertEquals(3, AiWolfParameters.LOG_ENVELOPE_VERSION);
        String line = LogEnvelope.envelope("{\"worldTime\":42}", "run-x", null, 7L);
        assertEquals("{\"logEnvelopeVersion\":3,\"runId\":\"run-x\",\"sessionId\":null,\"phaseId\":null,"
                + "\"logSequence\":7,\"worldTime\":42}", line);
        assertEquals("{\"logEnvelopeVersion\":3,\"runId\":\"run-x\",\"sessionId\":null,\"phaseId\":null,"
                + "\"logSequence\":1}", LogEnvelope.envelope("{}", "run-x", null, 1L));
    }

    @Test
    @DisplayName("25B: 複数スレッドから書いても番号は重複・欠番なく、各ファイル内で行の順と番号の順が一致する")
    void sequenceIsThreadSafe() throws Exception {
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(1);
        List<String> files = List.of(ResearchLogger.DECISIONS_FILE, ResearchLogger.EVENTS_FILE,
                ResearchLogger.PLAYER_SAMPLES_FILE, ResearchLogger.AI_AVAILABILITY_FILE);
        for (int t = 0; t < threads; t++) {
            String file = files.get(t % files.size());
            pool.submit(() -> {
                ready.await();
                for (int i = 0; i < perThread; i++) {
                    switch (file) {
                        case ResearchLogger.DECISIONS_FILE -> ResearchLogger.logDecision("{}");
                        case ResearchLogger.EVENTS_FILE -> ResearchLogger.logEvent("{}");
                        case ResearchLogger.PLAYER_SAMPLES_FILE -> ResearchLogger.logPlayerSample("{}");
                        default -> ResearchLogger.logAiAvailability("{}");
                    }
                }
                return null;
            });
        }
        ready.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        Set<Long> seen = new HashSet<>();
        for (String file : files) {
            long previous = 0L;
            for (String line : ResearchLogger.drainForTest(file, false)) {
                long value = sequence(line);
                assertTrue(value > previous, "ファイル内で番号が逆行した: " + file);
                previous = value;
                assertTrue(seen.add(value), "番号が重複した: " + value);
            }
        }
        assertEquals(threads * perThread, seen.size());
        assertEquals(threads * perThread, (long) java.util.Collections.max(seen), "欠番がない");
    }

    // ------------------------------------------------------------------
    // 25C：実験者の入力に個人情報を入れない
    // ------------------------------------------------------------------

    @Test
    @DisplayName("25C: ラベルと割付 ID は匿名の形だけ。@・空白・メール・UUID（ハイフンあり・なし）は拒否")
    void labelsRejectIdentifiers() {
        for (String ok : List.of("p001-s01", "P12_s2", "pilot-dry-run")) {
            assertTrue(StudySession.isValidLabel(ok), ok);
            assertTrue(StudyAssignment.isValidId(ok), ok);
        }
        for (String bad : List.of("taro@example.com", "taro yamada", "p001 s01", "",
                "99999999-1234-4abc-8def-999999999999", "p-999999991234" + "4abc8def999999999999",
                "99999999123441bc8def999999999999", "a.b@c.jp")) {
            assertFalse(StudySession.isValidLabel(bad), bad);
            assertFalse(StudyAssignment.isValidId(bad), bad);
        }
        assertTrue(StudySession.LABEL_PRIVACY_WARNING.contains("DO NOT use participant names"));
        assertTrue(StudySession.LABEL_PRIVACY_WARNING.contains("p001-s01"));
    }

    @Test
    @DisplayName("Privacy: Human Study の全種類の行に、プレイヤーの生 UUID・名前・メール・IP・UUID の形が出ない")
    void humanStudyLogsContainNoPersonalData() {
        String subjectId = MAPPER.toSubjectId(RAW_PLAYER);
        String wolfSubjectId = "wolf-ba9876543210";
        StudySession.Active session = StudySession.start("p001-s01", 0L, 0L, NOW);
        Map<String, Object> startDetails = new LinkedHashMap<>();
        startDetails.put("assignmentId", "A-01");
        startDetails.put("ownerSubjectId", subjectId);
        startDetails.put("researchWolves", wolfSubjectId + "=adaptive");
        ResearchLogger.logSession(SessionLogJson.toJsonLine(SessionLogJson.SESSION_START, session, 0L, 0L, startDetails));
        session = StudySession.setPhase(StudyPhase.TRAINING);
        ResearchLogger.logSession(SessionLogJson.toJsonLine(SessionLogJson.PHASE_CHANGED, session, 20L, 0L,
                Map.of("toPhase", "TRAINING")));
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("wolfSubjectId", wolfSubjectId);
        control.put("memoryEnabled", false);
        ResearchLogger.logSession(SessionLogJson.controlLine("MEMORY_CONDITION_CHANGED", session, 30L, 0L, control));

        PlayerLogJson.Subjects subjects = new PlayerLogJson.Subjects(subjectId, wolfSubjectId,
                ExperimentalCondition.ADAPTIVE);
        PlayerLogJson.Relation relation = new PlayerLogJson.Relation(3.0, 1.0, 0.9, 1, true);
        for (PlayerLogJson.PlayerEventType type : PlayerLogJson.PlayerEventType.values()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("hostileType", "minecraft:zombie");
            details.put("attackerType", "zombie");
            ResearchLogger.logPlayerEvent(PlayerLogJson.event(type, subjects, session, 40L, 0L, relation, details));
        }
        ResearchLogger.logPlayerSample(PlayerLogJson.sample(subjects, session, 60L, 0L, relation,
                new PlayerLogJson.Motion(20L, 1.0, 2.0), "follow_close", "ACTIVE"));
        AiAvailability.Tracker tracker = new AiAvailability.Tracker();
        tracker.update(AiAvailability.Reason.SITTING, 80L).ifPresent(transition -> ResearchLogger.logAiAvailability(
                AiAvailabilityLogJson.toJsonLine(transition, subjectId, wolfSubjectId, ExperimentalCondition.ADAPTIVE,
                        StudySession.current(), 80L, 0L, 40L)));
        tracker.update(null, 120L).ifPresent(transition -> ResearchLogger.logAiAvailability(
                AiAvailabilityLogJson.toJsonLine(transition, subjectId, wolfSubjectId, ExperimentalCondition.ADAPTIVE,
                        StudySession.current(), 120L, 0L, 40L)));
        ResearchLogger.logSession(SessionLogJson.toJsonLine(SessionLogJson.SESSION_STOP, StudySession.stop(),
                200L, 0L, Map.of("reason", "COMMAND")));

        List<String> lines = drainAll();
        assertTrue(lines.size() >= 4 + PlayerLogJson.PlayerEventType.values().length, "全種類の行を作った");
        String raw = RAW_PLAYER.toString();
        for (String line : lines) {
            assertFalse(line.contains(raw), line);
            assertFalse(line.toLowerCase().contains(raw.replace("-", "").substring(0, 12)), line);
            assertFalse(line.contains(PLAYER_NAME), line);
            assertFalse(UUID_DASHED.matcher(line).find(), "UUID の形: " + line);
            assertFalse(UUID_PLAIN.matcher(line).find(), "32 桁の 16 進: " + line);
            assertFalse(EMAIL.matcher(line).find(), "メールアドレスの形: " + line);
            assertFalse(IPV4.matcher(line).find(), "IP アドレスの形: " + line);
        }
    }

    // ------------------------------------------------------------------
    // 25G：FEED の二重記録の再発防止
    // ------------------------------------------------------------------

    @Test
    @DisplayName("25G: 1 回の餌やり（INTERACT_AT + INTERACT）で events の FEED も player-events の PLAYER_FEED も 1 件")
    void oneFeedingIsRecordedOnce() {
        StudySession.Active session = StudySession.start("p001-s01", 0L, 0L, NOW);
        PlayerLogJson.Subjects subjects = new PlayerLogJson.Subjects(MAPPER.toSubjectId(RAW_PLAYER),
                "wolf-ba9876543210", ExperimentalCondition.ADAPTIVE);
        long worldTime = 400L;
        // サーバに届く 1 回の右クリック分のコールバック。WolfInteractionHandler.onUseEntity と同じ判定を通し、
        // 処理するコールバックでだけ、出来事ログ（WolfEventObserver.feed）とプレイヤーログ（PlayerBehaviorLogger.onFeed）を書く。
        for (boolean atLocation : new boolean[] {true, false}) {
            if (WolfInteractionHandler.handles(false, true, atLocation)) {
                ResearchLogger.logEvent("{\"eventType\":\"FEED\",\"wolfSubjectId\":\"wolf-ba9876543210\",\"worldTime\":"
                        + worldTime + "}");
                ResearchLogger.logPlayerEvent(PlayerLogJson.event(PlayerLogJson.PlayerEventType.PLAYER_FEED, subjects,
                        session, worldTime, 0L, new PlayerLogJson.Relation(1.0, 0.5, 1.0, 0, false), Map.of()));
            }
        }
        List<String> feeds = ResearchLogger.drainForTest(ResearchLogger.EVENTS_FILE, false).stream()
                .filter(line -> line.contains("\"eventType\":\"FEED\"")).toList();
        List<String> playerFeeds = ResearchLogger.drainForTest(ResearchLogger.PLAYER_EVENTS_FILE, false).stream()
                .filter(line -> line.contains("\"eventType\":\"PLAYER_FEED\"")).toList();
        assertEquals(1, feeds.size(), "events.jsonl FEED = 1");
        assertEquals(1, playerFeeds.size(), "player-events.jsonl PLAYER_FEED = 1");
    }
}
