package jp.ac.naist.hai.aiwolf.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.agent.EventOutcome;
import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.WolfMemory;

/** 出来事ログと記憶ログの直列化テスト。 */
class EventLogJsonTest {

    private static final String WOLF = "00000000-0000-4000-8000-0000000000f1";

    private static WolfEvent sampleEvent() {
        return WolfEvent.of(WolfEventType.PLAYER_PROTECTED_WOLF, 12_345L, "minecraft:overworld",
                "minecraft:taiga", 8.5, 63.0, -12.25, "subject-abc123def456", 0.4, 0.9);
    }

    @Test
    @DisplayName("出来事ログは1行で、適用後の状態を含む")
    void eventLineCarriesStateAfter() {
        WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE,
                AiWolfParameters.DEFAULT_EXPERIMENT_SEED);
        WolfEvent event = sampleEvent();
        EventOutcome outcome = agent.onEvent(event);

        String line = EventLogJson.toJsonLine(WOLF, "subject-abc123def456",
                ExperimentalCondition.ADAPTIVE, event, outcome, 1_700_000_000_000L);

        assertFalse(line.contains("\n"));
        for (String key : List.of("\"recordType\":\"event\"", "\"eventType\":\"player_protected_wolf\"",
                "\"valence\"", "\"arousal\"", "\"salience\"", "\"stateAfter\"", "\"region\"")) {
            assertTrue(line.contains(key), "missing: " + key);
        }
        assertTrue(line.contains("\"subjectId\":\"subject-abc123def456\""));
    }

    @Test
    @DisplayName("記憶ログは決定ログの relevantMemoryIds と突き合わせられる")
    void memoryLineCarriesItsId() {
        WolfMemory memory = WolfMemory.fromEvent(sampleEvent(), WOLF + "#m0");
        String line = MemoryLogJson.toJsonLine(WOLF, memory);

        assertFalse(line.contains("\n"));
        assertTrue(line.contains("\"memoryId\":\"" + WOLF + "#m0\""));
        assertTrue(line.contains("\"recordType\":\"memory\""));
        assertTrue(line.contains("\"salience\""));
    }

    @Test
    @DisplayName("ログにプレイヤー UUID がそのまま入らない")
    void logsCarryNoRawPlayerUuid() {
        WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 1L);
        WolfEvent event = sampleEvent();
        EventOutcome outcome = agent.onEvent(event);
        String line = EventLogJson.toJsonLine(WOLF, "subject-abc123def456",
                ExperimentalCondition.ADAPTIVE, event, outcome, 0L);
        assertTrue(line.contains("subject-"));
        assertFalse(line.contains("playerName"));
    }
    @Test
    @DisplayName("戦闘由来のイベントは combatId 付きで記録される")
    void combatMetadataIsLogged() {
        WolfEvent event = WolfEvent.of(WolfEventType.PLAYER_PROTECTED_WOLF, 1234L,
                "minecraft:overworld", "minecraft:plains", 10.0, 64.0, -20.0,
                "subject-abc123def456", 0.4, 0.9,
                java.util.Map.of(WolfEvent.META_COMBAT_ID, WOLF + "#c3",
                        WolfEvent.META_HOSTILE_TYPE, "minecraft:zombie",
                        WolfEvent.META_HOSTILE_ID, "hostile-7"));
        WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 1L);
        EventOutcome outcome = agent.onEvent(event);

        String line = EventLogJson.toJsonLine(WOLF, "subject-abc123def456",
                ExperimentalCondition.ADAPTIVE, event, outcome, 0L);

        assertFalse(line.contains("\n"));
        assertTrue(line.contains("\"metadata\""));
        assertTrue(line.contains("\"combatId\":\"" + WOLF + "#c3\""),
                "combatId がないと 1 回の戦闘から何件発火したか検証できない");
        assertTrue(line.contains("\"hostileType\":\"minecraft:zombie\""));
        assertTrue(line.contains("\"hostileId\":\"hostile-7\""));
    }

    @Test
    @DisplayName("metadata がないイベントでは metadata キーを出さない")
    void metadataIsOmittedWhenEmpty() {
        WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 1L);
        WolfEvent event = sampleEvent();
        EventOutcome outcome = agent.onEvent(event);

        String line = EventLogJson.toJsonLine(WOLF, "subject-abc123def456",
                ExperimentalCondition.ADAPTIVE, event, outcome, 0L);

        assertFalse(line.contains("\"metadata\""));
    }
    @Test
    @DisplayName("出来事ログに適用前後と差分が残る")
    void eventLineCarriesBeforeAfterAndDelta() {
        WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 1L);
        // 先に何度か経験させ、before が初期値と違う状態にしておく。
        for (int i = 0; i < 5; i++) {
            agent.onEvent(sampleEvent());
        }
        WolfEvent event = WolfEvent.of(WolfEventType.PLAYER_PROTECTED_WOLF, 5000L,
                "minecraft:overworld", "minecraft:plains", 1.0, 64.0, 2.0,
                "subject-abc123def456", 0.4, 0.9);
        EventOutcome outcome = agent.onEvent(event);

        String line = EventLogJson.toJsonLine(WOLF, "subject-abc123def456",
                ExperimentalCondition.ADAPTIVE, event, outcome, 0L);

        assertFalse(line.contains("\n"));
        for (String key : List.of("\"stateBefore\"", "\"stateAfter\"", "\"stateDelta\"",
                "\"preferenceBefore\"", "\"preferenceAfter\"", "\"preferenceDelta\"",
                "\"createdMemoryId\"")) {
            assertTrue(line.contains(key), "missing: " + key);
        }
        // Phase 19 で wolfSubjectId / experimentSeed / randomSeed / adaptiveFeatures を追加したので 4。
        assertTrue(line.contains("\"schemaVersion\":4"), "項目が増えたらスキーマ版を上げる");
        assertTrue(line.contains("\"trust\""));
        assertTrue(line.contains("\"follow_close\""));
    }

    @Test
    @DisplayName("差分は before と after の引き算と一致する")
    void deltaAgreesWithBeforeAndAfter() {
        WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 1L);
        EventOutcome outcome = agent.onEvent(sampleEvent());

        outcome.stateDelta().forEach((parameter, delta) -> assertEquals(
                outcome.stateAfter().get(parameter) - outcome.stateBefore().get(parameter),
                delta, 1e-12, parameter.key()));
        assertTrue(outcome.createdMemoryId().startsWith(WOLF + "#m"));
    }

    @Test
    @DisplayName("STATIC では記憶が作られず選好も動かない")
    void staticConditionProducesNoMemoryAndNoPreferenceChange() {
        WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.STATIC, 1L);
        EventOutcome outcome = agent.onEvent(sampleEvent());

        assertEquals(null, outcome.createdMemoryId());
        assertEquals(0.0, outcome.largestPreferenceChange(), 0.0);

        String line = EventLogJson.toJsonLine(WOLF, "subject-abc123def456",
                ExperimentalCondition.STATIC, sampleEvent(), outcome, 0L);
        assertTrue(line.contains("\"createdMemoryId\":null"));
    }
}
