package jp.ac.naist.hai.aiwolf.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.Vector3;

/** {@link AvoidanceGeometry} の単体テスト。回避が本当に恐怖領域から離れるかを固定する。 */
class AvoidanceGeometryTest {

    private static final String DIM = "minecraft:overworld";
    private static final double EPS = 1.0e-9;

    @Test
    @DisplayName("region の中心は量子化単位の中央になる")
    void regionCentreIsAtTheMiddleOfTheCell() {
        SpatialRegion region = new SpatialRegion(DIM, 0, 2, -1);
        Vector3 center = AvoidanceGeometry.regionCenter(region, 24);
        assertEquals(12.0, center.x(), EPS);
        assertEquals(60.0, center.y(), EPS);
        assertEquals(-12.0, center.z(), EPS);
    }

    @Test
    @DisplayName("脱出方向は恐怖領域の中心から離れる向きになる")
    void escapeDirectionPointsAwayFromTheCentre() {
        Vector3 center = new Vector3(0.0, 64.0, 0.0);
        Vector3 wolf = new Vector3(10.0, 64.0, 0.0);
        Vector3 direction = AvoidanceGeometry.escapeDirection(wolf, center);
        assertEquals(1.0, direction.x(), EPS);
        assertEquals(0.0, direction.z(), EPS);
    }

    @Test
    @DisplayName("同じ位置でも恐怖領域が違えば脱出方向が変わる")
    void differentFearedRegionsGiveDifferentDirections() {
        Vector3 wolf = new Vector3(0.0, 64.0, 0.0);
        Vector3 eastward = AvoidanceGeometry.escapeDirection(wolf, new Vector3(-10.0, 64.0, 0.0));
        Vector3 northward = AvoidanceGeometry.escapeDirection(wolf, new Vector3(0.0, 64.0, 10.0));
        assertEquals(1.0, eastward.x(), EPS);
        assertEquals(-1.0, northward.z(), EPS);
        assertFalse(eastward.equals(northward));
    }

    @Test
    @DisplayName("中心の真上にいても NaN にならず有限の方向を返す")
    void standingOnTheCentreStillGivesAFiniteDirection() {
        Vector3 center = new Vector3(12.0, 64.0, 12.0);
        Vector3 direction = AvoidanceGeometry.escapeDirection(center, center);
        assertTrue(direction.isFinite(), "NaN を navigation へ渡すと挙動が壊れる");
        assertEquals(1.0, direction.horizontalLength(), EPS);
    }

    @Test
    @DisplayName("候補はすべて有限で、先頭は中心から遠ざかる")
    void candidatesAreFiniteAndLeadAway() {
        Vector3 center = new Vector3(0.0, 64.0, 0.0);
        Vector3 wolf = new Vector3(5.0, 64.0, 0.0);
        List<Vector3> candidates = AvoidanceGeometry.escapeCandidates(wolf, center, 18.0);

        assertFalse(candidates.isEmpty());
        for (Vector3 candidate : candidates) {
            assertTrue(candidate.isFinite());
        }
        assertTrue(AvoidanceGeometry.movesAwayFrom(wolf, center, candidates.get(0)));
    }

    @Test
    @DisplayName("中心へ近づく候補は却下できる")
    void movesAwayRejectsCandidatesTowardsTheCentre() {
        Vector3 center = new Vector3(0.0, 64.0, 0.0);
        Vector3 wolf = new Vector3(10.0, 64.0, 0.0);
        assertFalse(AvoidanceGeometry.movesAwayFrom(wolf, center, new Vector3(2.0, 64.0, 0.0)));
        assertTrue(AvoidanceGeometry.movesAwayFrom(wolf, center, new Vector3(25.0, 64.0, 0.0)));
    }

    @Test
    @DisplayName("既定の region サイズでも中心が計算できる")
    void defaultRegionSizeWorks() {
        Vector3 center = AvoidanceGeometry.regionCenter(new SpatialRegion(DIM, 1, 0, 0));
        assertEquals(AiWolfParameters.SPATIAL_REGION_SIZE * 1.5, center.x(), EPS);
    }
}
