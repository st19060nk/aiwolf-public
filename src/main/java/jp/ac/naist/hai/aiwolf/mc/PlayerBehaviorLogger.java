package jp.ac.naist.hai.aiwolf.mc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.log.ResearchLogger;
import jp.ac.naist.hai.aiwolf.study.PlayerLogJson;
import jp.ac.naist.hai.aiwolf.study.PlayerLogJson.PlayerEventType;
import jp.ac.naist.hai.aiwolf.study.StudySession;

/**
 * プレイヤーの行動を {@code player-events.jsonl} に記録する（Phase 21）。
 *
 * <p><strong>既存の検出の結果を写すだけで、検出の条件も研究イベントも変えない。</strong>
 * 撫で・餌は研究イベント PET / FEED が成立したときだけ、救援は CombatTracker が
 * PLAYER_PROTECTED_WOLF を確定したときだけ記録する。AI の状態・記憶・選好には何も渡さない。</p>
 *
 * <p>プレイヤーを表す項目は匿名の {@code subjectId} だけ（{@link WolfAiRegistry#anonymousId}）。
 * 名前・UUID・座標は書かない（{@link PlayerLogJson} が受け取らない）。</p>
 */
public final class PlayerBehaviorLogger {

    private PlayerBehaviorLogger() {
        throw new AssertionError("utility class");
    }

    /**
     * 撫でが成立した（クールダウン明けの PET）。
     *
     * @param player    プレイヤー
     * @param wolf      オオカミ
     * @param worldTime ワールド時刻
     */
    public static void onPet(PlayerEntity player, WolfEntity wolf, long worldTime) {
        log(PlayerEventType.PLAYER_PET, player, wolf, worldTime, null);
    }

    /**
     * 餌やりが成立した（研究イベント FEED と同じ条件）。
     *
     * @param player    プレイヤー
     * @param wolf      オオカミ
     * @param worldTime ワールド時刻
     */
    public static void onFeed(PlayerEntity player, WolfEntity wolf, long worldTime) {
        log(PlayerEventType.PLAYER_FEED, player, wolf, worldTime, null);
    }

    /**
     * プレイヤーが敵に実際にダメージを与えた（飛び道具は射手に帰属済み）。
     * プレイヤーの研究オオカミそれぞれについて 1 行（通常は 1 匹）。
     *
     * @param player     攻撃したプレイヤー
     * @param hostile    敵
     * @param damage     実際の損失
     * @param projectile 飛び道具による攻撃なら true
     * @param worldTime  ワールド時刻
     */
    public static void onAttackHostile(PlayerEntity player, LivingEntity hostile, double damage,
                                       boolean projectile, long worldTime) {
        for (Owned owned : researchWolvesOf(player)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("hostileType", CombatTracker.typeId(hostile));
            details.put("damage", damage);
            details.put("projectile", projectile);
            details.put("distanceToWolf", sameWorld(player, owned.wolf()) ? (double) player.distanceTo(owned.wolf()) : null);
            double hostileToWolf = hostile.getWorld() == owned.wolf().getWorld()
                    ? hostile.distanceTo(owned.wolf()) : Double.NaN;
            details.put("hostileDistanceToWolf", Double.isNaN(hostileToWolf) ? null : hostileToWolf);
            details.put("wolfNearby", !Double.isNaN(hostileToWolf) && hostileToWolf <= AiWolfParameters.HOSTILE_SCAN_RADIUS);
            details.put("wolfInCombatWithHostile", owned.controller().observer().combatTracker().tracks(hostile.getUuid()));
            details.put("hostileTargetsWolf", hostile instanceof MobEntity mob && mob.getTarget() == owned.wolf());
            write(PlayerEventType.PLAYER_ATTACK_HOSTILE, player, owned, worldTime, details);
        }
    }

    /**
     * プレイヤーが敵を倒した。<strong>戦闘の追跡を更新する前に</strong>呼ぶ（倒す直前の状態を記録するため）。
     *
     * @param player    倒したプレイヤー
     * @param hostile   敵
     * @param worldTime ワールド時刻
     */
    public static void onKilledHostile(PlayerEntity player, LivingEntity hostile, long worldTime) {
        for (Owned owned : researchWolvesOf(player)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("hostileType", CombatTracker.typeId(hostile));
            details.put("wolfWasInCombatWithHostile",
                    owned.controller().observer().combatTracker().tracks(hostile.getUuid()));
            details.put("wolfWasThreatened", hostile instanceof MobEntity mob && mob.getTarget() == owned.wolf());
            details.put("hostileDistanceToWolf", hostile.getWorld() == owned.wolf().getWorld()
                    ? (double) hostile.distanceTo(owned.wolf()) : null);
            write(PlayerEventType.PLAYER_KILLED_HOSTILE, player, owned, worldTime, details);
        }
    }

    /**
     * プレイヤー自身が実際にダメージを受けた。加害者は種類と役割だけを書く（ID は書かない）。
     *
     * @param player       プレイヤー
     * @param attacker     加害者（{@code null} = 環境）
     * @param damage       実際の損失
     * @param worldTime    ワールド時刻
     */
    public static void onPlayerDamaged(PlayerEntity player, Entity attacker, double damage, long worldTime) {
        String role = attacker == null ? "environment"
                : CombatTracker.isHostile(attacker) ? "hostile"
                : attacker instanceof WolfEntity ? "wolf"
                : attacker instanceof PlayerEntity ? "player" : "other";
        for (Owned owned : researchWolvesOf(player)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("damage", damage);
            details.put("attackerRole", role);
            details.put("attackerType", attacker == null ? null : CombatTracker.typeId(attacker));
            write(PlayerEventType.PLAYER_DAMAGED, player, owned, worldTime, details);
        }
    }

    /**
     * 救援が確定した。{@code WolfEventObserver} が研究イベント PLAYER_PROTECTED_WOLF を発行したのと
     * 同じ時点・同じ判定で呼ばれる（意味論は CombatEpisode のまま）。飼い主がオフラインでも記録する。
     *
     * @param wolf      オオカミ
     * @param agent     オオカミの AI
     * @param inCombat  オオカミが他の戦闘を追跡中なら true
     * @param combatId  戦闘 ID
     * @param hostileType 敵の種類
     * @param worldTime ワールド時刻
     */
    public static void onProtectedWolf(WolfEntity wolf, WolfAiAgent agent, boolean inCombat, String combatId,
                                       String hostileType, long worldTime) {
        LivingEntity owner = wolf.getOwner();
        PlayerEntity player = owner instanceof PlayerEntity p ? p : null;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("combatId", combatId);
        details.put("hostileType", hostileType);
        PlayerLogJson.Relation relation = relation(player, wolf, inCombat);
        ResearchLogger.logPlayerEvent(PlayerLogJson.event(PlayerEventType.PLAYER_PROTECTED_WOLF,
                new PlayerLogJson.Subjects(agent.ownerResearchId(), WolfAiRegistry.wolfSubjectId(wolf.getUuid()),
                        agent.condition()),
                StudySession.current(), worldTime, System.currentTimeMillis(), relation, details));
    }

    // ------------------------------------------------------------------

    /** プレイヤーの研究オオカミ（登録済み・ロード済み）。 */
    record Owned(WolfEntity wolf, WolfAiController controller) {
    }

    private static void log(PlayerEventType type, PlayerEntity player, WolfEntity wolf, long worldTime,
                            Map<String, Object> details) {
        WolfAiRegistry.find(wolf).ifPresent(controller ->
                write(type, player, new Owned(wolf, controller), worldTime, details));
    }

    private static void write(PlayerEventType type, PlayerEntity player, Owned owned, long worldTime,
                              Map<String, Object> details) {
        WolfAiAgent agent = owned.controller().agent();
        boolean inCombat = owned.controller().observer().combatTracker().openEpisodeCount() > 0;
        ResearchLogger.logPlayerEvent(PlayerLogJson.event(type,
                new PlayerLogJson.Subjects(WolfAiRegistry.anonymousId(player.getUuid()),
                        WolfAiRegistry.wolfSubjectId(owned.wolf().getUuid()), agent.condition()),
                StudySession.current(), worldTime, System.currentTimeMillis(),
                relation(player, owned.wolf(), inCombat), details));
    }

    /**
     * プレイヤーとオオカミの関係を測る。
     *
     * @param player   プレイヤー（{@code null} = オフライン）
     * @param wolf     オオカミ
     * @param inCombat 戦闘中か
     * @return 関係
     */
    static PlayerLogJson.Relation relation(PlayerEntity player, WolfEntity wolf, boolean inCombat) {
        Double distance = player != null && sameWorld(player, wolf) ? (double) player.distanceTo(wolf) : null;
        Double playerHealth = player == null ? null : ratio(player.getHealth(), player.getMaxHealth());
        return new PlayerLogJson.Relation(distance, ratio(wolf.getHealth(), wolf.getMaxHealth()), playerHealth,
                hostileCount(wolf), inCombat);
    }

    /** オオカミの周り（ContextBuilder と同じ半径）の生きている敵の数。 */
    static int hostileCount(WolfEntity wolf) {
        double size = AiWolfParameters.HOSTILE_SCAN_RADIUS * 2.0;
        return wolf.getWorld().getEntitiesByClass(HostileEntity.class, Box.of(wolf.getPos(), size, size, size),
                LivingEntity::isAlive).size();
    }

    /**
     * プレイヤーが飼い主の研究オオカミのうち、プレイヤーと同じワールドにロードされているもの。
     * 飼い主の照合は匿名 ID 同士で行う（UUID を比較しない経路と同じ）。
     */
    static List<Owned> researchWolvesOf(PlayerEntity player) {
        List<Owned> owned = new ArrayList<>();
        if (!(player.getWorld() instanceof ServerWorld world)) {
            return owned;
        }
        String subjectId = WolfAiRegistry.anonymousId(player.getUuid());
        for (Map.Entry<UUID, WolfAiController> entry : WolfAiRegistry.all().entrySet()) {
            if (subjectId == null || !subjectId.equals(entry.getValue().agent().ownerResearchId())) {
                continue;
            }
            if (world.getEntity(entry.getKey()) instanceof WolfEntity wolf && wolf.isAlive()) {
                owned.add(new Owned(wolf, entry.getValue()));
            }
        }
        return owned;
    }

    private static boolean sameWorld(Entity a, Entity b) {
        return a.getWorld() == b.getWorld();
    }

    private static double ratio(float value, float max) {
        return max <= 0.0f ? 0.0 : Math.max(0.0, Math.min(1.0, value / max));
    }
}
