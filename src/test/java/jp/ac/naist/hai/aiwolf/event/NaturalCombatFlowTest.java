package jp.ac.naist.hai.aiwolf.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 自然な Minecraft の戦闘の流れに沿った結末判定のテスト。
 *
 * <p>実機の戦闘は「敵が飼い主を殴る → 飼い主が被弾 → オオカミが介入」の順に進む。
 * Phase 13 の実装は飼い主の被弾時点で戦闘が開かれていない場合に
 * {@code ownerDamageTaken} を捨てていたため、この自然な順序では
 * {@code DEFEND_SUCCESS} が一度も成立しなかった。戦闘を飼い主の被弾から
 * 開始できるようにしたことをここで固定する。</p>
 *
 * <p>2 つのイベントの意味の違いも固定する。</p>
 * <dl>
 *   <dt>{@code DEFEND_SUCCESS}</dt><dd>飼い主が攻撃を受けた → オオカミが介入</dd>
 *   <dt>{@code PLAYER_PROTECTED_WOLF}</dt><dd>オオカミが攻撃を受けた → 飼い主が介入</dd>
 * </dl>
 */
final class NaturalCombatFlowTest {

    private static CombatEpisode startedByOwnerDamage() {
        return CombatEpisode.open("wolf-1#c0", "zombie-1", "minecraft:zombie",
                CombatTrigger.OWNER_DAMAGED, 1000L, 1.0);
    }

    private static CombatEpisode startedByWolfDamage() {
        return CombatEpisode.open("wolf-1#c1", "zombie-1", "minecraft:zombie",
                CombatTrigger.WOLF_DAMAGED, 1000L, 1.0);
    }

    @Test
    @DisplayName("Case A: 飼い主被弾 → オオカミ介入 → 撃破で DEFEND_SUCCESS")
    void caseAOwnerDamagedThenWolfIntervenes() {
        CombatEpisode combat = startedByOwnerDamage();
        combat.recordOwnerDamage(4.0, 1000L);
        combat.recordWolfAttack(1020L);
        combat.markHostileRemoved(true, 1060L);

        assertEquals(Optional.of(WolfEventType.DEFEND_SUCCESS), combat.resolve(),
                "敵が飼い主を先に殴る自然な流れで成立しなければ、防衛成功は実機で一度も起きない");
        assertEquals(CombatTrigger.OWNER_DAMAGED, combat.trigger());
    }

    @Test
    @DisplayName("Case B: 飼い主被弾 → オオカミ介入なし → 飼い主が撃破では不成立")
    void caseBWolfNeverIntervenes() {
        CombatEpisode combat = startedByOwnerDamage();
        combat.recordOwnerDamage(4.0, 1000L);
        combat.recordOwnerAttack(1020L);
        combat.markHostileRemoved(true, 1060L);

        assertEquals(Optional.empty(), combat.resolve(),
                "オオカミが何もしていない戦闘を防衛成功として数えてはならない");
        assertFalse(combat.wolfParticipated());
    }

    @Test
    @DisplayName("Case C: オオカミ被弾 → 飼い主介入 → 撃破で PLAYER_PROTECTED_WOLF")
    void caseCWolfDamagedThenOwnerIntervenes() {
        CombatEpisode combat = startedByWolfDamage();
        combat.recordWolfDamage(4.0, 0.7, 1000L);
        combat.recordOwnerAttack(1020L);
        combat.markHostileRemoved(true, 1060L);

        assertEquals(Optional.of(WolfEventType.PLAYER_PROTECTED_WOLF), combat.resolve());
        assertEquals(CombatTrigger.WOLF_DAMAGED, combat.trigger());
    }

    @Test
    @DisplayName("Case D: 飼い主被弾中にオオカミが別の敵を攻撃しても不成立")
    void caseDWolfFightsAnotherMob() {
        // 飼い主を殴っている敵の episode
        CombatEpisode attackerOfOwner = startedByOwnerDamage();
        attackerOfOwner.recordOwnerDamage(4.0, 1000L);
        attackerOfOwner.markHostileRemoved(true, 1060L);

        // オオカミが相手にしていた別の敵の episode
        CombatEpisode other = CombatEpisode.open("wolf-1#c2", "skeleton-9", "minecraft:skeleton",
                CombatTrigger.WOLF_TARGETED_HOSTILE, 1010L, 1.0);
        other.recordWolfAttack(1010L);
        other.markHostileRemoved(true, 1050L);

        assertEquals(Optional.empty(), attackerOfOwner.resolve(),
                "オオカミが介入していない敵の撃破を防衛成功にしてはならない");
        assertEquals(Optional.empty(), other.resolve(),
                "飼い主が被弾していない敵を倒しただけでは防衛成功ではない");
    }

    @Test
    @DisplayName("無関係な敵を倒しても戦闘は完結しない")
    void unrelatedHostileDoesNotCompleteCombat() {
        CombatEpisode combat = startedByOwnerDamage();
        combat.recordOwnerDamage(4.0, 1000L);
        combat.recordWolfAttack(1020L);
        // この敵は倒されていない。別の敵が死んでもこの episode には影響しない。

        assertFalse(combat.hostileRemoved());
        assertEquals(Optional.empty(), combat.resolve());
    }

    @Test
    @DisplayName("飼い主被弾から始まった戦闘でオオカミが死ねば不成立")
    void deadWolfNeverDefends() {
        CombatEpisode combat = startedByOwnerDamage();
        combat.recordOwnerDamage(4.0, 1000L);
        combat.recordWolfAttack(1020L);
        combat.markWolfDead();
        combat.markHostileRemoved(true, 1060L);

        assertEquals(Optional.empty(), combat.resolve());
    }

    @Test
    @DisplayName("飼い主被弾から始まった戦闘で敵が逃げれば成功ではない")
    void escapingHostileIsNotDefendSuccess() {
        CombatEpisode combat = startedByOwnerDamage();
        combat.recordOwnerDamage(4.0, 1000L);
        combat.recordWolfAttack(1020L);
        combat.markHostileRemoved(false, 1400L);

        assertEquals(Optional.empty(), combat.resolve(),
                "撃破していなければ防衛成功ではない（オオカミの攻撃記録だけでは足りない）");
    }

    @Test
    @DisplayName("開始のきっかけが研究ログで区別できる")
    void triggerIsRecorded() {
        assertEquals("owner_damaged", CombatTrigger.OWNER_DAMAGED.id());
        assertEquals("wolf_damaged", CombatTrigger.WOLF_DAMAGED.id());
        assertEquals("wolf_targeted_hostile", CombatTrigger.WOLF_TARGETED_HOSTILE.id());

        CombatEpisode combat = startedByOwnerDamage();
        combat.recordOwnerDamage(4.0, 1000L);
        assertTrue(combat.describe().stream().anyMatch(s -> s.equals("trigger=owner_damaged")));
        assertTrue(combat.ownerInvolved(), "被弾だけでも飼い主は関与している");
    }

    @Test
    @DisplayName("きっかけを指定しない従来の呼び出しも動く")
    void defaultTriggerStillWorks() {
        CombatEpisode combat = CombatEpisode.open("wolf-1#c0", "zombie-1",
                "minecraft:zombie", 1000L, 1.0);

        assertEquals(CombatTrigger.WOLF_TARGETED_HOSTILE, combat.trigger());
    }
}
