package jp.ac.naist.hai.aiwolf.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorEligibility;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorScore;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorScorer;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorWeights;
import jp.ac.naist.hai.aiwolf.behavior.PolicySelector;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.core.JsonWriter;
import jp.ac.naist.hai.aiwolf.learning.PreferenceVector;
import jp.ac.naist.hai.aiwolf.memory.MemorySummary;
import jp.ac.naist.hai.aiwolf.random.WolfRandomProvider;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;

/** 研究ログの直列化テスト。因果連鎖が追跡できることを保証する。 */
class DecisionLogJsonTest {

    private static final UUID WOLF = UUID.fromString("00000000-0000-4000-8000-0000000000d4");

    private BehaviorDecision sampleDecision(WolfInternalState state) {
        WolfContextSnapshot context = WolfContextSnapshot.builder().ownerDistance(5.0).build();
        Set<BehaviorType> eligible = BehaviorEligibility.evaluate(context);
        Map<BehaviorType, BehaviorScore> scores = new BehaviorScorer(BehaviorWeights.createDefault())
                .score(state, context, MemorySummary.empty(), PreferenceVector.createInitial(),
                        null, eligible, ExperimentalCondition.ADAPTIVE);
        return new PolicySelector().select(scores, WolfRandomProvider.forWolf(11L, WOLF));
    }

    @Test
    @DisplayName("決定ログは全候補のスコア内訳を含む 1 行 JSON である")
    void decisionLineContainsFullScoreBreakdown() {
        WolfInternalState state = WolfInternalState.createInitial();
        BehaviorDecision decision = sampleDecision(state);

        String line = DecisionLogJson.toJsonLine(WOLF.toString(), "subject-001",
                ExperimentalCondition.ADAPTIVE, 20260919L, 1234L, 24000L, 1_700_000_000_000L,
                "minecraft:overworld", "minecraft:plains", 10.5, 64.0, -20.25,
                state, decision, List.of("mem-1", "mem-2"));

        assertFalse(line.contains("\n"), "JSONL の 1 レコードに改行が含まれてはならない");
        for (String component : List.of("\"base\"", "\"state\"", "\"context\"", "\"memory\"",
                "\"preference\"", "\"persistence\"", "\"penalty\"", "\"finalScore\"",
                "\"probability\"", "\"selected\"")) {
            assertTrue(line.contains(component), "missing component: " + component);
        }
        assertTrue(line.contains("\"decisionIndex\":0"));
        assertTrue(line.contains("\"randomSeed\":1234"));
        assertTrue(line.contains("\"subjectId\":\"subject-001\""));
        assertTrue(line.contains("\"selectedBehavior\":\"" + decision.selected().id() + "\""));
    }

    @Test
    @DisplayName("プレイヤー名などの個人情報は匿名 ID 経由でしか入らない")
    void personalInformationIsNotStored() {
        WolfInternalState state = WolfInternalState.createInitial();
        String line = DecisionLogJson.toJsonLine(WOLF.toString(), null,
                ExperimentalCondition.STATIC, 1L, 2L, 3L, 4L, "minecraft:overworld",
                "minecraft:plains", 0.0, 0.0, 0.0, state, sampleDecision(state), List.of());
        assertTrue(line.contains("\"subjectId\":null"));
    }

    @Test
    @DisplayName("JsonWriter は制御文字と引用符を正しくエスケープする")
    void jsonWriterEscapesSpecialCharacters() {
        assertEquals("\"a\\\"b\"", JsonWriter.quote("a\"b"));
        assertEquals("\"line1\\nline2\"", JsonWriter.quote("line1\nline2"));
        assertEquals("\"tab\\there\"", JsonWriter.quote("tab\there"));
        assertEquals("\"\\u0001\"", JsonWriter.quote("\u0001"));
        assertEquals("\"back\\\\slash\"", JsonWriter.quote("back\\slash"));
    }

    @Test
    @DisplayName("JsonWriter は入れ子構造を正しく整形する")
    void jsonWriterHandlesNestedStructures() {
        String json = JsonWriter.compact()
                .beginObject()
                .property("name", "wolf")
                .beginArray("values")
                .value(1.0)
                .value(2.5)
                .endArray()
                .beginObject("nested")
                .property("flag", true)
                .endObject()
                .endObject()
                .toJson();
        assertEquals("{\"name\":\"wolf\",\"values\":[1.0,2.5],\"nested\":{\"flag\":true}}", json);
    }
}
