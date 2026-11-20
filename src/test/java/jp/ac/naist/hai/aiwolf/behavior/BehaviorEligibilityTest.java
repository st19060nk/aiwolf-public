package jp.ac.naist.hai.aiwolf.behavior;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;

/** {@link BehaviorEligibility} の単体テスト。 */
class BehaviorEligibilityTest {

    @Test
    @DisplayName("敵がいなければ DEFEND_PLAYER は候補に入らない")
    void defendRequiresThreat() {
        Set<BehaviorType> available = BehaviorEligibility.evaluate(
                WolfContextSnapshot.builder().build());
        assertFalse(available.contains(BehaviorType.DEFEND_PLAYER));
    }

    @Test
    @DisplayName("敵がいれば DEFEND_PLAYER が候補に入る")
    void defendBecomesAvailableWithThreat() {
        Set<BehaviorType> available = BehaviorEligibility.evaluate(
                WolfContextSnapshot.builder()
                        .hostileMobCount(2)
                        .nearestHostileDistance(5.0)
                        .build());
        assertTrue(available.contains(BehaviorType.DEFEND_PLAYER));
    }

    @Test
    @DisplayName("危険 region でなければ AVOID_LOCATION は候補に入らない")
    void avoidRequiresDangerousRegion() {
        Set<BehaviorType> safe = BehaviorEligibility.evaluate(
                WolfContextSnapshot.builder().regionFear(0.1).build());
        assertFalse(safe.contains(BehaviorType.AVOID_LOCATION));

        Set<BehaviorType> dangerous = BehaviorEligibility.evaluate(
                WolfContextSnapshot.builder().regionFear(0.6).build());
        assertTrue(dangerous.contains(BehaviorType.AVOID_LOCATION));
    }

    @Test
    @DisplayName("飼い主が不在なら飼い主依存行動は候補に入らない")
    void ownerDependentBehavioursRequireOwner() {
        Set<BehaviorType> available = BehaviorEligibility.evaluate(
                WolfContextSnapshot.builder().ownerPresent(false).build());
        assertFalse(available.contains(BehaviorType.FOLLOW_CLOSE));
        assertFalse(available.contains(BehaviorType.FOLLOW_NORMAL));
        assertFalse(available.contains(BehaviorType.APPROACH_PLAYER));
        assertFalse(available.contains(BehaviorType.REST_NEAR_PLAYER));
        assertFalse(available.contains(BehaviorType.DEFEND_PLAYER));
    }

    @Test
    @DisplayName("実行可能集合は決して空にならない")
    void availableSetIsNeverEmpty() {
        Set<BehaviorType> available = BehaviorEligibility.evaluate(
                WolfContextSnapshot.builder()
                        .ownerPresent(false)
                        .regionFear(0.0)
                        .build());
        assertFalse(available.isEmpty());
        assertTrue(available.contains(BehaviorType.EXPLORE_NEARBY));
        assertTrue(available.contains(BehaviorType.RETREAT));
    }
}
