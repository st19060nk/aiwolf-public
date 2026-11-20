package jp.ac.naist.hai.aiwolf.study;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 25 P0-4：プレイヤーのサンプルの移動量が、セッションをまたいで汚染されないことの検証。 */
final class MotionBaselineTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 15, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final Object OVERWORLD = new Object();
    private static final Object NETHER = new Object();
    private static final String WOLF = "wolf";
    private static final String OWNER = "owner";

    private final MotionBaseline<Double> baseline = new MotionBaseline<>();
    private final Runnable clear = baseline::clear;

    @AfterEach
    void clean() {
        StudySession.removeBoundaryListener(clear);
        StudySession.reset();
    }

    @Test
    @DisplayName("同じセッションの 1 秒後のサンプルだけが前回を基準にする。最初のサンプルは基準なし")
    void baselineWithinASession() {
        MotionBaseline.Result<Double> first = baseline.observe(WOLF, "s1", 100L, OVERWORLD, OWNER, 1.0);
        assertFalse(first.hasBaseline());
        assertEquals(MotionBaseline.Missing.FIRST_SAMPLE, first.missing());
        MotionBaseline.Result<Double> second = baseline.observe(WOLF, "s1", 120L, OVERWORLD, OWNER, 2.0);
        assertTrue(second.hasBaseline());
        assertEquals(1.0, second.previous());
        assertEquals(20L, second.intervalTicks());
    }

    @Test
    @DisplayName("停止から 1 秒以内に次のセッションを始めても、前のセッションの位置を基準にしない")
    void noCrossSessionContamination() {
        baseline.observe(WOLF, "s1", 100L, OVERWORLD, OWNER, 1.0);
        MotionBaseline.Result<Double> next = baseline.observe(WOLF, "s2", 120L, OVERWORLD, OWNER, 50.0);
        assertFalse(next.hasBaseline());
        assertEquals(MotionBaseline.Missing.NEW_SESSION, next.missing());
        assertNull(next.previous());
    }

    @Test
    @DisplayName("セッションの開始・終了・リセットで前回位置が消える（StudySession の境界の通知）")
    void clearedOnSessionStartAndStop() {
        StudySession.addBoundaryListener(clear);
        baseline.observe(WOLF, "before", 0L, OVERWORLD, OWNER, 1.0);
        assertEquals(1, baseline.size());
        StudySession.start("p001-s01", 0L, 0L, NOW);
        assertEquals(0, baseline.size(), "開始で消える");

        baseline.observe(WOLF, StudySession.current().sessionId(), 20L, OVERWORLD, OWNER, 1.0);
        StudySession.stop();
        assertEquals(0, baseline.size(), "終了で消える");

        baseline.observe(WOLF, "x", 40L, OVERWORLD, OWNER, 1.0);
        StudySession.reset();
        assertEquals(0, baseline.size(), "リセット（サーバの起動・停止、ワールドの切り替え）で消える");

        AtomicInteger calls = new AtomicInteger();
        Runnable counter = calls::incrementAndGet;
        StudySession.addBoundaryListener(counter);
        StudySession.addBoundaryListener(counter);
        StudySession.reset();
        StudySession.removeBoundaryListener(counter);
        assertEquals(1, calls.get(), "同じ処理は二重に登録しない");
    }

    @Test
    @DisplayName("長い空白・時刻の逆行・ワールドの移動では移動量を計算しない。飼い主の切断で消える")
    void gapsAndWorldChanges() {
        baseline.observe(WOLF, "s1", 100L, OVERWORLD, OWNER, 1.0);
        long gap = 100L + MotionBaseline.MAX_BASELINE_GAP_TICKS + 1;
        assertEquals(MotionBaseline.Missing.GAP, baseline.observe(WOLF, "s1", gap, OVERWORLD, OWNER, 2.0).missing());
        assertEquals(MotionBaseline.Missing.GAP, baseline.observe(WOLF, "s1", gap - 5, OVERWORLD, OWNER, 2.0).missing());
        assertEquals(MotionBaseline.Missing.WORLD_CHANGED,
                baseline.observe(WOLF, "s1", gap + 15, NETHER, OWNER, 2.0).missing());
        assertTrue(baseline.observe(WOLF, "s1", gap + 35, NETHER, OWNER, 2.0).hasBaseline());

        baseline.observe("other-wolf", "s1", gap + 35, NETHER, "other-owner", 1.0);
        baseline.forgetOwner(OWNER);
        assertEquals(1, baseline.size(), "切断した飼い主のオオカミだけ消える");
        assertEquals(MotionBaseline.Missing.FIRST_SAMPLE,
                baseline.observe(WOLF, "s1", gap + 55, NETHER, OWNER, 2.0).missing());
        assertEquals(3L * AiWolfParameters.PLAYER_SAMPLE_INTERVAL_TICKS, MotionBaseline.MAX_BASELINE_GAP_TICKS);
    }

    @Test
    @DisplayName("基準のないサンプルは移動量・速さを書かず、hasBaseline=false と理由を書く")
    void firstSampleStatesNoBaseline() {
        PlayerLogJson.Subjects subjects = new PlayerLogJson.Subjects("subject-0123456789ab", "wolf-ba9876543210",
                ExperimentalCondition.ADAPTIVE);
        PlayerLogJson.Relation relation = new PlayerLogJson.Relation(2.0, 1.0, 1.0, 0, false);
        String none = PlayerLogJson.sample(subjects, null, 0L, 0L, relation,
                PlayerLogJson.Motion.none(MotionBaseline.Missing.NEW_SESSION), null, null);
        assertTrue(none.contains("\"hasBaseline\":false"), none);
        assertTrue(none.contains("\"noBaselineReason\":\"NEW_SESSION\""), none);
        for (String absent : new String[] {"playerDistanceMoved", "wolfDistanceMoved", "playerSpeed", "wolfSpeed",
                "playerMoving", "wolfMoving", "intervalTicks"}) {
            assertFalse(none.contains(absent), absent);
        }
        String with = PlayerLogJson.sample(subjects, null, 20L, 0L, relation,
                new PlayerLogJson.Motion(20L, 1.0, 2.0), null, null);
        assertTrue(with.contains("\"hasBaseline\":true"), with);
        assertFalse(with.contains("noBaselineReason"));
        assertTrue(with.contains("\"playerLogSchemaVersion\":2"));
    }
}
