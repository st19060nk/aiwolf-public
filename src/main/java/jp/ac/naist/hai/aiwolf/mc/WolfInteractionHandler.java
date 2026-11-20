package jp.ac.naist.hai.aiwolf.mc;

import java.util.Locale;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;

/**
 * プレイヤーとオオカミの直接的なやりとりを出来事へ変換する。
 *
 * <p>Minecraft には「撫でる」動作が存在しないため、
 * <strong>スニーク + 素手 + 自分の飼い犬を右クリック</strong>を PET として扱う。
 * スニークを条件にしているのは、通常の右クリック（お座りの切り替え）を奪わないためである。</p>
 *
 * <p>連打による無制限の関係性上昇を防ぐため、PET には
 * {@link AiWolfParameters#PET_COOLDOWN_TICKS} のクールダウンがある。</p>
 *
 * <p>デバッグ表示が有効なプレイヤーには、PET にならなかった理由を返す。
 * 「撫でたつもりが条件を満たしていない」という取りこぼしは実験データの欠落に直結するため、
 * 実験者が事前に操作を確認できるようにしてある。</p>
 */
public final class WolfInteractionHandler {

    /** PET 時に出すハートの数。 */
    private static final int PET_PARTICLE_COUNT = 4;

    /** パーティクルの広がり。 */
    private static final double PET_PARTICLE_SPREAD = 0.4;

    private WolfInteractionHandler() {
        throw new AssertionError("utility class");
    }

    /**
     * エンティティへの右クリックを処理する。
     *
     * @param player 操作したプレイヤー
     * @param world  ワールド
     * @param hand   使用した手
     * @param entity 対象エンティティ
     * @return PET として消費したなら {@link ActionResult#SUCCESS}、それ以外は {@link ActionResult#PASS}
     */
    public static ActionResult onUseEntity(PlayerEntity player, World world, Hand hand, Entity entity) {
        return onUseEntity(player, world, hand, entity, false);
    }

    /**
     * エンティティへの右クリック。
     *
     * <p><strong>1 回の右クリックで、サーバではこのコールバックが 2 回呼ばれる。</strong>クライアントは
     * {@code INTERACT_AT}（位置つき）と {@code INTERACT} の 2 つのパケットを送り、Fabric はその両方で
     * {@code UseEntityCallback} を発火する。以前は両方で処理していたため、餌やり 1 回が FEED 2 回として
     * 記録されていた（撫ではクールダウンで 1 回に抑えられていた）。バニラが実際に餌を食べさせる・お座りを
     * 切り替えるのは {@code INTERACT} 側なので、そちらだけで処理し、{@code INTERACT_AT} は素通しする。</p>
     *
     * @param atLocation {@code INTERACT_AT}（位置つき）のコールバックなら true
     */
    public static ActionResult onUseEntity(PlayerEntity player, World world, Hand hand, Entity entity,
                                           boolean atLocation) {
        if (!handles(world.isClient(), hand == Hand.MAIN_HAND, atLocation)) {
            return ActionResult.PASS;
        }
        if (!(entity instanceof WolfEntity wolf) || !wolf.isTamed()) {
            return ActionResult.PASS;
        }
        if (!player.getUuid().equals(wolf.getOwnerUuid())) {
            return ActionResult.PASS;
        }

        WolfAiController controller = WolfAiRegistry.getOrCreate(wolf);
        long worldTime = world.getTime();
        ItemStack stack = player.getStackInHand(hand);

        // スニークはフラグとポーズのどちらでも認める。クライアントの設定や
        // 乗り物に乗っている状態で、片方しか立たないことがあるため。
        boolean sneaking = player.isSneaking() || player.isInSneakingPose();
        boolean emptyHand = stack.isEmpty();

        reportInteraction(player, sneaking, emptyHand, controller, worldTime);

        if (sneaking && emptyHand) {
            boolean accepted = controller.observer().tryPet(wolf, controller.agent(), worldTime);
            if (accepted) {
                PlayerBehaviorLogger.onPet(player, wolf, worldTime);
            }
            if (accepted && world instanceof ServerWorld serverWorld) {
                serverWorld.spawnParticles(ParticleTypes.HEART,
                        wolf.getX(), wolf.getBodyY(1.0), wolf.getZ(),
                        PET_PARTICLE_COUNT,
                        PET_PARTICLE_SPREAD, PET_PARTICLE_SPREAD, PET_PARTICLE_SPREAD, 0.0);
            }
            // お座りの切り替えへ落とさないよう、ここで操作を消費する。
            return ActionResult.SUCCESS;
        }

        if (!stack.isEmpty() && wolf.isBreedingItem(stack) && wolf.getHealth() < wolf.getMaxHealth()) {
            controller.observer().feed(wolf, controller.agent(), worldTime);
            PlayerBehaviorLogger.onFeed(player, wolf, worldTime);
        }
        // 餌やり・お座りの切り替えはバニラの処理に任せる。
        return ActionResult.PASS;
    }

    /**
     * デバッグ表示が有効なプレイヤーへ、判定の内訳を返す。
     *
     * <p>通常プレイ中のプレイヤーには何も表示しない。内部状態を見せると
     * 個体性の判断に交絡が生じるためである。</p>
     */
    /**
     * このコールバックを処理するか。1 回の右クリックで来る 2 つのコールバック（INTERACT_AT と INTERACT）の
     * うち、サーバ側・メインハンドの INTERACT だけを処理する。
     *
     * @param clientSide クライアント側なら true
     * @param mainHand   メインハンドなら true
     * @param atLocation INTERACT_AT（位置つき）なら true
     * @return 処理するなら true
     */
    public static boolean handles(boolean clientSide, boolean mainHand, boolean atLocation) {
        return !clientSide && mainHand && !atLocation;
    }

    private static void reportInteraction(PlayerEntity player, boolean sneaking, boolean emptyHand,
                                          WolfAiController controller, long worldTime) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)
                || !DebugDisplay.isEnabled(serverPlayer.getUuid())) {
            return;
        }
        boolean cooldownReady = controller.observer().isPetReady(worldTime);
        String message = String.format(Locale.ROOT,
                "[AIWolf] PET判定: sneaking=%s emptyHand=%s cooldownReady=%s -> %s",
                sneaking, emptyHand, cooldownReady,
                sneaking && emptyHand ? (cooldownReady ? "PET 成立" : "クールダウン中") : "条件未達");
        serverPlayer.sendMessage(Text.literal(message)
                .formatted(sneaking && emptyHand && cooldownReady ? Formatting.GREEN : Formatting.YELLOW), false);
    }
}
