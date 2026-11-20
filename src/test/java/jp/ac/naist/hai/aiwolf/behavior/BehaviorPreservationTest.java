package jp.ac.naist.hai.aiwolf.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.learning.PreferenceVector;
import jp.ac.naist.hai.aiwolf.memory.MemorySummary;
import jp.ac.naist.hai.aiwolf.random.WolfRandomProvider;
import jp.ac.naist.hai.aiwolf.simulation.PreExperimentSimulation;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * スコア計算の結果がビット単位で変わっていないことの検証（Phase 14A の回帰テスト）。
 *
 * <p>期待値の指紋は、スコア分解を導入する<strong>前</strong>のコード（コミット 29dde1b）で
 * 計算して固定した。乱数で作った多数の状態・文脈・記憶・選好・現在行動・実験条件について、
 * 全候補の finalScore・Softmax 確率・選択結果を 64 bit の指紋へ畳み込む。
 * 1 bit でも違えば指紋が変わる。</p>
 */
final class BehaviorPreservationTest {

    /** 分解導入前のコードで計算した指紋。 */
    private static final long EXPECTED_SCORE_FINGERPRINT = -4108027312615967564L;

    /** 分解導入前のコードで計算した、実験前シミュレーション（小規模設定）の指紋。 */
    private static final long EXPECTED_SIMULATION_FINGERPRINT = 1793115400018766462L;

    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001B3L + 0x9E3779B97F4A7C15L;
    }

    static long scoreFingerprint() {
        BehaviorScorer scorer = new BehaviorScorer(BehaviorWeights.createDefault());
        PolicySelector selector = new PolicySelector();
        SplittableRandom random = new SplittableRandom(20_261_114L);
        long hash = 17L;
        List<ExperimentalCondition> conditions = List.of(ExperimentalCondition.STATIC,
                ExperimentalCondition.STATE_ONLY, ExperimentalCondition.ADAPTIVE);
        for (int i = 0; i < 4_000; i++) {
            WolfInternalState state = WolfInternalState.createInitial();
            for (StateParameter parameter : StateParameter.values()) {
                state.set(parameter, random.nextDouble());
            }
            WolfContextSnapshot context = WolfContextSnapshot.builder()
                    .ownerPresent(random.nextInt(5) != 0)
                    .ownerDistance(random.nextDouble() * 40.0)
                    .wolfHealthRatio(random.nextDouble())
                    .ownerHealthRatio(random.nextDouble())
                    .hostileMobCount(random.nextInt(4))
                    .nearestHostileDistance(random.nextDouble() * 20.0)
                    .night(random.nextBoolean())
                    .underground(random.nextBoolean())
                    .ownerMoving(random.nextBoolean())
                    .regionFear(random.nextDouble())
                    .recentCombat(random.nextDouble())
                    .ownerUnderAttack(random.nextInt(4) == 0)
                    .wolfUnderAttack(random.nextInt(4) == 0)
                    .build();
            Map<BehaviorType, Double> memoryEffects = new EnumMap<>(BehaviorType.class);
            PreferenceVector preferences = PreferenceVector.createInitial();
            for (BehaviorType behavior : BehaviorType.ALL) {
                if (random.nextBoolean()) {
                    memoryEffects.put(behavior, random.nextDouble() * 2.0 - 1.0);
                }
                preferences.set(behavior, random.nextDouble() * 2.0 - 1.0);
            }
            MemorySummary memory = new MemorySummary(memoryEffects, List.of());
            BehaviorType current = random.nextInt(3) == 0 ? null
                    : BehaviorType.ALL.get(random.nextInt(BehaviorType.ALL.size()));
            ExperimentalCondition condition = conditions.get(random.nextInt(conditions.size()));

            Set<BehaviorType> eligible = BehaviorEligibility.evaluate(context);
            Map<BehaviorType, BehaviorScore> scores = scorer.score(state, context, memory, preferences,
                    current, eligible, condition);
            for (BehaviorType behavior : BehaviorType.ALL) {
                BehaviorScore score = scores.get(behavior);
                hash = mix(hash, score == null ? -1L : Double.doubleToLongBits(score.finalScore()));
            }
            BehaviorDecision decision = selector.select(scores, WolfRandomProvider.restore(i, i));
            hash = mix(hash, decision.selected().ordinal());
            hash = mix(hash, Double.doubleToLongBits(decision.selectedProbability()));
        }
        return hash;
    }

    static long simulationFingerprint() {
        PreExperimentSimulation.Settings settings = new PreExperimentSimulation.Settings(6, 60, 80,
                PreExperimentSimulation.Settings.defaults().temperature(), 12_345L);
        long hash = 31L;
        for (ExperimentalCondition condition : List.of(ExperimentalCondition.ADAPTIVE,
                ExperimentalCondition.STATE_ONLY, ExperimentalCondition.STATIC)) {
            PreExperimentSimulation.Result result = PreExperimentSimulation.run(condition, settings);
            for (BehaviorType behavior : BehaviorType.ALL) {
                hash = mix(hash, Double.doubleToLongBits(result.companionship().get(behavior)));
                hash = mix(hash, Double.doubleToLongBits(result.solo().get(behavior)));
            }
            hash = mix(hash, Double.doubleToLongBits(result.withinDistance()));
            hash = mix(hash, Double.doubleToLongBits(result.betweenDistance()));
        }
        return hash;
    }

    @Test
    @DisplayName("スコア・確率・選択結果は分解導入前とビット単位で一致する")
    void scoresAreBitIdentical() {
        assertEquals(EXPECTED_SCORE_FINGERPRINT, scoreFingerprint());
    }

    @Test
    @DisplayName("実験前シミュレーションの結果は分解導入前とビット単位で一致する")
    void simulationIsBitIdentical() {
        assertEquals(EXPECTED_SIMULATION_FINGERPRINT, simulationFingerprint());
    }
}
