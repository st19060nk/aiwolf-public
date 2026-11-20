package jp.ac.naist.hai.aiwolf.consistency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.event.CombatEpisode;
import jp.ac.naist.hai.aiwolf.event.CombatTrigger;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 戦闘の結末が、逃げた個体にも届くことのテスト。
 *
 * <p>以前は敵の死亡を「死亡地点の近くにいる個体」へしか伝えていなかった。
 * 退避や場所回避で敵から離れた個体には結末が届かず、その戦闘は時間切れで
 * 消えていた。<strong>消えるのが「逃げた個体の戦闘」に偏る</strong>ので、
 * 恐怖傾向の強い個体ほど救われた経験が記録されない、という欠測が生じる。
 * まさに測ろうとしている個体差に相関した欠測であり、研究結果を直接歪める。</p>
 *
 * <p>通知は距離ではなく、敵 UUID と戦闘記録の対応で決める。</p>
 */
final class CombatNotificationTest {

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && candidate != null; i++) {
            if (Files.isDirectory(candidate.resolve("src/main/java"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("リポジトリのルートが見つからない");
    }

    private static String source(String relative) throws IOException {
        Path file = repositoryRoot().resolve(relative);
        assertTrue(Files.isRegularFile(file), "対象ファイルがない: " + relative);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------- 逃げた個体の戦闘の結末

    @Test
    @DisplayName("退避して離れていても、飼い主が敵を倒せば救出が成立する")
    void retreatingWolfStillLearnsTheOutcome() {
        CombatEpisode combat = CombatEpisode.open("wolf-1#c0", "zombie-1", "minecraft:zombie",
                CombatTrigger.WOLF_DAMAGED, 1000L, 1.0);
        combat.recordWolfDamage(6.0, 0.4, 1000L);
        // ここでオオカミは RETREAT し、以降この敵に一切関与しない。
        // 距離で通知先を決めていると、この先の死亡が届かない。
        combat.recordOwnerAttack(1080L);
        combat.markHostileRemoved(true, 1100L);

        assertEquals(Optional.of(WolfEventType.PLAYER_PROTECTED_WOLF), combat.resolve(),
                "逃げた個体こそ「守られた」経験を記録する必要がある");
    }

    @Test
    @DisplayName("結末が届かないと戦闘は時間切れで消える（通知が必要な理由）")
    void unnotifiedEpisodeExpiresWithoutAnyEvent() {
        CombatEpisode combat = CombatEpisode.open("wolf-1#c1", "zombie-1", "minecraft:zombie",
                CombatTrigger.WOLF_DAMAGED, 1000L, 1.0);
        combat.recordWolfDamage(6.0, 0.4, 1000L);
        combat.recordOwnerAttack(1080L);
        // markHostileRemoved が届かない＝死亡通知が距離で弾かれた場合

        assertTrue(combat.isExpired(1080L + AiWolfParameters.COMBAT_EPISODE_TIMEOUT_TICKS,
                AiWolfParameters.COMBAT_EPISODE_TIMEOUT_TICKS));
        assertEquals(Optional.empty(), combat.resolve(),
                "通知が届かなければ、実際には救出されたのに記録が残らない");
    }

    @Test
    @DisplayName("防衛成功でも同じく、離れた位置での撃破が届く必要がある")
    void defendSuccessAlsoNeedsTheOutcome() {
        CombatEpisode combat = CombatEpisode.open("wolf-1#c2", "skeleton-1", "minecraft:skeleton",
                CombatTrigger.OWNER_DAMAGED, 1000L, 1.0);
        combat.recordOwnerDamage(4.0, 1000L);
        combat.recordWolfAttack(1020L);
        // オオカミが深追いして離れた先で敵が倒れた場合も届かねばならない
        combat.markHostileRemoved(true, 1200L);

        assertEquals(Optional.of(WolfEventType.DEFEND_SUCCESS), combat.resolve());
    }

    // ------------------------------------------------------- 実装の回帰テスト

    @Test
    @DisplayName("敵の死亡は距離ではなく戦闘の対応関係で伝える")
    void hostileDeathIsRoutedByCombatNotDistance() throws IOException {
        String combatEvents = source("src/main/java/jp/ac/naist/hai/aiwolf/mc/CombatEvents.java");

        assertTrue(combatEvents.contains("forEachTrackingCombat"),
                "敵 UUID と戦闘記録の対応で通知先を決めること");
        assertFalse(combatEvents.contains("anyTamedWolvesNear"),
                "死亡地点の近傍スキャンで通知先を絞ると、退避した個体の戦闘が落ちる");
        assertFalse(combatEvents.contains("HOSTILE_SCAN_RADIUS"),
                "戦闘の通知に敵スキャン半径を使ってはならない");
    }

    @Test
    @DisplayName("飼い主の介入も戦闘の対応関係で伝える")
    void ownerInterventionIsRoutedByCombat() throws IOException {
        String combatEvents = source("src/main/java/jp/ac/naist/hai/aiwolf/mc/CombatEvents.java");

        assertTrue(combatEvents.contains("recordOwnerAttack"),
                "飼い主の攻撃も、退避中の個体の戦闘へ届く必要がある");
        assertFalse(combatEvents.contains("onPlayerAttack"),
                "近接だけを拾う経路は撤去した（Mixin 経路と二重になるため）");
    }

    @Test
    @DisplayName("飼い主の被弾だけは距離で絞る（定義がそうなっているため）")
    void ownerDamageIsStillBoundedByPresence() throws IOException {
        String observer = source("src/main/java/jp/ac/naist/hai/aiwolf/mc/DamageObserver.java");

        assertTrue(observer.contains("OWNER_PRESENCE_RADIUS"),
                "PLAYER_DAMAGED_NEAR_WOLF は「そばで起きた」ことが条件なので距離で絞る");
        assertTrue(observer.contains("isOwnerNearby"),
                "在否の定義は 1 か所（OwnerPresence）だけを使う");
    }

    // ------------------------------------------- ダメージ検出の単一経路

    @Test
    @DisplayName("HP 差分ポーリングによるダメージ検出が残っていない")
    void noHealthPollingRemains() throws IOException {
        String observer = source("src/main/java/jp/ac/naist/hai/aiwolf/mc/WolfEventObserver.java");

        assertFalse(observer.contains("previousWolfHealth"),
                "コールバックと差分の二重記録になる");
        assertFalse(observer.contains("previousOwnerHealth"),
                "コールバックと差分の二重記録になる");
        assertFalse(observer.contains("detectWolfDamage"));
        assertFalse(observer.contains("detectOwnerDamage"));
    }

    @Test
    @DisplayName("ダメージは実際に適用された被害から取り込む")
    void damageComesFromTheDamageEvent() throws IOException {
        String mixin = source("src/main/java/jp/ac/naist/hai/aiwolf/mixin/LivingEntityDamageMixin.java");
        String damageObserver = source("src/main/java/jp/ac/naist/hai/aiwolf/mc/DamageObserver.java");
        String observer = source("src/main/java/jp/ac/naist/hai/aiwolf/mc/WolfEventObserver.java");

        assertTrue(mixin.contains("@At(\"HEAD\")") && mixin.contains("@At(\"RETURN\")"),
                "適用前後の耐久を読むこと。RETURN なら死亡経路も捉えられる");
        assertTrue(damageObserver.contains("onDamageApplied"));
        assertTrue(observer.contains("public String onWolfDamaged"));
        assertTrue(observer.contains("public String onOwnerDamaged"));
        assertTrue(damageObserver.contains("DamageAttribution"),
                "矢は射手へ帰属させること");
    }

    @Test
    @DisplayName("研究上のダメージ量は前後の耐久の差から求める")
    void damageAmountIsWhatActuallyLanded() throws IOException {
        String observer = source("src/main/java/jp/ac/naist/hai/aiwolf/mc/DamageObserver.java");

        assertTrue(observer.contains("DamageRecord.effectiveLoss("),
                "攻撃の威力ではなく、実際に失われた耐久を使うこと");
        assertTrue(observer.contains("actualDamage <= 0.0"),
                "完全に防いだ攻撃を被害として学習してはならない");
        String mod = source("src/main/java/jp/ac/naist/hai/aiwolf/AiWolfMod.java");
        assertFalse(mod.contains("AFTER_DAMAGE.register"),
                "AFTER_DAMAGE の damageTaken は防具の軽減を含まず、致死攻撃では発火しない"
                        + "（Fabric 公式 Javadoc）。研究用の実 HP 損失には使えない");
    }

    @Test
    @DisplayName("加害者ごとにダメージを分けて溜める")
    void damageIsKeptApartPerHostile() throws IOException {
        String observer = source("src/main/java/jp/ac/naist/hai/aiwolf/mc/WolfEventObserver.java");

        assertTrue(observer.contains("wolfDamage.add(sourceKey("),
                "Zombie の 2 と Skeleton の 3 を「Skeleton から 5」にしてはならない");
        assertTrue(observer.contains("ownerDamage.add(sourceKey("));
    }

    @Test
    @DisplayName("研究イベントの発火は決定ごとに最大 1 件のまま")
    void researchEventsStillFireOncePerDecision() throws IOException {
        String observer = source("src/main/java/jp/ac/naist/hai/aiwolf/mc/WolfEventObserver.java");

        assertTrue(observer.contains("DamageAccumulator"),
                "1 発ごとに発火させると、1 戦闘で 3 回噛まれた個体が 3 倍の速さで学習する");
        assertTrue(observer.contains("emitPendingWolfDamage"));
        assertTrue(observer.contains("emitPendingOwnerDamage"));
    }
    // ------------------------------------------- Phase 13.3：経路の役割分離

    @Test
    @DisplayName("飼い主の遠距離攻撃も介入として拾える")
    void projectileInterventionIsCaptured() throws IOException {
        String damageObserver = source("src/main/java/jp/ac/naist/hai/aiwolf/mc/DamageObserver.java");

        assertTrue(damageObserver.contains("onHostileDamaged"),
                "敵が被弾したときにも飼い主の介入を見ないと、弓の救援が記録されない");
        assertTrue(damageObserver.contains("attacker instanceof PlayerEntity"),
                "敵を殴ったのが飼い主かどうかで判定する");
        assertTrue(damageObserver.contains("CombatEvents.recordOwnerAttack"),
                "既存の戦闘だけを更新する経路を通すこと");
    }

    @Test
    @DisplayName("飼い主の攻撃だけで新しい戦闘を開かない")
    void ownerAttackNeverOpensANewEpisode() throws IOException {
        String combatEvents = source("src/main/java/jp/ac/naist/hai/aiwolf/mc/CombatEvents.java");

        assertTrue(combatEvents.contains("forEachTrackingCombat"),
                "すでに追跡している戦闘だけを更新すること");
        assertFalse(combatEvents.contains("episodeFor"),
                "飼い主が殴っただけで戦闘を開くと、結末の来ない戦闘が枠を食いつぶす");
    }

    @Test
    @DisplayName("同じ一撃がダメージ経路と死亡経路の両方から入らない")
    void theSameHitIsNotCountedTwice() throws IOException {
        String tracker = source("src/main/java/jp/ac/naist/hai/aiwolf/mc/CombatTracker.java");
        String mod = source("src/main/java/jp/ac/naist/hai/aiwolf/AiWolfMod.java");

        assertFalse(tracker.contains("public void onHostileDied(UUID hostileUuid, boolean"),
                "死亡経路は戦闘の終了だけを担当する。介入の記録はダメージ経路が済ませている");
        assertFalse(mod.contains("AttackEntityCallback.EVENT"),
                "近接専用の経路を残すと、Mixin 経路と同じ一撃で二重に記録される");
        assertTrue(mod.contains("AFTER_DEATH.register"),
                "死亡による戦闘終了は引き続き購読する");
    }

    @Test
    @DisplayName("致死攻撃がダメージ経路で捕捉される")
    void fatalHitIsCapturedOnTheDamagePath() throws IOException {
        String observer = source("src/main/java/jp/ac/naist/hai/aiwolf/mc/WolfEventObserver.java");
        String damageObserver = source("src/main/java/jp/ac/naist/hai/aiwolf/mc/DamageObserver.java");

        assertTrue(damageObserver.contains("boolean fatal = healthAfter <= 0.0f"),
                "致死かどうかは適用後の HP から判断する");
        assertTrue(observer.contains("combatTracker.onWolfDied()"),
                "致死攻撃を受けた戦闘は成功として数えない");
    }

    @Test
    @DisplayName("Mixin は観測だけで、ダメージ処理を変更しない")
    void theMixinOnlyObserves() throws IOException {
        String mixin = source("src/main/java/jp/ac/naist/hai/aiwolf/mixin/LivingEntityDamageMixin.java");

        assertFalse(mixin.contains("setReturnValue"), "戻り値を書き換えてはならない");
        assertFalse(mixin.contains("cancellable = true"), "キャンセルしてはならない");
        assertTrue(mixin.contains("@At(\"RETURN\")"),
                "TAIL では死亡経路に届かない。RETURN ですべての return を捉える");
    }
}
