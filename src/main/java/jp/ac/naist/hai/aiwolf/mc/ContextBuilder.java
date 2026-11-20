package jp.ac.naist.hai.aiwolf.mc;

import java.util.List;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;

/**
 * Minecraft の世界から {@link WolfContextSnapshot} を組み立てる唯一の場所。
 *
 * <p>ここが「Minecraft 依存」と「純粋な意思決定」の境界である。これより下流
 * （BehaviorScorer / PolicySelector / 記憶）は {@code WolfEntity} を一切知らない。</p>
 *
 * <p>敵性 MOB の探索は決定時（既定 40 ticks ごと）にしか行わない。毎 tick の
 * 全エンティティ走査は性能要件で禁止されている。</p>
 */
public final class ContextBuilder {

    private ContextBuilder() {
        throw new AssertionError("utility class");
    }

    /**
     * 現在のゲーム状態からスナップショットを作る。
     *
     * @param wolf       対象のオオカミ
     * @param controller 個体のランタイム（空間記憶と飼い主移動の判定に使用）
     * @return 文脈スナップショット
     */
    public static WolfContextSnapshot build(WolfEntity wolf, WolfAiController controller) {
        World world = wolf.getWorld();
        LivingEntity owner = wolf.getOwner();

        // 在否の定義は WolfEventObserver と共有する。文脈と出来事検出で
        // 別々の閾値を使うと、「飼い主のそばで被弾した」のに「長期分離中」という
        // 矛盾した記録が生まれる。
        boolean ownerPresent = WolfEventObserver.isOwnerNearby(wolf, owner);

        double ownerDistance = ownerPresent
                ? wolf.distanceTo(owner)
                : WolfContextSnapshot.NO_TARGET_DISTANCE;
        double ownerHealthRatio = ownerPresent ? healthRatio(owner) : 1.0;

        List<HostileEntity> hostiles = world.getEntitiesByClass(HostileEntity.class,
                Box.of(wolf.getPos(), AiWolfParameters.HOSTILE_SCAN_RADIUS * 2.0,
                        AiWolfParameters.HOSTILE_SCAN_RADIUS * 2.0,
                        AiWolfParameters.HOSTILE_SCAN_RADIUS * 2.0),
                hostile -> hostile.isAlive());

        double nearestHostileDistance = WolfContextSnapshot.NO_TARGET_DISTANCE;
        for (HostileEntity hostile : hostiles) {
            nearestHostileDistance = Math.min(nearestHostileDistance, wolf.distanceTo(hostile));
        }

        BlockPos pos = wolf.getBlockPos();
        SpatialRegion region = currentRegion(wolf);

        return WolfContextSnapshot.builder()
                .ownerPresent(ownerPresent)
                .ownerDistance(ownerDistance)
                .wolfHealthRatio(healthRatio(wolf))
                .ownerHealthRatio(ownerHealthRatio)
                .hostileMobCount(hostiles.size())
                .nearestHostileDistance(nearestHostileDistance)
                .night(world.isNight())
                .underground(!world.isSkyVisible(pos))
                .ownerMoving(controller.isOwnerMoving())
                .regionFear(controller.agent().regionFear(region))
                .recentCombat(controller.recentCombat())
                .ownerUnderAttack(ownerPresent && owner.hurtTime > 0)
                .wolfUnderAttack(wolf.hurtTime > 0)
                .build();
    }

    /**
     * オオカミの現在位置を量子化した領域を返す。
     *
     * @param wolf 対象のオオカミ
     * @return 量子化された領域
     */
    public static SpatialRegion currentRegion(WolfEntity wolf) {
        return SpatialRegion.of(dimensionId(wolf.getWorld()), wolf.getX(), wolf.getY(), wolf.getZ());
    }

    /**
     * ディメンションの安定 ID を返す。
     *
     * @param world 対象のワールド
     * @return {@code "minecraft:overworld"} のような ID
     */
    public static String dimensionId(World world) {
        return world.getRegistryKey().getValue().toString();
    }

    /**
     * バイオームの安定 ID を返す。研究ログ用。
     *
     * @param wolf 対象のオオカミ
     * @return バイオーム ID。解決できなければ {@code "unknown"}
     */
    public static String biomeId(WolfEntity wolf) {
        return wolf.getWorld().getBiome(wolf.getBlockPos()).getKey()
                .map(key -> key.getValue().toString())
                .orElse("unknown");
    }

    private static double healthRatio(LivingEntity entity) {
        float max = entity.getMaxHealth();
        if (max <= 0.0f) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, entity.getHealth() / max));
    }
}
