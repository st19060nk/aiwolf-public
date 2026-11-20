package jp.ac.naist.hai.aiwolf.mc;

import java.util.List;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.event.DamageAttribution;
import jp.ac.naist.hai.aiwolf.event.DamageRecord;
import jp.ac.naist.hai.aiwolf.log.DamageLogJson;
import jp.ac.naist.hai.aiwolf.log.ResearchLogger;

/**
 * 観測した被弾を、関係する個体へ振り分ける唯一の入口。
 *
 * <p>ダメージを知る経路はここだけにしてある。以前は HP の差分を決定のたびに見ていたが、
 * 複数回の被弾が 1 件に潰れ、攻撃者もその時点の {@code getAttacker()} 1 体に丸められていた。
 * Fabric の {@code AFTER_DAMAGE} へ移した後も、防具の軽減が反映されず、
 * 致死攻撃では発火しないという問題が残っていた
 * （理由は {@code LivingEntityDamageMixin} の Javadoc にある）。</p>
 *
 * <h2>3 つの被害者を区別する</h2>
 *
 * <dl>
 *   <dt>オオカミが受けた</dt><dd>自身の戦闘記録へ。加害者ごとに分けて溜める</dd>
 *   <dt>飼い主が受けた</dt><dd>近傍にいる自分の飼い犬の戦闘記録へ</dd>
 *   <dt>敵が受けた</dt><dd>加害者が飼い主なら、その敵を追跡している飼い犬へ「介入した」と記録する</dd>
 * </dl>
 *
 * <p>3 つ目が Phase 13.3 で加わったもので、<strong>弓やクロスボウによる救援</strong>を
 * 捉えるために要る。近接攻撃しか見ていないと、飼い主が矢で敵を削って
 * オオカミがとどめを刺した戦闘が「飼い主は何もしていない」と記録される。</p>
 */
public final class DamageObserver {

    private DamageObserver() {
        throw new AssertionError("utility class");
    }

    /**
     * ダメージが実際に適用された直後に呼ばれる。
     *
     * @param victim           被害者
     * @param source           ダメージ源
     * @param healthBefore     適用前の HP
     * @param absorptionBefore 適用前の Absorption
     * @param healthAfter      適用後の HP
     * @param absorptionAfter  適用後の Absorption
     */
    public static void onDamageApplied(LivingEntity victim, DamageSource source,
                                       float healthBefore, float absorptionBefore,
                                       float healthAfter, float absorptionAfter) {
        World world = victim.getWorld();
        if (world == null || world.isClient()) {
            return;
        }
        double actualDamage = DamageRecord.effectiveLoss(healthBefore, absorptionBefore,
                healthAfter, absorptionAfter);
        if (actualDamage <= 0.0) {
            // 完全に防がれた攻撃。測るのは攻撃されたことではなく、実際に受けた被害。
            return;
        }

        Entity attacker = DamageAttribution.causingAttacker(source.getAttacker(), source.getSource());
        boolean fatal = healthAfter <= 0.0f || !victim.isAlive();
        long worldTime = world.getTime();

        if (victim instanceof WolfEntity wolf) {
            onWolfDamaged(wolf, source, attacker, actualDamage, healthBefore, healthAfter,
                    absorptionBefore, absorptionAfter, fatal, worldTime);
            return;
        }
        if (victim instanceof PlayerEntity player) {
            onOwnerDamaged(player, source, attacker, actualDamage, healthBefore, healthAfter,
                    absorptionBefore, absorptionAfter, fatal, worldTime);
            return;
        }
        if (CombatTracker.isHostile(victim)) {
            onHostileDamaged(victim, attacker, actualDamage, source.getSource() != attacker, worldTime);
        }
    }

    /** オオカミが受けた被害を、その個体の戦闘記録と研究ログへ入れる。 */
    private static void onWolfDamaged(WolfEntity wolf, DamageSource source, Entity attacker,
                                      double actualDamage, float healthBefore, float healthAfter,
                                      float absorptionBefore, float absorptionAfter,
                                      boolean fatal, long worldTime) {
        if (!wolf.isTamed()) {
            return;
        }
        WolfAiRegistry.find(wolf).ifPresent(controller -> {
            String combatId = controller.observer()
                    .onWolfDamaged(wolf, attacker, actualDamage, healthBefore / maxHealth(wolf),
                            healthAfter / maxHealth(wolf), fatal, worldTime);
            log(DamageRecord.ROLE_WOLF, source, attacker, actualDamage,
                    healthBefore, healthAfter, absorptionBefore, absorptionAfter,
                    fatal, combatId, worldTime, controller);
        });
    }

    /** 飼い主が受けた被害を、近傍にいる飼い犬それぞれへ伝える。 */
    private static void onOwnerDamaged(PlayerEntity player, DamageSource source, Entity attacker,
                                       double actualDamage, float healthBefore, float healthAfter,
                                       float absorptionBefore, float absorptionAfter,
                                       boolean fatal, long worldTime) {
        PlayerBehaviorLogger.onPlayerDamaged(player, attacker, actualDamage, worldTime);
        for (WolfEntity wolf : ownedWolvesNear(player)) {
            WolfAiRegistry.find(wolf).ifPresent(controller -> {
                String combatId = controller.observer()
                        .onOwnerDamaged(wolf, attacker, actualDamage, worldTime);
                log(DamageRecord.ROLE_OWNER, source, attacker, actualDamage,
                        healthBefore, healthAfter, absorptionBefore, absorptionAfter,
                        fatal, combatId, worldTime, controller);
            });
        }
    }

    /**
     * 敵が受けた被害から、飼い主の介入を記録する。
     *
     * <p><strong>すでに開いている戦闘だけを更新する。</strong>飼い主が敵を殴ったという
     * だけで戦闘を新しく開くと、オオカミが一切関わっていない戦いまで記録され、
     * 結末が来ないまま枠を食いつぶす。</p>
     *
     * <p>対象はその敵を追跡している個体のうち、<strong>攻撃したプレイヤーを飼い主に持つもの</strong>
     * だけである。他人の攻撃を自分の飼い主の介入として数えてはならない。</p>
     */
    private static void onHostileDamaged(LivingEntity hostile, Entity attacker, double actualDamage,
                                         boolean projectile, long worldTime) {
        if (!(attacker instanceof PlayerEntity player)) {
            return;
        }
        CombatEvents.recordOwnerAttack(hostile.getUuid(), player.getUuid(), worldTime);
        // Player Behavior Logger。救援の判定（CombatTracker）とは独立した観測で、判定には使わない。
        PlayerBehaviorLogger.onAttackHostile(player, hostile, actualDamage, projectile, worldTime);
    }

    private static List<WolfEntity> ownedWolvesNear(PlayerEntity owner) {
        double radius = AiWolfParameters.OWNER_PRESENCE_RADIUS;
        UUID ownerUuid = owner.getUuid();
        return owner.getWorld().getEntitiesByClass(WolfEntity.class,
                Box.of(owner.getPos(), radius * 2.0, radius * 2.0, radius * 2.0),
                wolf -> wolf.isTamed() && ownerUuid.equals(wolf.getOwnerUuid())
                        && WolfEventObserver.isOwnerNearby(wolf, owner));
    }

    private static float maxHealth(LivingEntity entity) {
        float max = entity.getMaxHealth();
        return max <= 0.0f ? 1.0f : max;
    }

    /** 1 発ぶんの被弾を研究ログへ 1 行書く。 */
    private static void log(String targetType, DamageSource source, Entity attacker,
                            double actualDamage, float healthBefore, float healthAfter,
                            float absorptionBefore, float absorptionAfter,
                            boolean fatal, String combatId, long worldTime,
                            WolfAiController controller) {
        DamageRecord record = new DamageRecord(targetType,
                attackerRole(attacker, controller),
                attackerId(attacker),
                typeOrNull(source.getSource()),
                typeOrNull(attacker),
                actualDamage, healthBefore, healthAfter, absorptionBefore, absorptionAfter,
                fatal, combatId, worldTime);
        ResearchLogger.logDamage(DamageLogJson.toJsonLine(controller.agent().wolfId(),
                controller.agent().ownerResearchId(), controller.agent().condition(), record));
    }

    /** 加害者の区分を返す。プレイヤー名は決して残さない。 */
    private static String attackerRole(Entity attacker, WolfAiController controller) {
        if (attacker == null) {
            return DamageRecord.ROLE_ENVIRONMENT;
        }
        if (CombatTracker.isHostile(attacker)) {
            return DamageRecord.ROLE_HOSTILE;
        }
        if (attacker instanceof WolfEntity) {
            return DamageRecord.ROLE_WOLF;
        }
        if (attacker instanceof PlayerEntity player) {
            String ownerId = controller.agent().ownerResearchId();
            return ownerId != null && ownerId.equals(WolfAiRegistry.anonymousId(player.getUuid()))
                    ? DamageRecord.ROLE_OWNER
                    : DamageRecord.ROLE_OTHER;
        }
        return DamageRecord.ROLE_OTHER;
    }

    /**
     * 研究ログへ書く加害者の識別子を返す。
     *
     * <p>プレイヤーは匿名 ID、Mob は短縮 UUID。矢で撃たれた場合の加害者は
     * すでに矢ではなく射手へ解決されているので、飼い主が弓で撃ったダメージも
     * 匿名 ID として記録される。</p>
     *
     * @param attacker 加害者（{@code null} 可）
     * @return 識別子。加害者がいなければ {@code null}
     */
    private static String attackerId(Entity attacker) {
        if (attacker == null) {
            return null;
        }
        boolean player = attacker instanceof PlayerEntity;
        return DamageAttribution.attackerId(player,
                player ? WolfAiRegistry.anonymousId(attacker.getUuid()) : null,
                player ? null : CombatTracker.shortId(attacker.getUuid()));
    }

    private static String typeOrNull(Entity entity) {
        return entity == null ? null : CombatTracker.typeId(entity);
    }
}
