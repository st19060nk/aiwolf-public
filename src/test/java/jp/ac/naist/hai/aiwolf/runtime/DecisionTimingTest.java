package jp.ac.naist.hai.aiwolf.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 決定タイミングのテスト。
 *
 * <p>Phase 13 までは「直近の決定記録が手元にあるか」で即時決定を判断していた。
 * この記録はセーブされない一時オブジェクトなので、復元した個体はロード直後に
 * 必ず 1 回余分に決定していた。余分な決定は decisionIndex を 1 つ進め、
 * 以降の乱数列と行動列を継続実行とずらす。再現性の前提が再起動で崩れる。</p>
 */
final class DecisionTimingTest {

    private static final int INTERVAL = AiWolfParameters.DECISION_INTERVAL_TICKS;
    private static final int MIN_DURATION = AiWolfParameters.MINIMUM_BEHAVIOR_DURATION_TICKS;

    @Test
    @DisplayName("まだ行動を決めていない個体は即座に決定する")
    void freshWolfDecidesImmediately() {
        assertTrue(DecisionTiming.shouldDecide(false, false,
                Long.MIN_VALUE, Long.MIN_VALUE, 1000L));
    }

    @Test
    @DisplayName("復元した個体はロード直後に余分な決定をしない")
    void restoredWolfDoesNotDecideOnLoad() {
        long savedAt = 10_000L;

        assertFalse(DecisionTiming.shouldDecide(true, false, savedAt, savedAt, savedAt + 1L),
                "ロード直後の 1 回が入ると decisionIndex が継続実行とずれる");
        assertFalse(DecisionTiming.shouldDecide(true, false, savedAt, savedAt, savedAt + 5L));
    }

    @Test
    @DisplayName("復元後も通常の決定間隔で次の決定が来る")
    void restoredWolfFollowsTheNormalInterval() {
        long savedAt = 10_000L;

        assertFalse(DecisionTiming.shouldDecide(true, false, savedAt, savedAt,
                savedAt + MIN_DURATION - 1));
        assertTrue(DecisionTiming.shouldDecide(true, false, savedAt, savedAt,
                savedAt + MIN_DURATION));
    }

    @Test
    @DisplayName("最小継続時間の中では再決定しない")
    void behaviourIsHeldForTheMinimumDuration() {
        long start = 5_000L;

        assertFalse(DecisionTiming.shouldDecide(true, false, start, start, start + INTERVAL));
        assertTrue(DecisionTiming.shouldDecide(true, false, start, start, start + MIN_DURATION));
    }

    @Test
    @DisplayName("緊急時は最小継続時間を無視して即時に決定する")
    void emergencyOverridesTheMinimumDuration() {
        long start = 5_000L;

        assertTrue(DecisionTiming.shouldDecide(true, true, start, start, start + 1L));
    }

    @Test
    @DisplayName("タイミングが欠けた旧形式のセーブではその場で決め直す")
    void legacySaveWithoutTimingDecidesOnce() {
        assertTrue(DecisionTiming.shouldDecide(true, false, Long.MIN_VALUE, Long.MIN_VALUE, 1000L));
        assertTrue(DecisionTiming.shouldDecide(true, false, 900L, Long.MIN_VALUE, 1000L));
    }

    @Test
    @DisplayName("ワールド時刻が巻き戻った場合は基準を取り直す")
    void rewoundWorldTimeResetsTheBaseline() {
        assertTrue(DecisionTiming.shouldDecide(true, false, 10_000L, 10_000L, 500L));
    }

    @Test
    @DisplayName("経過 tick は初回と巻き戻しで 0 になる")
    void elapsedIsNeverNegative() {
        assertEquals(0L, DecisionTiming.elapsedSince(Long.MIN_VALUE, 1000L));
        assertEquals(0L, DecisionTiming.elapsedSince(10_000L, 500L));
        assertEquals(40L, DecisionTiming.elapsedSince(1000L, 1040L));
    }
}
