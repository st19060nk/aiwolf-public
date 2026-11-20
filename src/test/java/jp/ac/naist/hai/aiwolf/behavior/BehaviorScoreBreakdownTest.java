package jp.ac.naist.hai.aiwolf.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.learning.PreferenceVector;
import jp.ac.naist.hai.aiwolf.log.DecisionLogJson;
import jp.ac.naist.hai.aiwolf.memory.MemorySummary;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 14A / 14B：スコアの成分分解の検証。 */
final class BehaviorScoreBreakdownTest {

    private static final BehaviorScorer SCORER = new BehaviorScorer(BehaviorWeights.createDefault());
    private static final double EPSILON = 1e-12;

    private static Map<BehaviorType, BehaviorScore> score(WolfInternalState state, WolfContextSnapshot context,
                                                          MemorySummary memory, PreferenceVector preferences,
                                                          ExperimentalCondition condition) {
        return SCORER.score(state, context, memory, preferences, BehaviorType.FOLLOW_CLOSE,
                BehaviorEligibility.evaluate(context), condition);
    }

    @Test
    @DisplayName("成分の和は total と浮動小数点誤差の範囲で一致する（乱数で 2,000 通り）")
    void componentsSumToTotal() {
        SplittableRandom random = new SplittableRandom(7L);
        for (int i = 0; i < 2_000; i++) {
            WolfInternalState state = WolfInternalState.createInitial();
            for (StateParameter parameter : StateParameter.values()) {
                state.set(parameter, random.nextDouble());
            }
            Map<BehaviorType, Double> effects = new EnumMap<>(BehaviorType.class);
            PreferenceVector preferences = PreferenceVector.createInitial();
            for (BehaviorType behavior : BehaviorType.ALL) {
                effects.put(behavior, random.nextDouble() * 2.0 - 1.0);
                preferences.set(behavior, random.nextDouble() * 2.0 - 1.0);
            }
            WolfContextSnapshot context = WolfContextSnapshot.builder()
                    .ownerDistance(random.nextDouble() * 30.0)
                    .wolfHealthRatio(random.nextDouble())
                    .hostileMobCount(random.nextInt(3))
                    .nearestHostileDistance(5.0 + random.nextDouble() * 10.0)
                    .regionFear(random.nextDouble())
                    .night(random.nextBoolean())
                    .build();
            for (BehaviorScore score : score(state, context, new MemorySummary(effects, List.of()), preferences,
                    ExperimentalCondition.ADAPTIVE).values()) {
                assertEquals(score.finalScore(), score.componentSum(), EPSILON);
                double fromMap = score.components().values().stream().mapToDouble(Double::doubleValue).sum();
                assertEquals(score.finalScore(), fromMap, EPSILON);
                assertEquals(score.context(), score.contextExcludingMemoryAndEmergency()
                        + score.spatialMemory() + score.emergency(), EPSILON);
            }
        }
    }

    @Test
    @DisplayName("空間記憶の成分は LOCATION_FEAR × 重み、緊急退避の成分は EMERGENCY_LOW_HEALTH × ボーナス")
    void contextBreakdownMatchesTheWeights() {
        WolfContextSnapshot context = WolfContextSnapshot.builder()
                .ownerDistance(5.0).regionFear(0.6).wolfHealthRatio(0.10).build();
        Map<BehaviorType, BehaviorScore> scores = score(WolfInternalState.createInitial(), context,
                MemorySummary.empty(), PreferenceVector.createInitial(), ExperimentalCondition.ADAPTIVE);

        assertEquals(1.3 * 0.6, scores.get(BehaviorType.AVOID_LOCATION).spatialMemory(), EPSILON);
        assertEquals(-0.4 * 0.6, scores.get(BehaviorType.EXPLORE_NEARBY).spatialMemory(), EPSILON);
        assertEquals(AiWolfParameters.EMERGENCY_RETREAT_BONUS, scores.get(BehaviorType.RETREAT).emergency(), EPSILON);
        assertEquals(0.0, scores.get(BehaviorType.FOLLOW_CLOSE).spatialMemory());
        assertEquals(0.0, scores.get(BehaviorType.FOLLOW_CLOSE).emergency());

        WolfContextSnapshot healthy = WolfContextSnapshot.builder().ownerDistance(5.0).wolfHealthRatio(0.9).build();
        assertEquals(0.0, score(WolfInternalState.createInitial(), healthy, MemorySummary.empty(),
                PreferenceVector.createInitial(), ExperimentalCondition.ADAPTIVE).get(BehaviorType.RETREAT).emergency());
    }

    @Test
    @DisplayName("記憶を使わない条件ではエピソード記憶・空間記憶の成分が 0、選好を使わない条件では選好の成分が 0")
    void disabledMechanismsContributeNothing() {
        // 実際の経験を与えた個体で確かめる（空間記憶も出来事から作られる）。
        for (ExperimentalCondition condition : List.of(ExperimentalCondition.STATE_ONLY,
                ExperimentalCondition.STATIC)) {
            WolfAiAgent agent = new WolfAiAgent("12345678-1234-4234-8234-123456789abc", condition, 1L);
            SpatialRegion region = SpatialRegion.of("minecraft:overworld", 10.0, 64.0, 10.0);
            for (WolfEventType type : List.of(WolfEventType.WOLF_DAMAGED, WolfEventType.WOLF_DAMAGED,
                    WolfEventType.WOLF_LOW_HEALTH, WolfEventType.PET, WolfEventType.PLAYER_PROTECTED_WOLF)) {
                agent.onEvent(WolfEvent.of(type, 100L, "minecraft:overworld", "minecraft:plains",
                        10.0, 64.0, 10.0, "subject-x", 0.5, 1.0));
            }
            WolfContextSnapshot context = WolfContextSnapshot.builder().ownerDistance(5.0)
                    .regionFear(agent.regionFear(region)).build();
            BehaviorDecision decision = agent.decide(context, region, "subject-x", 200L);
            for (BehaviorScore score : decision.scores().values()) {
                assertEquals(0.0, score.episodicMemory(), condition + " " + score.behavior());
                assertEquals(0.0, score.spatialMemory(), condition + " " + score.behavior());
                assertEquals(0.0, score.preference(), condition + " " + score.behavior());
            }
        }
    }

    @Test
    @DisplayName("条件を切り替えても基礎値・状態・文脈・継続の成分と温度は変わらない（変わるのは学習機構の成分だけ）")
    void conditionsShareEverythingButLearning() {
        WolfInternalState state = WolfInternalState.createInitial();
        state.set(StateParameter.ATTACHMENT, 0.8);
        state.set(StateParameter.FEAR, 0.6);
        WolfContextSnapshot context = WolfContextSnapshot.builder().ownerDistance(9.0).hostileMobCount(1)
                .nearestHostileDistance(6.0).regionFear(0.5).build();
        Map<BehaviorType, Double> effects = new EnumMap<>(BehaviorType.class);
        effects.put(BehaviorType.RETREAT, 0.4);
        PreferenceVector preferences = PreferenceVector.createInitial();
        preferences.set(BehaviorType.APPROACH_PLAYER, 0.3);
        MemorySummary memory = new MemorySummary(effects, List.of("m"));

        Map<BehaviorType, BehaviorScore> reference = score(state, context, memory, preferences,
                ExperimentalCondition.ADAPTIVE);
        for (ExperimentalCondition condition : ExperimentalCondition.values()) {
            Map<BehaviorType, BehaviorScore> scores = score(state, context, memory, preferences, condition);
            assertEquals(reference.keySet(), scores.keySet(), "eligibility must not depend on the condition");
            for (BehaviorType behavior : scores.keySet()) {
                BehaviorScore a = reference.get(behavior);
                BehaviorScore b = scores.get(behavior);
                assertEquals(a.base(), b.base());
                assertEquals(a.state(), b.state());
                assertEquals(a.context(), b.context());
                assertEquals(a.spatialMemory(), b.spatialMemory());
                assertEquals(a.emergency(), b.emergency());
                assertEquals(a.persistence(), b.persistence());
            }
        }
        assertEquals(AiWolfParameters.SOFTMAX_TEMPERATURE, new PolicySelector().temperature());
    }

    @Test
    @DisplayName("決定ログには全候補の内訳（空間記憶・緊急・その他の文脈・成分和）とスキーマ番号が入る")
    void decisionLogCarriesTheBreakdown() {
        WolfContextSnapshot context = WolfContextSnapshot.builder().ownerDistance(5.0).regionFear(0.5).build();
        Set<BehaviorType> eligible = BehaviorEligibility.evaluate(context);
        Map<BehaviorType, BehaviorScore> scores = SCORER.score(WolfInternalState.createInitial(), context,
                MemorySummary.empty(), PreferenceVector.createInitial(), null, eligible, ExperimentalCondition.ADAPTIVE);
        BehaviorDecision decision = new PolicySelector().select(scores,
                jp.ac.naist.hai.aiwolf.random.WolfRandomProvider.restore(1L, 0L));
        String json = DecisionLogJson.toJsonLine("wolf", "subject-x", ExperimentalCondition.ADAPTIVE, 1L, 2L, 3L, 4L,
                "minecraft:overworld", "minecraft:plains", 0.0, 0.0, 0.0,
                WolfInternalState.createInitial(), decision, List.of());

        assertTrue(json.contains("\"decisionLogSchemaVersion\":" + AiWolfParameters.DECISION_LOG_SCHEMA_VERSION));
        int candidates = json.split("\"behavior\":", -1).length - 1;
        assertEquals(eligible.size(), candidates, "every candidate must be logged (Option A)");
        for (String field : List.of("contextSpatialMemory", "contextEmergency", "contextOther", "componentSum")) {
            assertEquals(candidates, json.split("\"" + field + "\":", -1).length - 1, field);
        }
        assertTrue(json.contains("\"contextSpatialMemory\":0.65"), "AVOID_LOCATION: 1.3 × 0.5");
    }
}
