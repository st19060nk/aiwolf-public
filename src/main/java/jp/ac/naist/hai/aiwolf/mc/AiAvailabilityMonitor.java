package jp.ac.naist.hai.aiwolf.mc;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import jp.ac.naist.hai.aiwolf.log.ResearchLogger;
import jp.ac.naist.hai.aiwolf.study.AiAvailability;
import jp.ac.naist.hai.aiwolf.study.AiAvailabilityLogJson;
import jp.ac.naist.hai.aiwolf.study.StudySession;

/**
 * 研究 AI が決定できない区間を {@code ai-availability.jsonl} に記録する（Phase 22）。
 *
 * <p>1 秒（{@code PLAYER_SAMPLE_INTERVAL_TICKS}）に 1 回、登録済みの個体を調べ、
 * 状態が変わったときだけ 1 行書く（ずっと止まっていても毎回は書かない）。
 * 個体の状態は読むだけで変更しない。</p>
 */
public final class AiAvailabilityMonitor {

    private static final Map<UUID, AiAvailability.Tracker> TRACKERS = new HashMap<>();

    private AiAvailabilityMonitor() {
        throw new AssertionError("utility class");
    }

    /**
     * サーバ tick ごとに呼ばれる。サンプラーより先に呼ぶ（サンプルに最新の状態を載せるため）。
     *
     * @param server サーバ
     */
    public static void tick(MinecraftServer server) {
        if (!PlayerStateSampler.isSampleTick(server.getTicks())) {
            return;
        }
        long worldTime = server.getOverworld().getTime();
        for (Map.Entry<UUID, WolfAiController> entry : WolfAiRegistry.all().entrySet()) {
            WolfEntity wolf = find(server, entry.getKey());
            WolfAiController controller = entry.getValue();
            long time = wolf == null ? worldTime : wolf.getWorld().getTime();
            Optional<AiAvailability.Reason> reason = wolf == null
                    ? AiAvailability.classify(false, false, false, false, controller.lastDecisionTick(), time)
                    : AiAvailability.classify(true, wolf.isTamed(), wolf.isInSittingPose(), wolf.isLeashed(),
                            controller.lastDecisionTick(), time);
            AiAvailability.Tracker tracker = TRACKERS.computeIfAbsent(entry.getKey(), key -> new AiAvailability.Tracker());
            tracker.update(reason.orElse(null), time).ifPresent(transition ->
                    ResearchLogger.logAiAvailability(AiAvailabilityLogJson.toJsonLine(transition,
                            controller.agent().ownerResearchId(), WolfAiRegistry.wolfSubjectId(entry.getKey()),
                            controller.agent().condition(), StudySession.current(), time,
                            System.currentTimeMillis(), controller.lastDecisionTick())));
        }
    }

    /**
     * 個体の現在の状態（サンプルに載せる）。
     *
     * @param wolfUuid 個体
     * @return {@code ACTIVE}、{@code SUSPENDED:SITTING} など。まだ調べていなければ {@code null}
     */
    public static String statusOf(UUID wolfUuid) {
        AiAvailability.Tracker tracker = TRACKERS.get(wolfUuid);
        if (tracker == null) {
            return null;
        }
        return tracker.current() == null ? "ACTIVE" : "SUSPENDED:" + tracker.current().name();
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

    /** サーバ停止時の後始末。 */
    public static void clear() {
        TRACKERS.clear();
    }
}
