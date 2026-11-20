package jp.ac.naist.hai.aiwolf.mc;

import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.world.World;

import jp.ac.naist.hai.aiwolf.event.DamageAttribution;


/**
 * 戦闘に関わる Minecraft イベントを、関係する個体の {@link CombatTracker} へ振り分ける。
 *
 * <h2>誰に伝えるかを距離で決めない</h2>
 *
 * <p>敵の死亡と飼い主の介入は、<strong>その敵との戦闘を追跡している個体</strong>へ
 * 伝える。以前は死亡地点の近くにいる個体を探していたため、退避や場所回避で
 * 敵から離れた個体には結末が届かず、その戦闘は時間切れで消えていた。
 * 消えるのが「逃げた個体の戦闘」に偏るので、測ろうとしている個体差そのものに
 * バイアスがかかっていた。</p>
 *
 * <h2>役割の分離</h2>
 *
 * <p>被弾の観測と帰属は {@link DamageObserver} が担当し、こちらは
 * <strong>戦闘の終了だけ</strong>を扱う。分けておかないと、とどめの一撃が
 * ダメージ経路と死亡経路の両方から入り、同じ攻撃が二重に数えられる。</p>
 */
public final class CombatEvents {

    private CombatEvents() {
        throw new AssertionError("utility class");
    }

    /**
     * エンティティが死亡したときに呼ばれる。
     *
     * <p>敵性 MOB の死亡は戦闘の結末そのものなので、その敵との戦闘を追跡している
     * 個体すべてへ伝える。オオカミ自身の死亡は、その戦闘を成功として数えないための記録になる。</p>
     *
     * @param entity 死亡したエンティティ
     * @param source ダメージ源
     */
    public static void onEntityDeath(LivingEntity entity, DamageSource source) {
        World world = entity.getWorld();
        if (world.isClient()) {
            return;
        }

        if (entity instanceof WolfEntity deadWolf) {
            WolfAiRegistry.find(deadWolf).ifPresent(controller ->
                    controller.observer().combatTracker().onWolfDied());
            return;
        }
        if (!CombatTracker.isHostile(entity)) {
            return;
        }

        long worldTime = world.getTime();
        UUID hostileUuid = entity.getUuid();
        // 戦闘の追跡を更新する前に、倒したプレイヤーの記録を残す（倒す直前の状態が必要なため）。
        if (DamageAttribution.causingAttacker(source.getAttacker(), source.getSource())
                instanceof net.minecraft.entity.player.PlayerEntity killer) {
            PlayerBehaviorLogger.onKilledHostile(killer, entity, worldTime);
        }
        // 距離ではなく戦闘の対応関係で伝える。退避中の個体にも必ず届く。
        WolfAiRegistry.forEachTrackingCombat(hostileUuid, controller ->
                controller.observer().combatTracker().onHostileDied(hostileUuid, worldTime));
    }

    /**
     * 飼い主がその敵を攻撃したことを、対応する戦闘へ記録する。
     *
     * @param hostileUuid 相手の UUID
     * @param attackerUuid 攻撃したプレイヤーの UUID
     * @param worldTime   発生ワールド時刻
     */
    static void recordOwnerAttack(UUID hostileUuid, UUID attackerUuid, long worldTime) {
        if (attackerUuid == null) {
            return;
        }
        WolfAiRegistry.forEachTrackingCombat(hostileUuid, controller -> {
            if (isOwnerOf(controller, attackerUuid)) {
                controller.observer().combatTracker().onOwnerAttackedHostile(hostileUuid, worldTime);
            }
        });
    }

    private static boolean isOwnerOf(WolfAiController controller, UUID candidate) {
        String ownerId = controller.agent().ownerResearchId();
        if (ownerId == null || candidate == null) {
            return false;
        }
        return ownerId.equals(WolfAiRegistry.anonymousId(candidate));
    }
}
