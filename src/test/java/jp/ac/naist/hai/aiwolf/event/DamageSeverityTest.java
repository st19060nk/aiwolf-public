package jp.ac.naist.hai.aiwolf.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 被弾の分類のテスト。1 回の被弾からは必ず 1 種類だけを返すことを固定する。
 */
final class DamageSeverityTest {

    @Test
    @DisplayName("緊急閾値を下回ると瀕死として記録する")
    void lowestHealthIsNearDeath() {
        assertEquals(WolfEventType.NEAR_DEATH, DamageSeverity.classify(
                AiWolfParameters.EMERGENCY_HEALTH_THRESHOLD - 0.01, null));
        assertEquals(WolfEventType.NEAR_DEATH, DamageSeverity.classify(0.0, BehaviorType.EXPLORE_NEARBY));
    }

    @Test
    @DisplayName("低 HP 閾値を下回ると低 HP として記録する")
    void lowHealthIsItsOwnEvent() {
        assertEquals(WolfEventType.WOLF_LOW_HEALTH, DamageSeverity.classify(
                AiWolfParameters.LOW_HEALTH_THRESHOLD - 0.01, null));
    }

    @Test
    @DisplayName("探索中の被弾は別種別になる")
    void damageWhileExploringIsDistinct() {
        assertEquals(WolfEventType.WOLF_DAMAGED_DURING_EXPLORATION,
                DamageSeverity.classify(0.9, BehaviorType.EXPLORE_NEARBY),
                "単独行動の痛みと、飼い主のそばでの戦闘の痛みは選好へ逆向きに効く");
    }

    @Test
    @DisplayName("それ以外は通常の被弾")
    void otherwiseItIsOrdinaryDamage() {
        assertEquals(WolfEventType.WOLF_DAMAGED, DamageSeverity.classify(0.9, null));
        assertEquals(WolfEventType.WOLF_DAMAGED,
                DamageSeverity.classify(0.9, BehaviorType.FOLLOW_CLOSE));
    }

    @Test
    @DisplayName("閾値ちょうどは重い側に入らない")
    void thresholdsAreExclusive() {
        assertEquals(WolfEventType.WOLF_LOW_HEALTH,
                DamageSeverity.classify(AiWolfParameters.EMERGENCY_HEALTH_THRESHOLD, null));
        assertEquals(WolfEventType.WOLF_DAMAGED,
                DamageSeverity.classify(AiWolfParameters.LOW_HEALTH_THRESHOLD, null));
    }
}
