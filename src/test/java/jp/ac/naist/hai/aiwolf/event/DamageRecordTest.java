package jp.ac.naist.hai.aiwolf.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 研究上のダメージ量の定義のテスト。
 *
 * <p>採用しているのは {@code EFFECTIVE_HEALTH_LOSS}、すなわち
 * {@code (health + absorption) の適用前後の差}である。攻撃の威力ではなく、
 * 実際に失われた耐久を測る。防具を着けたオオカミと着けていないオオカミでは、
 * 同じゾンビの一撃でも経験の重さが違うからである。</p>
 *
 * <p>Fabric の {@code AFTER_DAMAGE} が渡す {@code damageTaken} は、公式 Javadoc に
 * 「防具とエンチャントによる軽減は含まない」と明記されており、この定義には使えない。</p>
 */
final class DamageRecordTest {

    private static DamageRecord record(double healthBefore, double absorptionBefore,
                                       double healthAfter, double absorptionAfter,
                                       boolean fatal) {
        return new DamageRecord(DamageRecord.ROLE_WOLF, DamageRecord.ROLE_HOSTILE, "zombie-a",
                "minecraft:zombie", "minecraft:zombie",
                DamageRecord.effectiveLoss(healthBefore, absorptionBefore,
                        healthAfter, absorptionAfter),
                healthBefore, healthAfter, absorptionBefore, absorptionAfter,
                fatal, "wolf-1#c0", 1000L);
    }

    @Test
    @DisplayName("通常の被弾：20 → 17 なら 3")
    void ordinaryDamageIsTheHealthDifference() {
        DamageRecord damage = record(20.0, 0.0, 17.0, 0.0, false);

        assertEquals(3.0, damage.actualDamage(), 1e-9);
        assertEquals(3.0, damage.healthLoss(), 1e-9);
        assertEquals(0.0, damage.absorptionLoss(), 1e-9);
        assertFalse(damage.fatal());
    }

    @Test
    @DisplayName("防具で軽減された被弾：威力が大きくても実損が 2 なら 2")
    void armourReducedDamageCountsWhatActuallyLanded() {
        // 威力 9 の攻撃を防具で受け、実際には 2 しか減らなかった場合
        DamageRecord damage = record(20.0, 0.0, 18.0, 0.0, false);

        assertEquals(2.0, damage.actualDamage(), 1e-9,
                "攻撃の威力ではなく、実際に失われた耐久を測る");
    }

    @Test
    @DisplayName("Absorption が削られた場合も被害として数える")
    void absorptionLossIsDamage() {
        DamageRecord damage = record(20.0, 4.0, 20.0, 1.0, false);

        assertEquals(3.0, damage.actualDamage(), 1e-9,
                "HP が減らなくても傷つけられた事実は起きている");
        assertEquals(0.0, damage.healthLoss(), 1e-9);
        assertEquals(3.0, damage.absorptionLoss(), 1e-9);
    }

    @Test
    @DisplayName("Absorption を貫通して HP まで届いた場合")
    void absorptionAndHealthCanBothDrop() {
        DamageRecord damage = record(20.0, 2.0, 17.0, 0.0, false);

        assertEquals(5.0, damage.actualDamage(), 1e-9);
        assertEquals(3.0, damage.healthLoss(), 1e-9);
        assertEquals(2.0, damage.absorptionLoss(), 1e-9,
                "HP のみの損失も別に復元できるようにしてある");
    }

    @Test
    @DisplayName("致死攻撃：3 → 0 なら 3 で fatal")
    void fatalHitIsRecordedWithItsDamage() {
        DamageRecord damage = record(3.0, 0.0, 0.0, 0.0, true);

        assertEquals(3.0, damage.actualDamage(), 1e-9);
        assertTrue(damage.fatal(),
                "致死攻撃で「死んだ」ことしか分からない状態にしてはならない");
        assertEquals("zombie-a", damage.attackerId());
        assertEquals("wolf-1#c0", damage.combatId());
    }

    @Test
    @DisplayName("被害 0 は被害として数えない")
    void zeroLossIsZero() {
        assertEquals(0.0, DamageRecord.effectiveLoss(20.0, 0.0, 20.0, 0.0), 1e-9);
    }

    @Test
    @DisplayName("処理中に回復が挟まっても負にならない")
    void healingDuringTheHitDoesNotGoNegative() {
        assertEquals(0.0, DamageRecord.effectiveLoss(10.0, 0.0, 12.0, 0.0), 1e-9,
                "負のダメージは学習を逆向きに動かす");
    }

    @Test
    @DisplayName("環境ダメージには加害者がいない")
    void environmentalDamageHasNoAttacker() {
        DamageRecord damage = new DamageRecord(DamageRecord.ROLE_WOLF,
                DamageRecord.ROLE_ENVIRONMENT, null, null, null,
                4.0, 20.0, 16.0, 0.0, 0.0, false, null, 1000L);

        assertTrue(damage.environmental());
        assertNull(damage.attackerId());
        assertNull(damage.combatId(), "環境ダメージは戦闘に結び付かない");
        assertEquals(4.0, damage.actualDamage(), 1e-9, "それでも被害としては記録する");
    }

    @Test
    @DisplayName("ダメージの定義が識別子として残る")
    void definitionIsRecorded() {
        assertEquals("EFFECTIVE_HEALTH_LOSS", DamageRecord.DAMAGE_DEFINITION,
                "解析時に damageAmount の意味が分からなくなるのを防ぐ");
    }

    @Test
    @DisplayName("矢の直接の出どころと原因を別々に残す")
    void directAndCausingSourcesAreBothKept() {
        DamageRecord damage = new DamageRecord(DamageRecord.ROLE_WOLF,
                DamageRecord.ROLE_HOSTILE, "skelet-b",
                "minecraft:arrow", "minecraft:skeleton",
                3.0, 20.0, 17.0, 0.0, 0.0, false, "wolf-1#c1", 1000L);

        assertEquals("minecraft:arrow", damage.directSourceType());
        assertEquals("minecraft:skeleton", damage.causingSourceType(),
                "研究上の相手は矢ではなく射手");
        assertEquals("skelet-b", damage.attackerId());
    }
}
