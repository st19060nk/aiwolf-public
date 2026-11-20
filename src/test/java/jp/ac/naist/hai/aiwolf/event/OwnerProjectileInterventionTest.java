package jp.ac.naist.hai.aiwolf.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 飼い主が弓・クロスボウで救援したときに、それが介入として記録されることのテスト。
 *
 * <p>Phase 13.2 までは近接攻撃だけを {@code AttackEntityCallback} で拾っていた。
 * 飼い主が矢で敵を削り、オオカミがとどめを刺した戦闘は
 * 「飼い主は何もしていない」と記録され、{@code PLAYER_PROTECTED_WOLF} が成立しなかった。
 * 遠距離で援護する遊び方をする参加者ほど、救われた経験が記録されないことになる。</p>
 */
final class OwnerProjectileInterventionTest {

    private static CombatEpisode episodeWithWolfDamaged() {
        CombatEpisode combat = CombatEpisode.open("wolf-1#c0", "zombie-1", "minecraft:zombie",
                CombatTrigger.WOLF_DAMAGED, 1000L, 1.0);
        combat.recordWolfDamage(4.0, 0.7, 1000L);
        return combat;
    }

    @Test
    @DisplayName("Test A: 矢で削り、オオカミがとどめを刺すと救出が成立する")
    void arrowThenWolfKillsCountsAsRescue() {
        CombatEpisode combat = episodeWithWolfDamaged();
        // 飼い主が矢を当てる。敵はまだ生きている。
        combat.recordOwnerAttack(1040L);
        // オオカミが反撃してとどめを刺す。
        combat.recordWolfAttack(1060L);
        combat.markHostileRemoved(true, 1080L);

        assertEquals(Optional.of(WolfEventType.PLAYER_PROTECTED_WOLF), combat.resolve(),
                "飼い主が最後の一撃を入れていなくても、援護は救出である");
        assertTrue(combat.ownerParticipated());
    }

    @Test
    @DisplayName("Test B: 別の敵を撃っても、こちらの戦闘では救出にならない")
    void arrowAtAnotherHostileDoesNotCount() {
        // オオカミが戦っている敵
        CombatEpisode attacked = episodeWithWolfDamaged();
        // 飼い主はまったく別の敵を撃っている（別 episode）
        CombatEpisode elsewhere = CombatEpisode.open("wolf-1#c1", "zombie-2", "minecraft:zombie",
                CombatTrigger.WOLF_TARGETED_HOSTILE, 1000L, 1.0);
        elsewhere.recordOwnerAttack(1040L);

        attacked.recordWolfAttack(1060L);
        attacked.markHostileRemoved(true, 1080L);

        assertEquals(Optional.empty(), attacked.resolve(),
                "無関係な敵への攻撃を、こちらの戦闘の介入として数えてはならない");
        assertFalse(attacked.ownerParticipated());
    }

    @Test
    @DisplayName("Test C: 同じ敵を何度撃っても介入は 1 つの事実として保持される")
    void repeatedArrowsKeepOneParticipation() {
        CombatEpisode combat = episodeWithWolfDamaged();
        combat.recordOwnerAttack(1040L);
        combat.recordOwnerAttack(1060L);
        combat.recordOwnerAttack(1080L);
        combat.recordWolfAttack(1090L);
        combat.markHostileRemoved(true, 1100L);

        assertTrue(combat.ownerParticipated());
        assertEquals(Optional.of(WolfEventType.PLAYER_PROTECTED_WOLF), combat.resolve());
        assertEquals(Optional.empty(), combat.resolve(),
                "何度撃たれても発火は 1 件。介入は回数ではなく事実として持つ");
    }

    @Test
    @DisplayName("Test D: 近接での救援も従来どおり成立する")
    void meleeRescueStillWorks() {
        CombatEpisode combat = episodeWithWolfDamaged();
        combat.recordOwnerAttack(1040L);
        combat.markHostileRemoved(true, 1060L);

        assertEquals(Optional.of(WolfEventType.PLAYER_PROTECTED_WOLF), combat.resolve());
    }

    @Test
    @DisplayName("被弾より前の援護は救出にならない（順序を見る）")
    void arrowBeforeTheWolfWasHurtIsNotRescue() {
        CombatEpisode combat = CombatEpisode.open("wolf-1#c2", "zombie-1", "minecraft:zombie",
                CombatTrigger.WOLF_TARGETED_HOSTILE, 1000L, 1.0);
        combat.recordOwnerAttack(1010L);
        combat.recordWolfAttack(1020L);
        combat.recordWolfDamage(4.0, 0.7, 1040L);
        combat.markHostileRemoved(true, 1060L);

        assertEquals(Optional.of(WolfEventType.PLAYER_AND_WOLF_COMBAT), combat.resolve(),
                "先に撃っていた敵に後から噛まれただけなら救出ではない");
    }

    @Test
    @DisplayName("飼い主の援護と防衛成功は別の意味を保つ")
    void defendSuccessIsStillAboutTheWolfProtectingTheOwner() {
        CombatEpisode combat = CombatEpisode.open("wolf-1#c3", "skeleton-1", "minecraft:skeleton",
                CombatTrigger.OWNER_DAMAGED, 1000L, 1.0);
        combat.recordOwnerDamage(3.0, 1000L);
        combat.recordOwnerAttack(1020L);
        combat.recordWolfAttack(1040L);
        combat.markHostileRemoved(true, 1060L);

        assertEquals(Optional.of(WolfEventType.DEFEND_SUCCESS), combat.resolve(),
                "飼い主が撃たれ、オオカミが介入した戦闘は防衛成功のまま");
    }
    // ------------------------------------------- 救援後の再被弾（Phase 13.4）

    @Test
    @DisplayName("Test A: 救援したあとに再び被弾しても救出は成立する")
    void rescueSurvivesLaterDamageFromTheSameHostile() {
        CombatEpisode combat = CombatEpisode.open("wolf-1#c4", "zombie-1", "minecraft:zombie",
                CombatTrigger.WOLF_DAMAGED, 100L, 1.0);
        combat.recordWolfDamage(4.0, 0.8, 100L);
        combat.recordOwnerAttack(120L);
        // 救援のあとも戦闘が続き、オオカミがもう一度噛まれる
        combat.recordWolfDamage(3.0, 0.6, 130L);
        combat.markHostileRemoved(true, 150L);

        assertEquals(Optional.of(WolfEventType.PLAYER_PROTECTED_WOLF), combat.resolve(),
                "救援後も攻撃され続けるのは戦闘が長引いた場合＝劣勢だった場合。"
                        + "危機的な救援ほど落ちる判定にしてはならない");
        assertTrue(combat.ownerIntervenedAfterWolfDamage());
    }

    @Test
    @DisplayName("Test B: 飼い主の攻撃が被弾より前だけなら成立しない")
    void ownerAttackOnlyBeforeTheWolfWasHurtIsNotRescue() {
        CombatEpisode combat = CombatEpisode.open("wolf-1#c5", "zombie-1", "minecraft:zombie",
                CombatTrigger.WOLF_TARGETED_HOSTILE, 100L, 1.0);
        combat.recordOwnerAttack(100L);
        combat.recordWolfAttack(110L);
        combat.recordWolfDamage(4.0, 0.8, 120L);
        combat.markHostileRemoved(true, 150L);

        assertFalse(combat.ownerIntervenedAfterWolfDamage(),
                "先に殴っていた敵に後から噛まれただけでは救援ではない");
        assertEquals(Optional.of(WolfEventType.PLAYER_AND_WOLF_COMBAT), combat.resolve());
    }

    @Test
    @DisplayName("Test C: 別の敵を攻撃しても、こちらの戦闘は救出にならない")
    void attackingAnotherHostileIsNotRescue() {
        CombatEpisode attacked = CombatEpisode.open("wolf-1#c6", "zombie-A", "minecraft:zombie",
                CombatTrigger.WOLF_DAMAGED, 100L, 1.0);
        attacked.recordWolfDamage(4.0, 0.8, 100L);

        CombatEpisode elsewhere = CombatEpisode.open("wolf-1#c7", "zombie-B", "minecraft:zombie",
                CombatTrigger.WOLF_TARGETED_HOSTILE, 110L, 1.0);
        elsewhere.recordOwnerAttack(120L);

        attacked.markHostileRemoved(true, 150L);

        assertFalse(attacked.ownerIntervenedAfterWolfDamage());
        assertEquals(Optional.empty(), attacked.resolve(),
                "戦闘は相手ごとに分かれている。別の敵への攻撃は届かない");
    }

    @Test
    @DisplayName("Test D: 何度救援され何度噛まれても、発火は 1 回だけ")
    void repeatedRescueAndDamageStillFiresOnce() {
        CombatEpisode combat = CombatEpisode.open("wolf-1#c8", "zombie-1", "minecraft:zombie",
                CombatTrigger.WOLF_DAMAGED, 100L, 1.0);
        combat.recordWolfDamage(2.0, 0.9, 100L);
        combat.recordOwnerAttack(110L);
        combat.recordWolfDamage(2.0, 0.8, 120L);
        combat.recordOwnerAttack(130L);
        combat.recordWolfDamage(2.0, 0.7, 140L);
        combat.recordOwnerAttack(150L);
        combat.markHostileRemoved(true, 160L);

        assertEquals(Optional.of(WolfEventType.PLAYER_PROTECTED_WOLF), combat.resolve());
        assertEquals(Optional.empty(), combat.resolve(),
                "1 つの戦闘から 2 回発火すると、1 回の経験で二重に学習する");
        assertEquals(6.0, combat.wolfDamageTaken(), 1e-9, "ダメージ量は積み上がる");
    }

    @Test
    @DisplayName("救援の事実は、そのあと何度被弾しても降りない")
    void interventionFlagIsNeverCleared() {
        CombatEpisode combat = CombatEpisode.open("wolf-1#c9", "zombie-1", "minecraft:zombie",
                CombatTrigger.WOLF_DAMAGED, 100L, 1.0);
        combat.recordWolfDamage(2.0, 0.9, 100L);
        combat.recordOwnerAttack(110L);
        assertTrue(combat.ownerIntervenedAfterWolfDamage());

        for (int i = 0; i < 10; i++) {
            combat.recordWolfDamage(0.5, 0.5, 200L + i * 10L);
            assertTrue(combat.ownerIntervenedAfterWolfDamage(),
                    "「この戦闘で救援に入った」という事実は失われない");
        }
    }

    @Test
    @DisplayName("救援があってもオオカミが死ねば成立しない")
    void rescueDoesNotCountIfTheWolfDies() {
        CombatEpisode combat = CombatEpisode.open("wolf-1#c10", "zombie-1", "minecraft:zombie",
                CombatTrigger.WOLF_DAMAGED, 100L, 1.0);
        combat.recordWolfDamage(4.0, 0.3, 100L);
        combat.recordOwnerAttack(110L);
        combat.recordWolfDamage(5.0, 0.0, 120L);
        combat.markWolfDead();
        combat.markHostileRemoved(true, 130L);

        assertTrue(combat.ownerIntervenedAfterWolfDamage(), "介入自体はあった");
        assertEquals(Optional.empty(), combat.resolve(), "それでも救出として数えない");
    }
}
