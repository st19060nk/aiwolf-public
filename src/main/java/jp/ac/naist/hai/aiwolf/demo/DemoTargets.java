package jp.ac.naist.hai.aiwolf.demo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import jp.ac.naist.hai.aiwolf.mc.DebugDisplay;

/**
 * デモ用の対象個体の決定（{@code /wolfai select}・注視中の個体）。
 *
 * <p>個体を選ぶだけで、個体の状態には触れない。</p>
 */
public final class DemoTargets {

    /** 注視判定に使う最大距離 [blocks]。 */
    private static final double LOOK_RANGE = 24.0;

    /** 視線とオオカミ方向の内積がこれ以上なら「見ている」とみなす（約 14 度以内）。 */
    private static final double LOOK_DOT_THRESHOLD = 0.97;

    /** プレイヤー UUID → 選択中の個体 UUID。メモリ上だけに持つ。 */
    private static final Map<UUID, UUID> SELECTED = new HashMap<>();

    private DemoTargets() {
        throw new AssertionError("utility class");
    }

    /**
     * 個体を選択する。
     *
     * @param player プレイヤー
     * @param wolf   選択する個体（{@code null} なら選択解除）
     */
    public static void select(ServerPlayerEntity player, WolfEntity wolf) {
        if (wolf == null) {
            SELECTED.remove(player.getUuid());
        } else {
            SELECTED.put(player.getUuid(), wolf.getUuid());
        }
    }

    /**
     * 選択中の個体を返す。選択していない・ロードされていないなら {@code null}。
     *
     * @param player プレイヤー
     * @return 個体
     */
    public static WolfEntity selected(ServerPlayerEntity player) {
        UUID wolfUuid = SELECTED.get(player.getUuid());
        if (wolfUuid == null) {
            return null;
        }
        Entity entity = player.getServerWorld().getEntity(wolfUuid);
        return entity instanceof WolfEntity wolf && wolf.isAlive() ? wolf : null;
    }

    /** @return 個体を選択しているなら true（ロード済みかは問わない） */
    public static boolean hasSelection(ServerPlayerEntity player) {
        return SELECTED.containsKey(player.getUuid());
    }

    /**
     * デバッグ表示の対象：選択中の個体、なければ見ている飼い慣らされた個体。
     *
     * @param player プレイヤー
     * @return 個体。なければ {@code null}
     */
    public static WolfEntity overlayTarget(ServerPlayerEntity player) {
        WolfEntity selected = selected(player);
        return selected != null ? selected : lookedAt(player);
    }

    /**
     * コマンドの対象：選択中 → 見ている個体 → 最も近い自分の飼い犬、の順。
     *
     * @param player プレイヤー
     * @return 個体。なければ {@code null}
     */
    public static WolfEntity commandTarget(ServerPlayerEntity player) {
        WolfEntity target = overlayTarget(player);
        return target != null ? target : DebugDisplay.findNearestOwnWolf(player);
    }

    /**
     * プレイヤーが見ている飼い慣らされたオオカミを返す。
     *
     * @param player プレイヤー
     * @return 個体。なければ {@code null}
     */
    public static WolfEntity lookedAt(ServerPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0f).normalize();
        List<WolfEntity> wolves = player.getWorld().getEntitiesByClass(WolfEntity.class,
                player.getBoundingBox().expand(LOOK_RANGE), wolf -> wolf.isAlive() && wolf.isTamed());
        WolfEntity best = null;
        double bestDot = LOOK_DOT_THRESHOLD;
        for (WolfEntity wolf : wolves) {
            Vec3d toWolf = wolf.getPos().add(0.0, wolf.getHeight() * 0.5, 0.0).subtract(eye);
            double distance = toWolf.length();
            if (distance < 1.0e-6 || distance > LOOK_RANGE) {
                continue;
            }
            double dot = look.dotProduct(toWolf.multiply(1.0 / distance));
            if (dot >= bestDot && player.canSee(wolf)) {
                best = wolf;
                bestDot = dot;
            }
        }
        return best;
    }

    /** すべての選択を解除する。 */
    public static void clear() {
        SELECTED.clear();
    }
}
