package jp.ac.naist.hai.aiwolf.mc;

import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.log.ResearchLogger;
import jp.ac.naist.hai.aiwolf.study.MotionBaseline;
import jp.ac.naist.hai.aiwolf.study.PlayerLogJson;
import jp.ac.naist.hai.aiwolf.study.StudySession;

/**
 * プレイヤーとオオカミの関係を一定間隔でサンプルし、{@code player-samples.jsonl} に書く（Phase 21）。
 *
 * <ul>
 *   <li>間隔は {@link AiWolfParameters#PLAYER_SAMPLE_INTERVAL_TICKS}（既定 20 ticks = 1 秒）。毎 tick は書かない</li>
 *   <li><strong>研究オオカミ 1 匹につき 1 行</strong>。オオカミの飼い主は 1 人なので、同じ組を二重に書かない</li>
 *   <li>セッション中だけ記録する（Human Study の区間外で容量を使わないため）</li>
 *   <li>飼い主がオフラインのとき、オオカミがロードされていないときは書かない</li>
 *   <li>座標は書かず、距離と前回からの移動量だけを書く</li>
 * </ul>
 *
 * <p>AI の状態・記憶・選好は読むだけで、何も変更しない。</p>
 */
public final class PlayerStateSampler {

    /**
     * 前回のサンプルの位置（オオカミの UUID → 位置）。メモリ上だけ。
     * 前回が別のセッション・長い空白・別のワールドなら移動量を計算しない（Phase 25 P0-4、{@link MotionBaseline}）。
     */
    private static final MotionBaseline<Positions> PREVIOUS = new MotionBaseline<>();

    /** セッションの開始・終了・リセットで前回位置を消す処理（{@link StudySession} に登録する）。 */
    private static final Runnable CLEAR_ON_BOUNDARY = PlayerStateSampler::clear;

    private record Positions(Vec3d wolf, Vec3d player, Object playerWorld) {
    }

    private PlayerStateSampler() {
        throw new AssertionError("utility class");
    }

    /**
     * この tick にサンプルするか。
     *
     * @param serverTicks サーバの tick 数
     * @return サンプルする tick なら true
     */
    public static boolean isSampleTick(long serverTicks) {
        return serverTicks % AiWolfParameters.PLAYER_SAMPLE_INTERVAL_TICKS == 0;
    }

    /**
     * サーバ tick ごとに呼ばれる。
     *
     * @param server サーバ
     */
    public static void tick(MinecraftServer server) {
        if (!isSampleTick(server.getTicks())) {
            return;
        }
        StudySession.Active session = StudySession.current();
        if (session == null) {
            return;
        }
        for (Map.Entry<UUID, WolfAiController> entry : WolfAiRegistry.all().entrySet()) {
            WolfEntity wolf = find(server, entry.getKey());
            if (wolf == null || wolf.getOwnerUuid() == null) {
                continue;
            }
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(wolf.getOwnerUuid());
            if (player == null) {
                continue;
            }
            sample(player, wolf, entry.getValue(), session);
        }
    }

    private static void sample(ServerPlayerEntity player, WolfEntity wolf, WolfAiController controller,
                               StudySession.Active session) {
        long worldTime = wolf.getWorld().getTime();
        boolean sameWorld = player.getWorld() == wolf.getWorld();
        Positions now = new Positions(wolf.getPos(), player.getPos(), player.getWorld());
        MotionBaseline.Result<Positions> baseline = PREVIOUS.observe(wolf.getUuid(), session.sessionId(), worldTime,
                wolf.getWorld(), player.getUuid(), now);
        PlayerLogJson.Motion motion;
        if (baseline.hasBaseline()) {
            Positions before = baseline.previous();
            // プレイヤーがディメンションを移っていれば、プレイヤーの移動量だけは計算しない。
            Double playerMoved = sameWorld && before.playerWorld() == player.getWorld()
                    ? player.getPos().distanceTo(before.player()) : null;
            motion = new PlayerLogJson.Motion(baseline.intervalTicks(), playerMoved,
                    wolf.getPos().distanceTo(before.wolf()));
        } else {
            motion = PlayerLogJson.Motion.none(baseline.missing());
        }

        boolean inCombat = controller.observer().combatTracker().openEpisodeCount() > 0;
        BehaviorType behavior = controller.currentBehavior();
        ResearchLogger.logPlayerSample(PlayerLogJson.sample(
                new PlayerLogJson.Subjects(WolfAiRegistry.anonymousId(player.getUuid()),
                        WolfAiRegistry.wolfSubjectId(wolf.getUuid()), controller.agent().condition()),
                session, worldTime, System.currentTimeMillis(),
                PlayerBehaviorLogger.relation(player, wolf, inCombat), motion,
                behavior == null ? null : behavior.id(), AiAvailabilityMonitor.statusOf(wolf.getUuid())));
    }

    private static WolfEntity find(MinecraftServer server, UUID uuid) {
        for (ServerWorld world : server.getWorlds()) {
            Entity entity = world.getEntity(uuid);
            if (entity instanceof WolfEntity wolf && wolf.isAlive()) {
                return wolf;
            }
        }
        return null;
    }

    /** 前回位置をすべて消す（セッションの開始・終了、サーバの起動・停止）。 */
    public static void clear() {
        PREVIOUS.clear();
    }

    /** セッションの境界で前回位置を消すよう登録する（mod の初期化時に 1 回）。 */
    public static void registerSessionBoundaryReset() {
        StudySession.addBoundaryListener(CLEAR_ON_BOUNDARY);
    }

    /**
     * 飼い主が切断したとき、その飼い主のオオカミの前回位置を消す（再接続後の最初のサンプルは基準なし）。
     *
     * @param player 切断したプレイヤーの UUID
     */
    public static void forgetOwner(UUID player) {
        PREVIOUS.forgetOwner(player);
    }
}
