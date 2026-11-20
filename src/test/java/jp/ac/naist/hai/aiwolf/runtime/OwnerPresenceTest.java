package jp.ac.naist.hai.aiwolf.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.OwnerPresence;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 飼い主の在否判定と、そこから導かれる分離・再会のテスト。
 *
 * <p>Phase 13 までの実装は「同じワールドにいるか」だけを見ていた。その結果、
 * 数百 block 離れていても飼い主の被弾が「オオカミのそばで」として記録され、
 * 逆に長期分離はディメンションを変えない限り一度も成立しなかった。
 * 距離を含む定義を 1 か所に置いたことをここで固定する。</p>
 */
final class OwnerPresenceTest {

    private static final double RADIUS = AiWolfParameters.OWNER_PRESENCE_RADIUS;

    private static boolean nearby(double distance) {
        return OwnerPresence.isNearby(true, true, true, distance);
    }

    @Test
    @DisplayName("5 block 先の飼い主は近傍にいる")
    void closeOwnerIsPresent() {
        assertTrue(nearby(5.0));
        assertTrue(nearby(0.0));
    }

    @Test
    @DisplayName("境界ちょうどは近傍に含み、1 block 先は含まない")
    void boundaryIsInclusive() {
        assertTrue(nearby(RADIUS), "境界の扱いは 1 か所でだけ決める");
        assertFalse(nearby(RADIUS + 1.0));
    }

    @Test
    @DisplayName("遠く離れた飼い主は不在として扱う")
    void distantOwnerIsAbsent() {
        assertFalse(nearby(200.0));
        assertFalse(nearby(1000.0));
    }

    @Test
    @DisplayName("別ディメンションの飼い主は距離に関わらず不在")
    void otherDimensionIsAbsent() {
        assertFalse(OwnerPresence.isNearby(true, true, false, 1.0));
        assertFalse(OwnerPresence.isNearby(true, true, false, Double.NaN));
    }

    @Test
    @DisplayName("飼い主がいない・死亡している場合は不在")
    void missingOrDeadOwnerIsAbsent() {
        assertFalse(OwnerPresence.isNearby(false, true, true, 1.0));
        assertFalse(OwnerPresence.isNearby(true, false, true, 1.0));
    }

    @Test
    @DisplayName("距離が不明な場合は不在として扱う")
    void unknownDistanceIsAbsent() {
        assertFalse(OwnerPresence.isNearby(true, true, true, Double.NaN));
    }

    @Test
    @DisplayName("文脈生成と出来事検出が同じ半径を使う")
    void thereIsOnlyOneRadius() {
        assertEquals(48.0, AiWolfParameters.OWNER_PRESENCE_RADIUS, 0.0,
                "「近くにいる」という同じ概念に別々の閾値を作らない");
    }

    // ------------------------------------------------------------- 分離と再会

    @Test
    @DisplayName("遠く離れた状態が続くと長期分離が 1 回だけ成立する")
    void distantOwnerEventuallySeparates() {
        SeparationTracker tracker = new SeparationTracker();
        long start = 1_000L;

        assertEquals(Optional.empty(), tracker.update(false, start));
        assertEquals(Optional.empty(),
                tracker.update(false, start + AiWolfParameters.LONG_SEPARATION_TICKS - 1));
        assertEquals(Optional.of(WolfEventType.LONG_SEPARATION),
                tracker.update(false, start + AiWolfParameters.LONG_SEPARATION_TICKS));
        assertEquals(Optional.empty(),
                tracker.update(false, start + AiWolfParameters.LONG_SEPARATION_TICKS * 3),
                "離れ続けている間に何度も分離を報告してはならない");
    }

    @Test
    @DisplayName("分離のあと戻ってくると再会が 1 回だけ成立する")
    void returningAfterSeparationIsReunion() {
        SeparationTracker tracker = new SeparationTracker();
        tracker.update(false, 0L);
        tracker.update(false, AiWolfParameters.LONG_SEPARATION_TICKS);

        assertEquals(Optional.of(WolfEventType.REUNION),
                tracker.update(true, AiWolfParameters.LONG_SEPARATION_TICKS + 100L));
        assertEquals(Optional.empty(),
                tracker.update(true, AiWolfParameters.LONG_SEPARATION_TICKS + 200L),
                "そばにいる間ずっと再会を出してはならない");
    }

    @Test
    @DisplayName("分離が成立する前に戻れば再会は起きない")
    void shortAbsenceIsNotSeparation() {
        SeparationTracker tracker = new SeparationTracker();
        tracker.update(false, 0L);

        assertEquals(Optional.empty(), tracker.update(true, 100L),
                "少し離れて戻るだけで関係性が動いてはならない");
    }

    @Test
    @DisplayName("離れ直すと分離を測り直す")
    void leavingAgainStartsANewSeparation() {
        SeparationTracker tracker = new SeparationTracker();
        tracker.update(false, 0L);
        tracker.update(false, AiWolfParameters.LONG_SEPARATION_TICKS);
        tracker.update(true, AiWolfParameters.LONG_SEPARATION_TICKS + 10L);

        long second = AiWolfParameters.LONG_SEPARATION_TICKS + 20L;
        assertEquals(Optional.empty(), tracker.update(false, second));
        assertEquals(Optional.of(WolfEventType.LONG_SEPARATION),
                tracker.update(false, second + AiWolfParameters.LONG_SEPARATION_TICKS));
    }

    @Test
    @DisplayName("分離中にセーブして再開しても再会を取り逃がさない")
    void separationFlagSurvivesRestart() {
        SeparationTracker tracker = new SeparationTracker();
        tracker.update(false, 0L);
        tracker.update(false, AiWolfParameters.LONG_SEPARATION_TICKS);
        assertTrue(tracker.separationReported());

        SeparationTracker restored = new SeparationTracker();
        restored.restore(tracker.separationReported());

        assertEquals(Optional.of(WolfEventType.REUNION), restored.update(true, 99_999L));
    }

    @Test
    @DisplayName("ワールドが閉じていた時間は分離時間に数えない")
    void offlineTimeIsNotSeparationTime() {
        SeparationTracker tracker = new SeparationTracker();
        tracker.update(false, 0L);
        SeparationTracker restored = new SeparationTracker();
        restored.restore(false);

        // 復元直後はまだ不在の開始時刻を持たないので、その場で分離にはならない。
        assertEquals(Optional.empty(), restored.update(false, 10_000_000L));
    }
}
