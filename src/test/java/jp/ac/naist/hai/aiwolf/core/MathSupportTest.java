package jp.ac.naist.hai.aiwolf.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link MathSupport} の単体テスト。Minecraft に依存しない。 */
class MathSupportTest {

    private static final double EPS = 1.0e-9;

    @Test
    @DisplayName("clamp は範囲外の値を境界へ丸める")
    void clampBoundsValues() {
        assertEquals(0.0, MathSupport.clamp01(-3.0), EPS);
        assertEquals(1.0, MathSupport.clamp01(42.0), EPS);
        assertEquals(0.25, MathSupport.clamp01(0.25), EPS);
    }

    @Test
    @DisplayName("clamp は NaN と無限大を拒否する")
    void clampRejectsNonFinite() {
        assertThrows(IllegalArgumentException.class, () -> MathSupport.clamp01(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> MathSupport.clamp01(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("z = 2x - 1 で 0.5 が中立になる")
    void signedRangeTreatsHalfAsNeutral() {
        assertEquals(-1.0, MathSupport.toSignedRange(0.0), EPS);
        assertEquals(0.0, MathSupport.toSignedRange(0.5), EPS);
        assertEquals(1.0, MathSupport.toSignedRange(1.0), EPS);
        assertEquals(0.6, MathSupport.toSignedRange(0.8), EPS);
    }

    @Test
    @DisplayName("proximity は距離 0 で 1、距離が伸びると単調減少する")
    void proximityDecaysWithDistance() {
        assertEquals(1.0, MathSupport.proximity(0.0, 6.0), EPS);
        assertEquals(0.4346, MathSupport.proximity(5.0, 6.0), 1.0e-4);
        assertEquals(0.1889, MathSupport.proximity(10.0, 6.0), 1.0e-4);
        assertTrue(MathSupport.proximity(20.0, 6.0) < MathSupport.proximity(10.0, 6.0));
    }

    @Test
    @DisplayName("softmax は合計 1 の確率を返し、高スコアほど高確率になる")
    void softmaxProducesNormalisedProbabilities() {
        double[] probabilities = MathSupport.softmax(new double[]{2.1, 1.8, 1.5, 0.8}, 0.6);
        double sum = 0.0;
        for (double p : probabilities) {
            assertTrue(p > 0.0 && p < 1.0, "probability must be in (0,1)");
            sum += p;
        }
        assertEquals(1.0, sum, 1.0e-12);
        assertTrue(probabilities[0] > probabilities[1]);
        assertTrue(probabilities[1] > probabilities[2]);
        assertTrue(probabilities[2] > probabilities[3]);
    }

    @Test
    @DisplayName("softmax は極端に大きなスコアでもオーバーフローしない")
    void softmaxIsNumericallyStable() {
        double[] probabilities = MathSupport.softmax(new double[]{10_000.0, 9_999.0}, 0.6);
        double sum = probabilities[0] + probabilities[1];
        assertEquals(1.0, sum, 1.0e-12);
        assertTrue(Double.isFinite(probabilities[0]));
        assertTrue(probabilities[0] > probabilities[1]);
    }

    @Test
    @DisplayName("温度が低いほど最高スコアへ確率が集中する")
    void lowerTemperatureSharpensDistribution() {
        double[] scores = {2.0, 1.0};
        double[] cold = MathSupport.softmax(scores, 0.1);
        double[] warm = MathSupport.softmax(scores, 2.0);
        assertTrue(cold[0] > warm[0]);
        assertTrue(cold[0] > 0.99);
    }

    @Test
    @DisplayName("EMA は目標へ少しだけ近づく")
    void emaMovesGraduallyTowardsTarget() {
        double updated = MathSupport.exponentialMovingAverage(0.5, 1.0, 0.02);
        assertEquals(0.51, updated, EPS);
    }
}
