package jp.ac.naist.hai.aiwolf.study;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorEligibility;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorScore;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorScorer;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorWeights;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.demo.DemoMode;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.MemoryImpactCalculator;
import jp.ac.naist.hai.aiwolf.memory.MemoryQuery;
import jp.ac.naist.hai.aiwolf.memory.MemoryRetriever;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 22〜24：AI の停止記録・割付・Study 2 の記憶条件の検証。 */
final class HumanStudyP1Test {

    @AfterEach
    void clean() {
        StudySession.reset();
        StudyAssignment.reset();
        DemoMode.reset();
    }

    // ------------------------------------------------------------------ Phase 22

    @Test
    @DisplayName("停止の理由はコード上で区別できる条件だけで、優先順位どおりに決まる")
    void classifyReasons() {
        assertEquals(Optional.of(AiAvailability.Reason.ENTITY_NOT_LOADED),
                AiAvailability.classify(false, true, true, true, 100L, 110L));
        assertEquals(Optional.of(AiAvailability.Reason.NOT_TAMED),
                AiAvailability.classify(true, false, true, false, 100L, 110L));
        assertEquals(Optional.of(AiAvailability.Reason.SITTING),
                AiAvailability.classify(true, true, true, true, 100L, 110L));
        assertEquals(Optional.of(AiAvailability.Reason.LEASHED),
                AiAvailability.classify(true, true, false, true, 100L, 110L));
        assertEquals(Optional.of(AiAvailability.Reason.NO_RECENT_DECISION),
                AiAvailability.classify(true, true, false, false, 100L, 100L + AiAvailability.NO_DECISION_TICKS + 1));
        assertEquals(Optional.empty(), AiAvailability.classify(true, true, false, false, 100L, 160L),
                "通常の決定間隔（40〜60 ticks）は停止ではない");
    }

    @Test
    @DisplayName("停止 → 再開は 1 組だけ記録され、ずっと止まっていても毎回は書かない")
    void suspendedThenResumedOnce() {
        AiAvailability.Tracker tracker = new AiAvailability.Tracker();
        List<AiAvailability.Transition> transitions = new ArrayList<>();
        // 稼働 → 座る（10 分間、1 秒ごとに観測）→ 立つ
        long t = 0L;
        for (int i = 0; i < 5; i++, t += 20) {
            tracker.update(null, t).ifPresent(transitions::add);
        }
        long sitAt = t;
        for (int i = 0; i < 600; i++, t += 20) {
            tracker.update(AiAvailability.Reason.SITTING, t).ifPresent(transitions::add);
        }
        tracker.update(null, t).ifPresent(transitions::add);

        assertEquals(2, transitions.size(), "SUSPENDED と RESUMED の 2 行だけ");
        assertEquals("AI_SUSPENDED", transitions.get(0).event());
        assertEquals(AiAvailability.Reason.SITTING, transitions.get(0).reason());
        assertEquals("AI_RESUMED", transitions.get(1).event());
        assertEquals(AiAvailability.Reason.SITTING, transitions.get(1).previousReason());
        assertEquals(t - sitAt, transitions.get(1).suspendedDurationTicks());
    }

    @Test
    @DisplayName("停止中に理由だけが変わっても、停止 → 再開の組は 1 組のまま（理由の変化は別の行）")
    void reasonChangeKeepsOnePair() {
        AiAvailability.Tracker tracker = new AiAvailability.Tracker();
        List<String> events = new ArrayList<>();
        tracker.update(AiAvailability.Reason.SITTING, 0L).ifPresent(x -> events.add(x.event()));
        tracker.update(AiAvailability.Reason.LEASHED, 20L).ifPresent(x -> events.add(x.event()));
        tracker.update(AiAvailability.Reason.LEASHED, 40L).ifPresent(x -> events.add(x.event()));
        Optional<AiAvailability.Transition> resumed = tracker.update(null, 100L);
        resumed.ifPresent(x -> events.add(x.event()));
        assertEquals(List.of("AI_SUSPENDED", "AI_SUSPENSION_REASON_CHANGED", "AI_RESUMED"), events);
        assertEquals(100L, resumed.orElseThrow().suspendedDurationTicks(), "停止の長さは最初の停止から数える");
    }

    @Test
    @DisplayName("ai-availability.jsonl の行に理由・停止の長さ・匿名 ID が入る")
    void availabilityLine() {
        AiAvailability.Tracker tracker = new AiAvailability.Tracker();
        tracker.update(AiAvailability.Reason.LEASHED, 1_000L);
        String line = AiAvailabilityLogJson.toJsonLine(tracker.update(null, 1_600L).orElseThrow(),
                "subject-0123456789ab", "wolf-ba9876543210", ExperimentalCondition.ADAPTIVE, null,
                1_600L, 0L, 1_590L);
        for (String key : List.of("\"event\":\"AI_RESUMED\"", "\"previousReason\":\"LEASHED\"",
                "\"suspendedDurationTicks\":600", "\"suspendedSinceWorldTime\":1000",
                "\"wolfSubjectId\":\"wolf-ba9876543210\"", "\"lastDecisionWorldTime\":1590")) {
            assertTrue(line.contains(key), "missing " + key + " in " + line);
        }
    }

    // ------------------------------------------------------------------ Phase 23

    @Test
    @DisplayName("割付：ID の制限、条件の順序の解析、割付表のハッシュ（中身は出さない）")
    void assignment(@TempDir Path directory) throws Exception {
        assertTrue(StudyAssignment.isValidId("condition-A-order-BA"));
        assertFalse(StudyAssignment.isValidId("Taro Yamada"));
        assertFalse(StudyAssignment.isValidId("99999999-1234-4abc-8def-999999999999"));

        assertEquals(List.of(ExperimentalCondition.ADAPTIVE, ExperimentalCondition.STATE_PREFERENCE),
                StudyAssignment.parseOrder("adaptive>state_preference").orElseThrow());
        assertEquals(List.of(ExperimentalCondition.STATE_PREFERENCE, ExperimentalCondition.ADAPTIVE),
                StudyAssignment.parseOrder("state_preference, full_adaptive").orElseThrow());
        assertTrue(StudyAssignment.parseOrder("adaptive>unknown").isEmpty());

        Path table = directory.resolve(StudyAssignment.TABLE_FILE_NAME);
        Files.writeString(table, "p001,memory_on_first\np002,memory_off_first\n", StandardCharsets.UTF_8);
        String hash = StudyAssignment.hashFile(table).orElseThrow();
        assertTrue(hash.matches("sha256:[0-9a-f]{64}"), hash);
        assertFalse(hash.contains("p001"));
        assertEquals(hash, StudyAssignment.hashFile(table).orElseThrow(), "同じ表なら同じハッシュ");
        Files.writeString(table, "p001,memory_off_first\n", StandardCharsets.UTF_8);
        assertNotEquals(hash, StudyAssignment.hashFile(table).orElseThrow(), "表が変われば別のハッシュ");
        assertTrue(StudyAssignment.hashFile(directory.resolve("missing.csv")).isEmpty());

        StudyAssignment.setId("condition-A-order-BA");
        StudyAssignment.setOrder(StudyAssignment.parseOrder("adaptive>state_preference").orElseThrow());
        assertEquals("adaptive>state_preference", StudyAssignment.orderText());
        String line = SessionLogJson.controlLine("ASSIGNMENT_SET", null, 10L, 0L, Map.of(
                "assignmentId", StudyAssignment.id(), "conditionOrder", StudyAssignment.orderText()));
        assertTrue(line.contains("\"recordType\":\"study_control\"") && line.contains("\"conditionOrder\":\"adaptive>state_preference\""));
        assertFalse(line.contains("elapsedTicksFromSessionStart"), "セッション外の操作には経過 tick を付けない");
    }

    // ------------------------------------------------------------------ Phase 24

    @Test
    @DisplayName("記憶 ON / OFF の切り替え先：状態と選好のフラグは保ち、記憶だけが変わる")
    void memoryConditionMapping() {
        assertEquals(ExperimentalCondition.STATE_PREFERENCE,
                MemoryCondition.withMemory(ExperimentalCondition.ADAPTIVE, false).orElseThrow());
        assertEquals(ExperimentalCondition.ADAPTIVE,
                MemoryCondition.withMemory(ExperimentalCondition.STATE_PREFERENCE, true).orElseThrow());
        assertEquals(ExperimentalCondition.STATE_ONLY,
                MemoryCondition.withMemory(ExperimentalCondition.STATE_MEMORY, false).orElseThrow());
        assertEquals(ExperimentalCondition.STATE_MEMORY,
                MemoryCondition.withMemory(ExperimentalCondition.STATE_ONLY, true).orElseThrow());
        assertTrue(MemoryCondition.withMemory(ExperimentalCondition.STATIC, true).isEmpty());
    }

    @Test
    @DisplayName("Study 2：記憶を OFF にすると記憶の寄与だけが 0 になり、状態・選好・文脈・記憶の中身は同じ。ON に戻せば元どおり")
    void memoryOffChangesOnlyMemoryContributions() {
        WolfAiAgent agent = new WolfAiAgent("13131313-2424-4535-8646-757575757575", ExperimentalCondition.ADAPTIVE, 5L);
        SpatialRegion cave = SpatialRegion.of("minecraft:overworld", 100.0, 30.0, 100.0);
        long tick = 100L;
        for (WolfEventType type : List.of(WolfEventType.WOLF_DAMAGED, WolfEventType.WOLF_DAMAGED,
                WolfEventType.WOLF_LOW_HEALTH, WolfEventType.WOLF_DAMAGED, WolfEventType.PLAYER_PROTECTED_WOLF,
                WolfEventType.PET, WolfEventType.FEED)) {
            agent.onEvent(WolfEvent.of(type, tick, "minecraft:overworld", "minecraft:plains", 100.0, 30.0, 100.0,
                    "subject-x", 0.5, 1.0));
            tick += 40L;
        }
        int memories = agent.memoryStore().size();
        int regions = agent.spatialMemory().size();

        Map<BehaviorType, BehaviorScore> on = scores(agent, cave, tick);
        agent.setCondition(MemoryCondition.withMemory(agent.condition(), false).orElseThrow());
        Map<BehaviorType, BehaviorScore> off = scores(agent, cave, tick);

        assertEquals(memories, agent.memoryStore().size(), "記憶の中身は消さない");
        assertEquals(regions, agent.spatialMemory().size());
        boolean anyMemoryEffect = false;
        for (BehaviorType behavior : off.keySet()) {
            BehaviorScore a = on.get(behavior);
            BehaviorScore b = off.get(behavior);
            assertEquals(a.base(), b.base());
            assertEquals(a.state(), b.state(), "内部状態は同じ");
            assertEquals(a.preference(), b.preference(), "選好は同じ（選好学習のフラグは保たれる）");
            assertEquals(a.contextExcludingMemoryAndEmergency(), b.contextExcludingMemoryAndEmergency(), 1e-12,
                    "記憶以外の文脈は同じ");
            assertEquals(0.0, b.episodicMemory());
            assertEquals(0.0, b.spatialMemory());
            anyMemoryEffect |= a.episodicMemory() != 0.0 || a.spatialMemory() != 0.0;
        }
        assertTrue(anyMemoryEffect, "ON のときは記憶が効いていること（比較の前提）");
        assertTrue(on.containsKey(BehaviorType.AVOID_LOCATION) && !off.containsKey(BehaviorType.AVOID_LOCATION),
                "空間記憶が OFF なら危険な場所の回避は選択肢に入らない");

        agent.setCondition(MemoryCondition.withMemory(agent.condition(), true).orElseThrow());
        Map<BehaviorType, BehaviorScore> back = scores(agent, cave, tick);
        assertEquals(on, back, "ON に戻せば元どおり");
    }

    private static Map<BehaviorType, BehaviorScore> scores(WolfAiAgent agent, SpatialRegion region, long tick) {
        WolfContextSnapshot context = WolfContextSnapshot.builder().ownerDistance(5.0)
                .regionFear(agent.regionFear(region)).build();
        var memory = agent.condition().usesEpisodicMemory()
                ? MemoryImpactCalculator.summarise(MemoryRetriever.retrieve(agent.memoryStore(),
                        MemoryQuery.atLocation(region, "subject-x", tick)))
                : jp.ac.naist.hai.aiwolf.memory.MemorySummary.empty();
        return new BehaviorScorer(BehaviorWeights.createDefault()).score(agent.state(), context, memory,
                agent.preferences(), null, BehaviorEligibility.evaluate(context), agent.condition());
    }

    @Test
    @DisplayName("評価モード（学習の凍結）は実験セッション中にも使え、セッションも Demo Mode もなければ使えない")
    void evaluationInStudySessions() {
        assertFalse(DemoMode.setEvaluationActive(true));
        StudySession.start("p001-s01", 0L, 0L, ZonedDateTime.now(ZoneOffset.UTC));
        assertTrue(DemoMode.setEvaluationActive(true));
        assertFalse(DemoMode.isEnabled(), "Demo Mode にはならない（ログは本実験の出力先に残る）");
    }
}
