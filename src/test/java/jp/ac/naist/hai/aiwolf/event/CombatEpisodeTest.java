package jp.ac.naist.hai.aiwolf.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 戦闘結末判定のテスト。
 *
 * <p>ここで検証しているのは「戦闘が起きたか」ではなく「どの戦闘を成功として
 * 数えるか」である。DEFEND_SUCCESS と PLAYER_PROTECTED_WOLF は state・記憶・選好の
 * 3 経路すべてに入るため、誤検出 1 件がそのまま研究データの誤りになる。</p>
 */
final class CombatEpisodeTest {

    private static CombatEpisode episode() {
        return CombatEpisode.open("wolf-1#c0", "zombie-1", "minecraft:zombie", 1000L, 1.0);
    }

    @Test
    @DisplayName("オオカミが被弾した後に飼い主が敵を倒すと PLAYER_PROTECTED_WOLF が成立する")
    void playerProtectedWolfIsDetected() {
        CombatEpisode combat = episode();
        combat.recordWolfDamage(4.0, 0.8, 1010L);
        combat.recordOwnerAttack(1015L);
        combat.markHostileRemoved(true, 1020L);

        assertEquals(Optional.of(WolfEventType.PLAYER_PROTECTED_WOLF), combat.resolve());
    }

    @Test
    @DisplayName("飼い主が敵を攻撃していなければ救出は成立しない")
    void ownerMustActuallyAttack() {
        CombatEpisode combat = episode();
        combat.recordWolfDamage(4.0, 0.8, 1010L);
        combat.recordWolfAttack(1012L);
        // 飼い主は近くにいたが一度も攻撃していない
        combat.markHostileRemoved(true, 1020L);

        assertEquals(Optional.empty(), combat.resolve(),
                "飼い主が居合わせただけの戦闘を救出として数えてはならない");
    }

    @Test
    @DisplayName("飼い主の攻撃が被弾より前なら救出ではない")
    void ownerAttackBeforeWolfDamageIsNotRescue() {
        CombatEpisode combat = episode();
        combat.recordOwnerAttack(1005L);
        combat.recordWolfAttack(1008L);
        combat.recordWolfDamage(4.0, 0.8, 1010L);
        combat.markHostileRemoved(true, 1020L);

        assertEquals(Optional.of(WolfEventType.PLAYER_AND_WOLF_COMBAT), combat.resolve(),
                "飼い主が先に殴っていた敵に後から噛まれただけなら救出ではない");
    }

    @Test
    @DisplayName("別の Mob を倒してもこの戦闘の結末は変わらない")
    void killingAnotherMobDoesNotResolveThisEpisode() {
        CombatEpisode combat = episode();
        combat.recordWolfDamage(4.0, 0.8, 1010L);
        combat.recordOwnerAttack(1015L);
        // この敵は倒されず、飼い主は別の Mob を倒した（= 別 episode の出来事）

        assertEquals(Optional.empty(), combat.resolve(),
                "相手ごとに episode を分けているので、別の Mob の死は影響しない");
        assertFalse(combat.hostileRemoved());
    }

    @Test
    @DisplayName("逃げ去った敵（未撃破）では救出も防衛成功も成立しない")
    void hostileEscapingIsNotSuccess() {
        CombatEpisode combat = episode();
        combat.recordWolfDamage(4.0, 0.8, 1010L);
        combat.recordOwnerDamage(2.0, 1011L);
        combat.recordWolfAttack(1012L);
        combat.recordOwnerAttack(1015L);
        combat.markHostileRemoved(false, 1400L);

        assertEquals(Optional.of(WolfEventType.PLAYER_AND_WOLF_COMBAT), combat.resolve(),
                "撃破していない戦闘は成功として数えない");
    }

    @Test
    @DisplayName("オオカミが死亡した戦闘は成功として数えない")
    void deadWolfNeverSucceeds() {
        CombatEpisode combat = episode();
        combat.recordWolfDamage(20.0, 0.0, 1010L);
        combat.recordOwnerAttack(1015L);
        combat.markHostileRemoved(true, 1020L);
        combat.markWolfDead();

        assertEquals(Optional.empty(), combat.resolve());
        assertFalse(combat.wolfSurvived());
    }

    @Test
    @DisplayName("飼い主が被弾しオオカミが介入して撃破すると DEFEND_SUCCESS が成立する")
    void defendSuccessIsDetected() {
        CombatEpisode combat = episode();
        combat.recordOwnerDamage(3.0, 1010L);
        combat.recordWolfAttack(1012L);
        combat.markHostileRemoved(true, 1030L);

        assertEquals(Optional.of(WolfEventType.DEFEND_SUCCESS), combat.resolve());
    }

    @Test
    @DisplayName("救出と防衛成功が同時に成立しても発火は 1 件だけ")
    void onlyTheHighestPriorityEventFires() {
        CombatEpisode combat = episode();
        combat.recordOwnerDamage(3.0, 1008L);
        combat.recordWolfAttack(1009L);
        combat.recordWolfDamage(4.0, 0.7, 1010L);
        combat.recordOwnerAttack(1015L);
        combat.markHostileRemoved(true, 1020L);

        assertEquals(Optional.of(WolfEventType.PLAYER_PROTECTED_WOLF), combat.resolve(),
                "1 回の戦闘から 2 件出すと state・記憶・選好へ二重に入る");
    }

    @Test
    @DisplayName("同一 combat から重複して発火しない")
    void resolveIsIdempotent() {
        CombatEpisode combat = episode();
        combat.recordWolfDamage(4.0, 0.8, 1010L);
        combat.recordOwnerAttack(1015L);
        combat.markHostileRemoved(true, 1020L);

        assertFalse(combat.isResolved());
        assertEquals(Optional.of(WolfEventType.PLAYER_PROTECTED_WOLF), combat.resolve());
        assertTrue(combat.isResolved());
        assertEquals(Optional.empty(), combat.resolve(), "2 回目は必ず空");
        assertEquals(Optional.empty(), combat.resolve());
    }

    @Test
    @DisplayName("無活動が続いた戦闘は期限切れとして回収できる")
    void inactiveEpisodeExpires() {
        CombatEpisode combat = episode();
        combat.recordWolfAttack(1010L);

        assertFalse(combat.isExpired(1100L, 200L));
        assertTrue(combat.isExpired(1210L, 200L));
    }

    @Test
    @DisplayName("ダメージ量と参加状況が研究ログ用メタデータに残る")
    void metadataRecordsWhoDidWhat() {
        CombatEpisode combat = episode();
        combat.recordWolfDamage(4.0, 0.8, 1010L);
        combat.recordOwnerDamage(2.5, 1011L);
        combat.recordOwnerAttack(1015L);
        combat.markHostileRemoved(true, 1020L);

        assertEquals(4.0, combat.wolfDamageTaken(), 1e-9);
        assertEquals(2.5, combat.ownerDamageTaken(), 1e-9);
        assertTrue(combat.ownerParticipated());
        assertFalse(combat.wolfParticipated());
        assertEquals("minecraft:zombie", combat.hostileType());
        assertTrue(combat.describe().stream().anyMatch(s -> s.startsWith("combatId=wolf-1#c0")));
    }

    @Test
    @DisplayName("負のダメージ値は加算されない")
    void negativeDamageIsIgnored() {
        CombatEpisode combat = episode();
        combat.recordWolfDamage(-5.0, 1.0, 1010L);
        combat.recordWolfAttack(1012L);
        combat.recordOwnerAttack(1015L);
        combat.markHostileRemoved(true, 1020L);

        assertEquals(0.0, combat.wolfDamageTaken(), 1e-9);
        assertEquals(Optional.of(WolfEventType.PLAYER_AND_WOLF_COMBAT), combat.resolve(),
                "被弾していないなら救出ではない");
    }
}
