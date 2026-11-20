package jp.ac.naist.hai.aiwolf.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.random.WolfRandomProvider;

/** {@link PolicySelector} の単体テスト。再現性と確率的選択を検証する。 */
class PolicySelectorTest {

    private static final UUID WOLF = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

    private static Map<BehaviorType, BehaviorScore> scores(Map<BehaviorType, Double> finalScores) {
        Map<BehaviorType, BehaviorScore> map = new EnumMap<>(BehaviorType.class);
        finalScores.forEach((behavior, value) ->
                map.put(behavior, BehaviorScore.of(behavior, value, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)));
        return Collections.unmodifiableMap(map);
    }

    private static Map<BehaviorType, BehaviorScore> sampleScores() {
        Map<BehaviorType, Double> raw = new EnumMap<>(BehaviorType.class);
        raw.put(BehaviorType.FOLLOW_CLOSE, 2.1);
        raw.put(BehaviorType.EXPLORE_NEARBY, 1.8);
        raw.put(BehaviorType.APPROACH_PLAYER, 1.5);
        raw.put(BehaviorType.RETREAT, 0.8);
        return scores(raw);
    }

    @Test
    @DisplayName("確率は合計 1 になり、スコア順と一致する")
    void probabilitiesAreNormalisedAndOrdered() {
        BehaviorDecision decision = new PolicySelector(0.6)
                .select(sampleScores(), WolfRandomProvider.forWolf(1L, WOLF));

        double sum = decision.probabilities().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 1.0e-12);
        assertTrue(decision.probabilities().get(BehaviorType.FOLLOW_CLOSE)
                > decision.probabilities().get(BehaviorType.EXPLORE_NEARBY));
        assertTrue(decision.probabilities().get(BehaviorType.APPROACH_PLAYER)
                > decision.probabilities().get(BehaviorType.RETREAT));
    }

    @Test
    @DisplayName("同じシードなら行動列が完全に再現される")
    void sameSeedReproducesIdenticalBehaviourSequence() {
        PolicySelector selector = new PolicySelector(0.6);
        List<BehaviorType> first = new ArrayList<>();
        List<BehaviorType> second = new ArrayList<>();

        WolfRandomProvider randomA = WolfRandomProvider.forWolf(2026L, WOLF);
        WolfRandomProvider randomB = WolfRandomProvider.forWolf(2026L, WOLF);
        for (int i = 0; i < 200; i++) {
            first.add(selector.select(sampleScores(), randomA).selected());
            second.add(selector.select(sampleScores(), randomB).selected());
        }
        assertEquals(first, second);
    }

    @Test
    @DisplayName("決定インデックスが記録され、1 回の決定で 1 つ進む")
    void decisionIndexIsRecordedAndAdvances() {
        PolicySelector selector = new PolicySelector(0.6);
        WolfRandomProvider random = WolfRandomProvider.forWolf(5L, WOLF);
        for (long expected = 0; expected < 10; expected++) {
            BehaviorDecision decision = selector.select(sampleScores(), random);
            assertEquals(expected, decision.decisionIndex());
            assertEquals(expected + 1, random.decisionIndex());
        }
    }

    @Test
    @DisplayName("記録した seed と decisionIndex から乱数値を後日再計算できる")
    void decisionIsVerifiableFromLoggedFields() {
        WolfRandomProvider random = WolfRandomProvider.forWolf(77L, WOLF);
        long seed = random.seed();
        BehaviorDecision decision = new PolicySelector(0.6).select(sampleScores(), random);
        assertEquals(WolfRandomProvider.randomValueFor(seed, decision.decisionIndex()),
                decision.randomValue(), 0.0);
    }

    @Test
    @DisplayName("常に最大スコアだけが選ばれるわけではない（確率的選択）")
    void selectionIsStochastic() {
        PolicySelector selector = new PolicySelector(0.6);
        WolfRandomProvider random = WolfRandomProvider.forWolf(99L, WOLF);
        Map<BehaviorType, Integer> counts = new EnumMap<>(BehaviorType.class);
        for (int i = 0; i < 5000; i++) {
            counts.merge(selector.select(sampleScores(), random).selected(), 1, Integer::sum);
        }
        assertTrue(counts.size() >= 3, "複数の行動が選ばれるべき");
        assertTrue(counts.get(BehaviorType.FOLLOW_CLOSE) > counts.get(BehaviorType.RETREAT));
    }

    @Test
    @DisplayName("経験分布が理論上の Softmax 分布に一致する")
    void empiricalDistributionMatchesSoftmax() {
        PolicySelector selector = new PolicySelector(0.6);
        WolfRandomProvider random = WolfRandomProvider.forWolf(123L, WOLF);
        Map<BehaviorType, BehaviorScore> scoreMap = sampleScores();
        Map<BehaviorType, Double> expected = selector.select(scoreMap,
                WolfRandomProvider.forWolf(123L, WOLF)).probabilities();

        Map<BehaviorType, Integer> counts = new EnumMap<>(BehaviorType.class);
        int samples = 40_000;
        for (int i = 0; i < samples; i++) {
            counts.merge(selector.select(scoreMap, random).selected(), 1, Integer::sum);
        }
        for (Map.Entry<BehaviorType, Double> entry : expected.entrySet()) {
            double observed = counts.getOrDefault(entry.getKey(), 0) / (double) samples;
            assertEquals(entry.getValue(), observed, 0.02,
                    "empirical probability differs for " + entry.getKey());
        }
    }

    @Test
    @DisplayName("温度が十分低ければほぼ最大スコアの行動が選ばれる")
    void lowTemperatureApproachesArgmax() {
        PolicySelector selector = new PolicySelector(0.05);
        WolfRandomProvider random = WolfRandomProvider.forWolf(3L, WOLF);
        int followClose = 0;
        for (int i = 0; i < 500; i++) {
            if (selector.select(sampleScores(), random).selected() == BehaviorType.FOLLOW_CLOSE) {
                followClose++;
            }
        }
        assertTrue(followClose > 480, "低温ではほぼ argmax になるはず: " + followClose);
    }

    @Test
    @DisplayName("不正な温度と空の候補は拒否される")
    void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PolicySelector(0.0));
        assertThrows(IllegalArgumentException.class, () -> new PolicySelector(-1.0));
        assertThrows(IllegalArgumentException.class, () -> new PolicySelector(0.6)
                .select(Collections.emptyMap(), WolfRandomProvider.forWolf(1L, WOLF)));
    }
}
