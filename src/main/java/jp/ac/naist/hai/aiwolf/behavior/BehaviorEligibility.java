package jp.ac.naist.hai.aiwolf.behavior;

import java.util.EnumSet;
import java.util.Set;

import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;

/**
 * 実行可能行動集合 A_available の判定。
 *
 * <p>そもそも実行できない行動を Softmax に含めると、確率質量が無駄になり
 * 行動分布の解釈も歪む。たとえば敵がいないのに DEFEND_PLAYER を候補に入れてはならない。</p>
 *
 * <p>Minecraft に依存しないため、判定規則そのものを単体テストで固定できる。</p>
 */
public final class BehaviorEligibility {

    private BehaviorEligibility() {
        throw new AssertionError("utility class");
    }

    /**
     * 現在文脈で実行可能な行動集合を返す。
     *
     * <p>{@link BehaviorType#EXPLORE_NEARBY} と {@link BehaviorType#RETREAT} は常に
     * 実行可能なため、戻り値が空になることはない。</p>
     *
     * @param context 文脈スナップショット
     * @return 実行可能行動集合（空にならない）
     */
    public static Set<BehaviorType> evaluate(WolfContextSnapshot context) {
        Set<BehaviorType> available = EnumSet.noneOf(BehaviorType.class);

        // 飼い主の有無に依存しない行動。
        available.add(BehaviorType.EXPLORE_NEARBY);
        available.add(BehaviorType.RETREAT);

        if (context.ownerPresent()) {
            available.add(BehaviorType.FOLLOW_CLOSE);
            available.add(BehaviorType.FOLLOW_NORMAL);
            available.add(BehaviorType.APPROACH_PLAYER);
            available.add(BehaviorType.REST_NEAR_PLAYER);

            boolean threatPresent = context.hostileMobCount() > 0
                    || context.ownerUnderAttack()
                    || context.wolfUnderAttack();
            if (threatPresent) {
                available.add(BehaviorType.DEFEND_PLAYER);
            }
        }

        if (context.regionFear() >= AiWolfParameters.DANGEROUS_REGION_THRESHOLD) {
            available.add(BehaviorType.AVOID_LOCATION);
        }

        return available;
    }
}
