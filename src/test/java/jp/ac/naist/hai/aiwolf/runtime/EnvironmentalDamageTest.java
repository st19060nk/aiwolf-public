package jp.ac.naist.hai.aiwolf.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.event.DamageAttribution;
import jp.ac.naist.hai.aiwolf.event.DamageSeverity;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 加害者のいないダメージの扱いのテスト。
 *
 * <p>落下・溶岩・炎・窒息では {@code DamageSource#getAttacker()} が {@code null} になる。
 * ここで戦闘記録を作ってしまうと、相手のいない戦闘が開き、結末が永遠に来ないまま
 * 時間切れで消える。開いた戦闘の枠を食いつぶすので、同時に進行していた本物の戦闘が
 * 追い出されることさえある。</p>
 *
 * <p>内部状態としては「傷ついた」経験なので更新する。戦闘としては数えない、
 * という区別をここで固定する。</p>
 */
final class EnvironmentalDamageTest {

    @Test
    @DisplayName("加害者がいないダメージで戦闘の相手を作らない")
    void noAttackerMeansNoCombatOpponent() {
        assertFalse(DamageAttribution.isCombatOpponent(null, false, false),
                "落下や溶岩で戦闘記録を開くと、結末が来ないまま枠を食いつぶす");
        assertFalse(DamageAttribution.isCombatOpponent(null, true, false));
    }

    @Test
    @DisplayName("加害者が null でも例外を投げずに集計できる")
    void nullSourceIsSafeToAccumulate() {
        DamageAccumulator accumulator = new DamageAccumulator();

        accumulator.add(null, 4.0, 0.6, 100L);
        accumulator.add(null, 2.0, 0.5, 120L);

        assertEquals(6.0, accumulator.pendingFrom(null), 1e-9);
        assertEquals(1, accumulator.pendingSources(), "加害者のいないダメージはひとまとめ");

        DamageAccumulator.Drained drained = accumulator.drain().orElseThrow();
        assertTrue(drained.environmental());
        assertNull(drained.hostileId());
    }

    @Test
    @DisplayName("環境ダメージでも内部状態は更新される")
    void environmentalDamageStillProducesAnEvent() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(null, 9.0, 0.35, 100L);

        DamageAccumulator.Drained drained = accumulator.drain().orElseThrow();

        assertEquals(WolfEventType.WOLF_LOW_HEALTH,
                DamageSeverity.classify(drained.lowestHealthRatio(), BehaviorType.FOLLOW_CLOSE),
                "落下で瀕死になった経験は記録されるべき。戦闘として数えないだけ");
    }

    @Test
    @DisplayName("環境ダメージと敵のダメージが同じ区間にあっても混ざらない")
    void environmentalAndHostileDamageStayApart() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add(null, 3.0, 0.8, 100L);
        accumulator.add("zombie-a", 2.0, 0.7, 110L);

        assertEquals(3.0, accumulator.pendingFrom(null), 1e-9);
        assertEquals(2.0, accumulator.pendingFrom("zombie-a"), 1e-9);
        assertEquals(2, accumulator.pendingSources());
    }

    @Test
    @DisplayName("完全に防いだ攻撃は被害として学習しない")
    void fullyBlockedDamageIsNotLearned() {
        DamageAccumulator accumulator = new DamageAccumulator();

        accumulator.add("zombie-a", 0.0, 1.0, 100L);

        assertEquals(0, accumulator.pendingSources(),
                "測るのは攻撃されたことではなく、実際に受けた被害");
        assertEquals(Optional.empty(), accumulator.drain());
    }

    @Test
    @DisplayName("防いだ攻撃のあとに通った攻撃だけが残る")
    void onlyDamageThatLandedIsKept() {
        DamageAccumulator accumulator = new DamageAccumulator();
        accumulator.add("zombie-a", 0.0, 1.0, 100L);
        accumulator.add("zombie-a", 3.0, 0.8, 120L);

        DamageAccumulator.Drained drained = accumulator.drain().orElseThrow();
        assertEquals(3.0, drained.totalDamage(), 1e-9);
        assertEquals(1, drained.totalHits());
    }
}
