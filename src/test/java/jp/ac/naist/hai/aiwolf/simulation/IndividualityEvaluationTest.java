package jp.ac.naist.hai.aiwolf.simulation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.simulation.StateSaturationSimulation.Intensity;
import jp.ac.naist.hai.aiwolf.simulation.StateSaturationSimulation.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 17：個体性評価の検証。 */
final class IndividualityEvaluationTest {

    @Test
    @DisplayName("距離関数：同一なら 0、JS は分布が重ならなければ 1、コサインは向きだけを見る")
    void distanceFunctions() {
        double[] p = {0.5, 0.5, 0.0};
        double[] q = {0.0, 0.0, 1.0};
        assertEquals(0.0, IndividualityEvaluation.euclidean(p, p));
        assertEquals(Math.sqrt(0.25 + 0.25 + 1.0), IndividualityEvaluation.euclidean(p, q), 1e-12);
        assertEquals(0.0, IndividualityEvaluation.jensenShannon(p, p), 1e-12);
        assertEquals(1.0, IndividualityEvaluation.jensenShannon(p, q), 1e-12);
        assertEquals(0.0, IndividualityEvaluation.cosine(new double[] {1, 2}, new double[] {2, 4}), 1e-12);
        assertEquals(1.0, IndividualityEvaluation.cosine(new double[] {1, 0}, new double[] {0, 1}), 1e-12);
    }

    @Test
    @DisplayName("条件を変えても育成の出来事列と個体の乱数は同一（差は学習機構だけ）")
    void conditionsShareTheSameExperience() {
        Map<WolfEventType, Integer> adaptiveCounts = new EnumMap<>(WolfEventType.class);
        Map<WolfEventType, Integer> staticCounts = new EnumMap<>(WolfEventType.class);
        WolfAiAgent adaptive = StateSaturationSimulation.rear(Pattern.MIXED, Intensity.TYPICAL, 7L, 10,
                ExperimentalCondition.ADAPTIVE, adaptiveCounts, null);
        WolfAiAgent statics = StateSaturationSimulation.rear(Pattern.MIXED, Intensity.TYPICAL, 7L, 10,
                ExperimentalCondition.STATIC, staticCounts, null);
        assertEquals(adaptiveCounts, staticCounts);
        assertEquals(adaptive.wolfId(), statics.wolfId());
        assertEquals(adaptive.random().seed(), statics.random().seed());
    }

    @Test
    @DisplayName("評価は決定論的で、個体を変更しない（同じ引数なら同じ指紋）")
    void evaluationIsReproducible() {
        IndividualityEvaluation.Wolf a = IndividualityEvaluation.evaluate(ExperimentalCondition.ADAPTIVE,
                Pattern.OWNER_ORIENTED, 3L, 10, 5);
        IndividualityEvaluation.Wolf b = IndividualityEvaluation.evaluate(ExperimentalCondition.ADAPTIVE,
                Pattern.OWNER_ORIENTED, 3L, 10, 5);
        assertEquals(IndividualityEvaluation.SESSIONS, a.sessions().size());
        for (int s = 0; s < a.sessions().size(); s++) {
            assertArrayEquals(a.sessions().get(s).features(), b.sessions().get(s).features());
        }
        assertEquals(a.finalState(), b.finalState());
    }

    @Test
    @DisplayName("行動指紋：8 行動の割合の合計は 1、各指標は 0〜1")
    void signatureIsWellFormed() {
        IndividualityEvaluation.Wolf wolf = IndividualityEvaluation.evaluate(ExperimentalCondition.STATE_MEMORY,
                Pattern.DANGER_HEAVY, 11L, 15, 0);
        for (IndividualityEvaluation.Signature signature : wolf.sessions()) {
            double sum = 0.0;
            for (double rate : signature.behaviorRates()) {
                sum += rate;
            }
            assertEquals(1.0, sum, 1e-9);
            for (double value : signature.features()) {
                assertTrue(value >= 0.0 && value <= 1.0, "feature out of range: " + value);
            }
        }
        assertEquals(IndividualityEvaluation.FEATURES.size(), wolf.sessions().get(0).features().length);
    }

    @Test
    @DisplayName("標準テストの記録（trace）から作る指紋は、既存の measure と一致する")
    void traceMatchesMeasure() {
        WolfAiAgent agent = StateSaturationSimulation.rear(Pattern.OWNER_ORIENTED, Intensity.TYPICAL, 5L, 10,
                ExperimentalCondition.ADAPTIVE, new EnumMap<>(WolfEventType.class), null);
        SpatialRegion home = SpatialRegion.of("minecraft:overworld", 0.0, 64.0, 0.0);
        BehaviouralSignature signature = StandardTestBattery.measure(agent, home, "subject-saturation", 12_000L, 2L);
        List<StandardTestBattery.Step> steps = StandardTestBattery.trace(agent, home, "subject-saturation", 12_000L,
                2L, StandardTestBattery.DEFAULT_DECISIONS, AiWolfParameters.SOFTMAX_TEMPERATURE);
        for (BehaviorType behavior : BehaviorType.ALL) {
            long count = steps.stream().filter(step -> step.behavior() == behavior).count();
            assertEquals(signature.proportionOf(behavior), count / (double) steps.size(), 1e-12);
        }
        assertEquals(50, steps.stream().filter(StandardTestBattery.Step::threatWindow).count(),
                "200 決定のうち脅威区間は 50 決定");
    }

    @Test
    @DisplayName("学習しない STATIC は、間隔を置いて測ると個体差がほぼ消え、完全適応は個体差が残る")
    void staticHasNoLearnedIndividuality() {
        List<IndividualityEvaluation.Wolf> statics = new ArrayList<>();
        List<IndividualityEvaluation.Wolf> adaptive = new ArrayList<>();
        for (Pattern pattern : Pattern.values()) {
            statics.add(IndividualityEvaluation.evaluate(ExperimentalCondition.STATIC, pattern, 21L, 20, 60));
            adaptive.add(IndividualityEvaluation.evaluate(ExperimentalCondition.ADAPTIVE, pattern, 21L, 20, 60));
        }
        double staticRatio = IndividualityEvaluation.seedResult(statics).ratio(0);
        double adaptiveRatio = IndividualityEvaluation.seedResult(adaptive).ratio(0);
        assertTrue(staticRatio < 2.0, "STATIC separation ratio should be near 1 but was " + staticRatio);
        assertTrue(adaptiveRatio > 2.0 * staticRatio,
                "ADAPTIVE " + adaptiveRatio + " should clearly exceed STATIC " + staticRatio);
    }
}
