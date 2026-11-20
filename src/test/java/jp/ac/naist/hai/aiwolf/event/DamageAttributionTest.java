package jp.ac.naist.hai.aiwolf.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ダメージの帰属規則のテスト。
 *
 * <p>Skeleton の矢が Skeleton ではなく矢に帰属すると、矢 1 本ごとに別の戦闘が開き、
 * そのどれもが結末を知らないまま時間切れで消える。Skeleton との戦闘だけが
 * 研究データから丸ごと落ちるため、遠距離攻撃してくる敵との関係が測れなくなる。</p>
 */
final class DamageAttributionTest {

    @Test
    @DisplayName("矢は矢ではなく射手へ帰属する")
    void projectileIsAttributedToTheShooter() {
        String shooter = "skeleton";
        String arrow = "arrow";

        assertEquals(shooter, DamageAttribution.causingAttacker(shooter, arrow));
    }

    @Test
    @DisplayName("近接攻撃では主体と直接の相手が同じ")
    void meleeAttributesToTheAttacker() {
        String zombie = "zombie";

        assertEquals(zombie, DamageAttribution.causingAttacker(zombie, zombie));
    }

    @Test
    @DisplayName("主体がいない場合は直接ぶつかったものへ落とす")
    void fallsBackToTheDirectSource() {
        assertEquals("lava", DamageAttribution.causingAttacker(null, "lava"));
    }

    @Test
    @DisplayName("どちらも無ければ帰属先なし")
    void noAttributionAtAll() {
        assertNull(DamageAttribution.causingAttacker(null, null));
    }

    @Test
    @DisplayName("敵性 MOB だけが戦闘の相手になる")
    void onlyHostilesAreCombatOpponents() {
        assertTrue(DamageAttribution.isCombatOpponent("zombie", true, false));
        assertFalse(DamageAttribution.isCombatOpponent("cactus", false, false),
                "サボテンや落下で戦闘を開いてはならない");
        assertFalse(DamageAttribution.isCombatOpponent(null, true, false));
    }

    @Test
    @DisplayName("自傷は戦闘として扱わない")
    void selfInflictedDamageIsNotCombat() {
        assertFalse(DamageAttribution.isCombatOpponent("self", true, true),
                "自分を相手に戦闘記録を開くと、結末が永遠に来ない");
    }
}
