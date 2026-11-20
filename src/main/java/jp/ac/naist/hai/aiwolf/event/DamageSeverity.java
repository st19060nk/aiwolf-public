package jp.ac.naist.hai.aiwolf.event;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;

/**
 * 被弾をどの出来事として記録するかの分類。Minecraft に依存しない。
 *
 * <p>1 回の被弾からは<strong>必ず 1 種類だけ</strong>を返す。瀕死・低 HP・通常の被弾を
 * 同時に記録すると、1 回の経験が state・記憶・選好の 3 経路へ何重にも入る。</p>
 *
 * <p>探索中の被弾を別種別にしているのは、「単独で出歩くと痛い目に遭う」という
 * 経験と「飼い主のそばで戦って傷ついた」経験が、行動選好へ逆向きに効くためである。</p>
 */
public final class DamageSeverity {

    private DamageSeverity() {
        throw new AssertionError("utility class");
    }

    /**
     * 被弾後の状態から出来事の種別を決める。
     *
     * @param healthRatioAfter 被弾後の HP 比率 [0,1]
     * @param currentBehavior  被弾時に実行していた行動（{@code null} 可）
     * @return 記録すべき出来事の種別
     */
    public static WolfEventType classify(double healthRatioAfter, BehaviorType currentBehavior) {
        if (healthRatioAfter < AiWolfParameters.EMERGENCY_HEALTH_THRESHOLD) {
            return WolfEventType.NEAR_DEATH;
        }
        if (healthRatioAfter < AiWolfParameters.LOW_HEALTH_THRESHOLD) {
            return WolfEventType.WOLF_LOW_HEALTH;
        }
        if (currentBehavior == BehaviorType.EXPLORE_NEARBY) {
            return WolfEventType.WOLF_DAMAGED_DURING_EXPLORATION;
        }
        return WolfEventType.WOLF_DAMAGED;
    }
}
