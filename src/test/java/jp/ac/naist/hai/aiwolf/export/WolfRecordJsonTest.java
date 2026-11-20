package jp.ac.naist.hai.aiwolf.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.core.MathSupport;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.state.StateParameter;

/** {@link WolfRecordJson} の単体テスト。 */
class WolfRecordJsonTest {

    private static final UUID WOLF = UUID.fromString("00000000-0000-4000-8000-0000000000e5");

    private static WolfAiAgent rearedAgent() {
        WolfAiAgent agent = new WolfAiAgent(WOLF.toString(), ExperimentalCondition.ADAPTIVE,
                AiWolfParameters.DEFAULT_EXPERIMENT_SEED);
        agent.setOwnerResearchId("subject-001");
        for (int i = 0; i < 5; i++) {
            agent.onEvent(WolfEvent.of(WolfEventType.PET, i * 600L, "minecraft:overworld",
                    "minecraft:plains", 10.0, 64.0, -5.0, "subject-001", 1.0, 1.0));
        }
        return agent;
    }

    @Test
    @DisplayName("wolves.json に状態・選好・空間記憶が含まれる")
    void exportContainsStatePreferencesAndSpatialMemory() {
        String json = WolfRecordJson.toWolvesJson(List.of(rearedAgent()), 24_000L,
                1_700_000_000_000L, AiWolfParameters.DEFAULT_EXPERIMENT_SEED);

        for (String key : List.of("\"schemaVersion\"", "\"parameterSetVersion\"", "\"experimentSeed\"",
                "\"wolfUuid\"", "\"ownerResearchId\"", "\"experimentalCondition\"",
                "\"randomSeed\"", "\"decisionIndex\"", "\"state\"", "\"learnedPreferences\"",
                "\"spatialMemory\"", "\"memoryCount\"")) {
            assertTrue(json.contains(key), "missing key: " + key);
        }
        for (StateParameter parameter : StateParameter.ALL) {
            assertTrue(json.contains("\"" + parameter.key() + "\""), parameter.key());
        }
        for (BehaviorType behavior : BehaviorType.ALL) {
            assertTrue(json.contains("\"" + behavior.id() + "\""), behavior.id());
        }
    }

    @Test
    @DisplayName("育成の結果が数値として書き出される")
    void exportReflectsRearing() {
        WolfAiAgent agent = rearedAgent();
        String json = WolfRecordJson.toWolvesJson(List.of(agent), 0L, 0L, 1L);

        assertTrue(agent.state().attachment() > 0.5, "撫でられた個体の愛着は上がっているはず");
        assertTrue(json.contains("\"attachment\": "
                + MathSupport.round(agent.state().attachment(), AiWolfParameters.EXPORT_DECIMALS)));
        assertEquals(5, agent.memoryStore().size());
    }

    @Test
    @DisplayName("プレイヤー名は書き出されず、匿名 ID のみが入る")
    void exportContainsNoPersonalInformation() {
        String json = WolfRecordJson.toWolvesJson(List.of(rearedAgent()), 0L, 0L, 1L);
        assertTrue(json.contains("\"ownerResearchId\": \"subject-001\""));
        assertFalse(json.contains("playerName"));
    }

    @Test
    @DisplayName("個体がいなくてもスキーマは成立する")
    void exportHandlesEmptyPopulation() {
        String json = WolfRecordJson.toWolvesJson(List.of(), 0L, 0L, 1L);
        assertTrue(json.contains("\"wolfCount\": 0"));
        assertTrue(json.contains("\"wolves\": []"));
    }
}
