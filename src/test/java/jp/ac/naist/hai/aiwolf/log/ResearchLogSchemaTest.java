package jp.ac.naist.hai.aiwolf.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jp.ac.naist.hai.aiwolf.agent.EventOutcome;
import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 19：研究ログのスキーマ（Human Study まで使う項目）の検証。 */
final class ResearchLogSchemaTest {

    private static final String WOLF = "12121212-3434-4565-8787-909090909090";
    private static final String OWNER = "subject-0123456789ab";
    private static final String RAW_PLAYER_UUID = "99999999-1234-4abc-8def-999999999999";
    private static final Pattern RUN_ID = Pattern.compile("run-\\d{8}T\\d{6}Z-[0-9a-f]{6}");

    private static WolfAiAgent experiencedAgent() {
        WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 9L);
        agent.setOwnerResearchId(OWNER);
        for (WolfEventType type : List.of(WolfEventType.PET, WolfEventType.WOLF_DAMAGED,
                WolfEventType.PLAYER_PROTECTED_WOLF)) {
            agent.onEvent(WolfEvent.of(type, 100L, "minecraft:overworld", "minecraft:plains",
                    5.0, 64.0, 5.0, OWNER, 0.6, 1.0));
        }
        return agent;
    }

    @Test
    @DisplayName("決定ログには Human Study に必要な項目がすべて入る（Extras あり）")
    void decisionLogHasTheStudyFields() {
        WolfAiAgent agent = experiencedAgent();
        SpatialRegion region = SpatialRegion.of("minecraft:overworld", 5.0, 64.0, 5.0);
        WolfContextSnapshot context = WolfContextSnapshot.builder().ownerDistance(4.0).hostileMobCount(1)
                .nearestHostileDistance(6.0).regionFear(agent.regionFear(region)).build();
        BehaviorDecision decision = agent.decide(context, region, OWNER, 200L);
        String line = DecisionLogJson.toJsonLine(WOLF, OWNER, agent.condition(), 1L, agent.random().seed(), 200L,
                0L, "minecraft:overworld", "minecraft:plains", 5.0, 64.0, 5.0, agent.state(), decision,
                agent.lastRelevantMemoryIds(), new DecisionLogJson.Extras("wolf-abcdef", agent.lastContext(),
                        agent.lastRetrievedMemories(), agent.preferences()));

        for (String key : List.of("subjectId", "wolfSubjectId", "experimentalCondition", "adaptiveFeatures",
                "parameterSetVersion", "experimentSeed", "randomSeed", "decisionIndex", "epochMillis",
                "worldTime", "context", "contextFeatures", "state", "retrievedMemories", "relevantMemoryIds",
                "preferences", "behaviorScores", "selectedBehavior", "selectedProbability", "randomValue",
                "contextSpatialMemory", "componentSum")) {
            assertTrue(line.contains("\"" + key + "\""), "missing: " + key);
        }
        assertTrue(line.contains("\"decisionLogSchemaVersion\":3"));
        assertTrue(line.contains("\"eventType\":\"player_protected_wolf\""), "retrieved memories carry their type");
        assertTrue(line.contains("\"hostileMobCount\":1"));
        assertEquals(agent.lastRetrievedMemories().size(), line.split("\"recency\":", -1).length - 1);
        assertFalse(line.contains(RAW_PLAYER_UUID));
    }

    @Test
    @DisplayName("旧来の呼び出し（Extras なし）でも既存の項目は変わらない")
    void legacyDecisionLogStillWorks() {
        WolfAiAgent agent = experiencedAgent();
        BehaviorDecision decision = agent.decide(WolfContextSnapshot.builder().ownerDistance(4.0).build(),
                null, OWNER, 200L);
        String line = DecisionLogJson.toJsonLine(WOLF, OWNER, agent.condition(), 1L, 2L, 200L, 0L,
                "minecraft:overworld", "minecraft:plains", 0.0, 0.0, 0.0, agent.state(), decision, List.of());
        assertFalse(line.contains("\"wolfSubjectId\""));
        // 各候補のスコア内訳にも "context" という項目があるので、文脈オブジェクト固有の項目で確かめる。
        assertFalse(line.contains("\"contextFeatures\""));
        assertFalse(line.contains("\"ownerPresent\""));
        assertFalse(line.contains("\"retrievedMemories\""));
        assertTrue(line.contains("\"adaptiveFeatures\""), "features come from the condition and are always written");
        assertTrue(line.contains("\"behaviorScores\""));
    }

    @Test
    @DisplayName("出来事ログには匿名の個体 ID・シード・適応機構が入る")
    void eventLogHasTheStudyFields() {
        WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.STATE_MEMORY, 9L);
        WolfEvent event = WolfEvent.of(WolfEventType.PET, 100L, "minecraft:overworld", "minecraft:plains",
                5.0, 64.0, 5.0, OWNER, 1.0, 1.0);
        EventOutcome outcome = agent.onEvent(event);
        String line = EventLogJson.toJsonLine(WOLF, OWNER, agent.condition(), event, outcome, 0L,
                new EventLogJson.Extras("wolf-abcdef", 1L, agent.random().seed()));
        for (String key : List.of("wolfSubjectId", "experimentSeed", "randomSeed", "adaptiveFeatures",
                "stateBefore", "stateAfter", "stateDelta", "preferenceBefore", "preferenceAfter",
                "preferenceDelta", "parameterSetVersion")) {
            assertTrue(line.contains("\"" + key + "\""), "missing: " + key);
        }
        assertTrue(line.contains("\"preferenceLearning\":false"), "STATE_MEMORY has no preference learning");
        assertTrue(line.contains("\"schemaVersion\":" + AiWolfParameters.EVENT_LOG_SCHEMA_VERSION));
    }

    @Test
    @DisplayName("runId は全行の先頭に付き、既存の項目を変えず、個人情報を含まない")
    void runIdEnvelope() {
        String runId = LogEnvelope.newRunId(ZonedDateTime.of(2026, 11, 14, 16, 5, 30, 0, ZoneOffset.ofHours(9)));
        assertTrue(RUN_ID.matcher(runId).matches(), runId);
        assertTrue(runId.startsWith("run-20261114T070530Z-"), "the run id uses UTC");

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("runId", runId);
        assertEquals("{\"runId\":\"" + runId + "\",\"a\":1}", LogEnvelope.prepend("{\"a\":1}", fields));
        assertEquals("{\"runId\":\"" + runId + "\"}", LogEnvelope.prepend("{}", fields));
        assertEquals("not json", LogEnvelope.prepend("not json", fields));
        fields.put("runId", null);
        assertEquals("{\"a\":1}", LogEnvelope.prepend("{\"a\":1}", fields));
    }
}
