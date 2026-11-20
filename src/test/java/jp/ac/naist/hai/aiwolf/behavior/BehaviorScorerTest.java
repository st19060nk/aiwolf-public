package jp.ac.naist.hai.aiwolf.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.learning.PreferenceVector;
import jp.ac.naist.hai.aiwolf.memory.MemorySummary;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;

/** {@link BehaviorScorer} の単体テスト。Minecraft を起動せずに検証できる。 */
class BehaviorScorerTest {

    private static final double EPS = 1.0e-9;

    private final BehaviorScorer scorer = new BehaviorScorer(BehaviorWeights.createDefault());

    private Map<BehaviorType, BehaviorScore> scoreWith(WolfInternalState state,
                                                       WolfContextSnapshot context,
                                                       MemorySummary memory,
                                                       PreferenceVector preferences,
                                                       BehaviorType current,
                                                       ExperimentalCondition condition) {
        Set<BehaviorType> eligible = BehaviorEligibility.evaluate(context);
        return scorer.score(state, context, memory, preferences, current, eligible, condition);
    }

    @Test
    @DisplayName("仕様書の計算例どおり FOLLOW_CLOSE の StateEffect が 1.10 になる")
    void stateEffectMatchesWorkedExample() {
        WolfInternalState state = WolfInternalState.createInitial();
        state.set(StateParameter.ATTACHMENT, 0.8);
        state.set(StateParameter.FEAR, 0.6);
        state.set(StateParameter.CURIOSITY, 0.4);
        state.set(StateParameter.AUTONOMY, 0.2);
        state.set(StateParameter.STRESS, 0.5); // z = 0 なので寄与しない

        // 脅威・夜間なしの文脈にして、StateEffect のみを取り出す。
        WolfContextSnapshot context = WolfContextSnapshot.builder().build();
        Map<BehaviorType, BehaviorScore> scores = scoreWith(state, context,
                MemorySummary.empty(), PreferenceVector.createInitial(), null,
                ExperimentalCondition.ADAPTIVE);

        // 0.9*0.6 + 0.7*0.2 + 0.4*0.0 - 0.7*(-0.6) = 1.10
        assertEquals(1.10, scores.get(BehaviorType.FOLLOW_CLOSE).state(), 1.0e-12);
    }

    @Test
    @DisplayName("finalScore は各成分の合計と一致する")
    void finalScoreEqualsSumOfComponents() {
        Map<BehaviorType, Double> memoryImpacts = new EnumMap<>(BehaviorType.class);
        memoryImpacts.put(BehaviorType.RETREAT, 0.6);
        MemorySummary memory = new MemorySummary(memoryImpacts, List.of("m1"));

        PreferenceVector preferences = PreferenceVector.createInitial();
        preferences.set(BehaviorType.RETREAT, 0.4);

        Map<BehaviorType, BehaviorScore> scores = scoreWith(WolfInternalState.createInitial(),
                WolfContextSnapshot.builder().build(), memory, preferences,
                BehaviorType.RETREAT, ExperimentalCondition.ADAPTIVE);

        BehaviorScore score = scores.get(BehaviorType.RETREAT);
        double expected = score.base() + score.state() + score.context()
                + score.memory() + score.preference() + score.persistence() - score.penalty();
        assertEquals(expected, score.finalScore(), EPS);
        assertEquals(0.6, score.memory(), EPS);
        assertEquals(0.4, score.preference(), EPS);
        assertEquals(AiWolfParameters.PERSISTENCE_BONUS, score.persistence(), EPS);
    }

    @Test
    @DisplayName("STATIC 条件では記憶と学習済み選好がスコアへ寄与しない")
    void staticConditionIgnoresMemoryAndPreference() {
        Map<BehaviorType, Double> memoryImpacts = new EnumMap<>(BehaviorType.class);
        memoryImpacts.put(BehaviorType.EXPLORE_NEARBY, 0.9);
        MemorySummary memory = new MemorySummary(memoryImpacts, List.of("m1"));
        PreferenceVector preferences = PreferenceVector.createInitial();
        preferences.set(BehaviorType.EXPLORE_NEARBY, 0.9);

        WolfInternalState state = WolfInternalState.createInitial();
        WolfContextSnapshot context = WolfContextSnapshot.builder().build();

        BehaviorScore staticScore = scoreWith(state, context, memory, preferences, null,
                ExperimentalCondition.STATIC).get(BehaviorType.EXPLORE_NEARBY);
        BehaviorScore stateOnlyScore = scoreWith(state, context, memory, preferences, null,
                ExperimentalCondition.STATE_ONLY).get(BehaviorType.EXPLORE_NEARBY);
        BehaviorScore adaptiveScore = scoreWith(state, context, memory, preferences, null,
                ExperimentalCondition.ADAPTIVE).get(BehaviorType.EXPLORE_NEARBY);

        assertEquals(0.0, staticScore.memory(), EPS);
        assertEquals(0.0, staticScore.preference(), EPS);
        assertEquals(0.0, stateOnlyScore.memory(), EPS);
        assertEquals(0.0, stateOnlyScore.preference(), EPS);
        assertEquals(0.9, adaptiveScore.memory(), EPS);
        assertEquals(0.9, adaptiveScore.preference(), EPS);

        // 重要：STATIC でも同じ行動集合・同じ基礎スコア構造を使う（バニラ化しない）。
        assertEquals(staticScore.base(), adaptiveScore.base(), EPS);
        assertEquals(staticScore.state(), adaptiveScore.state(), EPS);
        assertEquals(staticScore.context(), adaptiveScore.context(), EPS);
    }

    @Test
    @DisplayName("全条件で候補行動集合が同一である")
    void allConditionsShareTheSameBehaviourSet() {
        WolfInternalState state = WolfInternalState.createInitial();
        WolfContextSnapshot context = WolfContextSnapshot.builder()
                .hostileMobCount(1).nearestHostileDistance(6.0).regionFear(0.5).build();
        Set<BehaviorType> staticKeys = scoreWith(state, context, MemorySummary.empty(),
                PreferenceVector.createInitial(), null, ExperimentalCondition.STATIC).keySet();
        Set<BehaviorType> adaptiveKeys = scoreWith(state, context, MemorySummary.empty(),
                PreferenceVector.createInitial(), null, ExperimentalCondition.ADAPTIVE).keySet();
        assertEquals(staticKeys, adaptiveKeys);
        assertEquals(8, adaptiveKeys.size());
    }

    @Test
    @DisplayName("瀕死のときは RETREAT のスコアが圧倒的に高くなる")
    void emergencyOverrideMakesRetreatDominant() {
        WolfInternalState state = WolfInternalState.createInitial();
        state.set(StateParameter.CURIOSITY, 1.0);
        state.set(StateParameter.AUTONOMY, 1.0);
        state.set(StateParameter.FEAR, 0.0);

        WolfContextSnapshot context = WolfContextSnapshot.builder()
                .wolfHealthRatio(0.10)
                .hostileMobCount(2)
                .nearestHostileDistance(4.0)
                .wolfUnderAttack(true)
                .build();

        Map<BehaviorType, BehaviorScore> scores = scoreWith(state, context, MemorySummary.empty(),
                PreferenceVector.createInitial(), BehaviorType.EXPLORE_NEARBY,
                ExperimentalCondition.ADAPTIVE);

        double retreat = scores.get(BehaviorType.RETREAT).finalScore();
        for (Map.Entry<BehaviorType, BehaviorScore> entry : scores.entrySet()) {
            if (entry.getKey() != BehaviorType.RETREAT) {
                assertTrue(retreat > entry.getValue().finalScore(),
                        "RETREAT should have the highest score but " + entry.getKey() + " was higher");
            }
        }

        // スコアの大小だけでなく、実際の選択確率が支配的であることまで確認する。
        double[] finalScores = scores.values().stream().mapToDouble(BehaviorScore::finalScore).toArray();
        double[] probabilities = jp.ac.naist.hai.aiwolf.core.MathSupport.softmax(
                finalScores, AiWolfParameters.SOFTMAX_TEMPERATURE);
        int retreatIndex = new java.util.ArrayList<>(scores.keySet()).indexOf(BehaviorType.RETREAT);
        assertTrue(probabilities[retreatIndex] > 0.90,
                "瀕死時の RETREAT 確率が低すぎる: " + probabilities[retreatIndex]);
    }

    @Test
    @DisplayName("継続ボーナスは現在実行中の行動にのみ加算される")
    void persistenceBonusAppliesOnlyToCurrentBehaviour() {
        Map<BehaviorType, BehaviorScore> scores = scoreWith(WolfInternalState.createInitial(),
                WolfContextSnapshot.builder().build(), MemorySummary.empty(),
                PreferenceVector.createInitial(), BehaviorType.FOLLOW_NORMAL,
                ExperimentalCondition.ADAPTIVE);
        assertEquals(AiWolfParameters.PERSISTENCE_BONUS,
                scores.get(BehaviorType.FOLLOW_NORMAL).persistence(), EPS);
        assertEquals(0.0, scores.get(BehaviorType.EXPLORE_NEARBY).persistence(), EPS);
    }

    @Test
    @DisplayName("同一の状態・文脈・選好なら、どの個体でも完全に同じスコアになる")
    void scoringHasNoPerIndividualBias() {
        WolfInternalState stateA = WolfInternalState.createInitial();
        WolfInternalState stateB = WolfInternalState.createInitial();
        WolfContextSnapshot context = WolfContextSnapshot.builder().ownerDistance(7.0).build();

        Map<BehaviorType, BehaviorScore> scoresA = scoreWith(stateA, context, MemorySummary.empty(),
                PreferenceVector.createInitial(), null, ExperimentalCondition.ADAPTIVE);
        Map<BehaviorType, BehaviorScore> scoresB = scoreWith(stateB, context, MemorySummary.empty(),
                PreferenceVector.createInitial(), null, ExperimentalCondition.ADAPTIVE);

        for (BehaviorType behavior : scoresA.keySet()) {
            assertEquals(scoresA.get(behavior).finalScore(), scoresB.get(behavior).finalScore(), 0.0);
        }
    }

    @Test
    @DisplayName("実行不能な行動はスコア表に現れない")
    void ineligibleBehavioursAreNotScored() {
        WolfContextSnapshot context = WolfContextSnapshot.builder()
                .ownerPresent(false).regionFear(0.0).build();
        Map<BehaviorType, BehaviorScore> scores = scoreWith(WolfInternalState.createInitial(), context,
                MemorySummary.empty(), PreferenceVector.createInitial(), null,
                ExperimentalCondition.ADAPTIVE);
        assertFalse(scores.containsKey(BehaviorType.FOLLOW_CLOSE));
        assertFalse(scores.containsKey(BehaviorType.AVOID_LOCATION));
        assertTrue(scores.containsKey(BehaviorType.EXPLORE_NEARBY));
    }

    @Test
    @DisplayName("恐怖が高いと RETREAT が上がり EXPLORE が下がる")
    void fearShiftsBalanceFromExplorationToRetreat() {
        WolfContextSnapshot context = WolfContextSnapshot.builder().build();

        WolfInternalState calm = WolfInternalState.createInitial();
        calm.set(StateParameter.FEAR, 0.1);
        WolfInternalState fearful = WolfInternalState.createInitial();
        fearful.set(StateParameter.FEAR, 0.9);

        Map<BehaviorType, BehaviorScore> calmScores = scoreWith(calm, context, MemorySummary.empty(),
                PreferenceVector.createInitial(), null, ExperimentalCondition.ADAPTIVE);
        Map<BehaviorType, BehaviorScore> fearScores = scoreWith(fearful, context, MemorySummary.empty(),
                PreferenceVector.createInitial(), null, ExperimentalCondition.ADAPTIVE);

        assertTrue(fearScores.get(BehaviorType.RETREAT).finalScore()
                > calmScores.get(BehaviorType.RETREAT).finalScore());
        assertTrue(fearScores.get(BehaviorType.EXPLORE_NEARBY).finalScore()
                < calmScores.get(BehaviorType.EXPLORE_NEARBY).finalScore());
        assertTrue(fearScores.get(BehaviorType.FOLLOW_CLOSE).finalScore()
                > calmScores.get(BehaviorType.FOLLOW_CLOSE).finalScore());
    }
}
