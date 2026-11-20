package jp.ac.naist.hai.aiwolf.random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link WolfRandomProvider} の単体テスト。研究の再現性を担保する。 */
class WolfRandomProviderTest {

    private static final UUID WOLF_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID WOLF_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    @Test
    @DisplayName("同じ実験シードと同じ UUID なら同じ乱数列になる")
    void sameSeedAndUuidProduceSameSequence() {
        WolfRandomProvider first = WolfRandomProvider.forWolf(12345L, WOLF_A);
        WolfRandomProvider second = WolfRandomProvider.forWolf(12345L, WOLF_A);
        for (int i = 0; i < 100; i++) {
            assertEquals(first.nextDecisionRandom(), second.nextDecisionRandom(), 0.0);
        }
    }

    @Test
    @DisplayName("個体が違えば乱数列も異なる")
    void differentWolvesProduceDifferentSequences() {
        WolfRandomProvider a = WolfRandomProvider.forWolf(12345L, WOLF_A);
        WolfRandomProvider b = WolfRandomProvider.forWolf(12345L, WOLF_B);
        assertNotEquals(a.seed(), b.seed());
        int differences = 0;
        for (int i = 0; i < 20; i++) {
            if (a.nextDecisionRandom() != b.nextDecisionRandom()) {
                differences++;
            }
        }
        assertTrue(differences >= 19, "個体ごとに異なる乱数列になるべき");
    }

    @Test
    @DisplayName("decisionIndex から復元すると続きの乱数列が完全に再現される")
    void restoreFromDecisionIndexResumesExactSequence() {
        WolfRandomProvider original = WolfRandomProvider.forWolf(999L, WOLF_A);
        for (int i = 0; i < 37; i++) {
            original.nextDecisionRandom();
        }
        WolfRandomProvider restored = WolfRandomProvider.restore(original.seed(), original.decisionIndex());
        for (int i = 0; i < 50; i++) {
            assertEquals(original.nextDecisionRandom(), restored.nextDecisionRandom(), 0.0);
        }
    }

    @Test
    @DisplayName("乱数値は [0,1) に収まり、decisionIndex が単調増加する")
    void randomValuesAreInUnitIntervalAndIndexAdvances() {
        WolfRandomProvider provider = WolfRandomProvider.forWolf(7L, WOLF_A);
        assertEquals(0L, provider.decisionIndex());
        for (int i = 0; i < 1000; i++) {
            double value = provider.nextDecisionRandom();
            assertTrue(value >= 0.0 && value < 1.0, "value out of range: " + value);
            assertEquals(i + 1L, provider.decisionIndex());
        }
    }

    @Test
    @DisplayName("randomValueFor は純粋関数であり、ログから後日再計算できる")
    void randomValueForIsPure() {
        WolfRandomProvider provider = WolfRandomProvider.forWolf(42L, WOLF_A);
        long seed = provider.seed();
        long index = provider.decisionIndex();
        double sampled = provider.nextDecisionRandom();
        assertEquals(sampled, WolfRandomProvider.randomValueFor(seed, index), 0.0);
    }

    @Test
    @DisplayName("peek は decisionIndex を進めない")
    void peekDoesNotAdvanceIndex() {
        WolfRandomProvider provider = WolfRandomProvider.forWolf(1L, WOLF_A);
        double peeked = provider.peekNextRandom();
        assertEquals(0L, provider.decisionIndex());
        assertEquals(peeked, provider.nextDecisionRandom(), 0.0);
    }

    @Test
    @DisplayName("乱数列はおおむね一様に分布する")
    void sequenceIsApproximatelyUniform() {
        WolfRandomProvider provider = WolfRandomProvider.forWolf(2026L, WOLF_A);
        int[] buckets = new int[10];
        int samples = 100_000;
        for (int i = 0; i < samples; i++) {
            buckets[(int) (provider.nextDecisionRandom() * 10)]++;
        }
        for (int count : buckets) {
            double ratio = (double) count / samples;
            assertTrue(ratio > 0.085 && ratio < 0.115, "bucket ratio out of range: " + ratio);
        }
    }
}
