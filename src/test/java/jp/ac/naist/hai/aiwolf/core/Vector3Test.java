package jp.ac.naist.hai.aiwolf.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link Vector3} の単体テスト。 */
class Vector3Test {

    private static final double EPS = 1.0e-9;

    @Test
    @DisplayName("水平正規化は長さ 1 になり Y を落とす")
    void horizontalNormalizedDropsY() {
        Vector3 normalized = new Vector3(3.0, 99.0, 4.0).horizontalNormalized();
        assertEquals(0.6, normalized.x(), EPS);
        assertEquals(0.0, normalized.y(), EPS);
        assertEquals(0.8, normalized.z(), EPS);
        assertEquals(1.0, normalized.horizontalLength(), EPS);
    }

    @Test
    @DisplayName("長さ 0 のベクトルを正規化しても NaN にならない")
    void zeroLengthNormalisationDoesNotProduceNaN() {
        Vector3 normalized = new Vector3(0.0, 5.0, 0.0).horizontalNormalized();
        assertEquals(Vector3.ZERO, normalized);
        assertTrue(normalized.isFinite());
    }

    @Test
    @DisplayName("Y 軸回転は長さを保つ")
    void rotationPreservesLength() {
        Vector3 rotated = new Vector3(1.0, 0.0, 0.0).rotatedAroundY(Math.PI / 2.0);
        assertEquals(0.0, rotated.x(), 1.0e-12);
        assertEquals(1.0, rotated.z(), 1.0e-12);
        assertEquals(1.0, rotated.horizontalLength(), 1.0e-12);
    }

    @Test
    @DisplayName("差と和とスカラ倍が期待どおり")
    void basicArithmetic() {
        Vector3 a = new Vector3(1.0, 2.0, 3.0);
        Vector3 b = new Vector3(0.5, 0.5, 0.5);
        assertEquals(new Vector3(0.5, 1.5, 2.5), a.minus(b));
        assertEquals(new Vector3(1.5, 2.5, 3.5), a.plus(b));
        assertEquals(new Vector3(2.0, 4.0, 6.0), a.scale(2.0));
    }
}
