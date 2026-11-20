package jp.ac.naist.hai.aiwolf.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;

/** 空間記憶（{@link SpatialRegion} / {@link SpatialMemory}）の単体テスト。 */
class SpatialMemoryTest {

    private static final String DIM = "minecraft:overworld";

    private static WolfEvent eventAt(WolfEventType type, double x, long worldTime) {
        return WolfEvent.of(type, worldTime, DIM, "minecraft:plains", x, 64.0, 0.0, "subject-1", 0.5, 1.0);
    }

    @Test
    @DisplayName("座標は region サイズ単位へ量子化される")
    void coordinatesAreQuantised() {
        SpatialRegion a = SpatialRegion.of(DIM, 0.0, 64.0, 0.0, 24);
        SpatialRegion b = SpatialRegion.of(DIM, 23.9, 70.0, 10.0, 24);
        SpatialRegion c = SpatialRegion.of(DIM, 24.0, 64.0, 0.0, 24);
        assertEquals(a, b, "同じ region 内の座標は同一視される");
        assertTrue(!a.equals(c), "region をまたぐと別扱いになる");
    }

    @Test
    @DisplayName("負の座標でも正しく量子化される")
    void negativeCoordinatesQuantiseCorrectly() {
        assertEquals(-1, SpatialRegion.of(DIM, -1.0, 64.0, 0.0, 24).regionX());
        assertEquals(-1, SpatialRegion.of(DIM, -24.0, 64.0, 0.0, 24).regionX());
        assertEquals(-2, SpatialRegion.of(DIM, -25.0, 64.0, 0.0, 24).regionX());
    }

    @Test
    @DisplayName("ディメンションが違えば別 region として扱われる")
    void dimensionsAreDistinct() {
        assertTrue(!SpatialRegion.of(DIM, 0, 64, 0).equals(
                SpatialRegion.of("minecraft:the_nether", 0, 64, 0)));
    }

    @Test
    @DisplayName("未知の region は恐怖値 0 を返す")
    void unknownRegionHasNoFear() {
        assertEquals(0.0, new SpatialMemory().fearAt(SpatialRegion.of(DIM, 0, 64, 0)), 0.0);
        assertEquals(0.0, new SpatialMemory().fearAt(null), 0.0);
    }

    @Test
    @DisplayName("危険な出来事を繰り返すと region の恐怖値が上がる")
    void dangerousEventsRaiseRegionFear() {
        SpatialMemory memory = new SpatialMemory();
        SpatialRegion region = SpatialRegion.of(DIM, 0.0, 64.0, 0.0);

        double before = memory.fearAt(region);
        for (int i = 0; i < 5; i++) {
            memory.recordEvent(eventAt(WolfEventType.NEAR_DEATH, 0.0, 1000L * i));
        }
        double after = memory.fearAt(region);

        assertTrue(after > before);
        assertTrue(after > 0.5, "繰り返し瀕死になった場所は強く恐れられるはず: " + after);
    }

    @Test
    @DisplayName("1 回の出来事で region の評価が振り切れない")
    void singleEventDoesNotSaturateRegion() {
        SpatialMemory memory = new SpatialMemory();
        memory.recordEvent(eventAt(WolfEventType.NEAR_DEATH, 0.0, 0L));
        assertTrue(memory.fearAt(SpatialRegion.of(DIM, 0.0, 64.0, 0.0)) < 0.5);
    }

    @Test
    @DisplayName("良い経験は恐怖を打ち消す")
    void positiveExperienceOffsetsFear() {
        SpatialMemory memory = new SpatialMemory();
        SpatialRegion region = SpatialRegion.of(DIM, 0.0, 64.0, 0.0);
        for (int i = 0; i < 5; i++) {
            memory.recordEvent(eventAt(WolfEventType.WOLF_DAMAGED, 0.0, 1000L * i));
        }
        double fearOnly = memory.fearAt(region);
        for (int i = 0; i < 10; i++) {
            memory.recordEvent(eventAt(WolfEventType.PLAYER_PROTECTED_WOLF, 0.0, 6000L + 1000L * i));
        }
        assertTrue(memory.fearAt(region) < fearOnly, "助けられた経験で恐怖が和らぐはず");
    }

    @Test
    @DisplayName("region 数が上限を超えたら重要度の低い region から削除される")
    void regionsArePrunedWhenOverCapacity() {
        SpatialMemory memory = new SpatialMemory(4);
        SpatialRegion dangerous = SpatialRegion.of(DIM, 0.0, 64.0, 0.0);
        for (int i = 0; i < 6; i++) {
            memory.recordEvent(eventAt(WolfEventType.NEAR_DEATH, 0.0, 100L * i));
        }
        for (int i = 1; i <= 10; i++) {
            memory.recordVisit(SpatialRegion.of(DIM, i * 100.0, 64.0, 0.0), 5000L + i);
        }
        assertEquals(4, memory.size());
        assertTrue(memory.fearAt(dangerous) > 0.0, "強い恐怖記憶は剪定で消えてはならない");
    }
}
