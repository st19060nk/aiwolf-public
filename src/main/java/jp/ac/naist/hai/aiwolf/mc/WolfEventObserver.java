package jp.ac.naist.hai.aiwolf.mc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.util.math.Box;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.agent.EventOutcome;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.OwnerPresence;
import jp.ac.naist.hai.aiwolf.event.CombatEpisode;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.log.EventLogJson;
import jp.ac.naist.hai.aiwolf.log.ResearchLogger;
import jp.ac.naist.hai.aiwolf.event.DamageAttribution;
import jp.ac.naist.hai.aiwolf.event.DamageSeverity;
import jp.ac.naist.hai.aiwolf.runtime.DamageAccumulator;
import jp.ac.naist.hai.aiwolf.runtime.SeparationTracker;

/**
 * 出来事の検出。個体ごとに 1 つ持つ。
 *
 * <p>検出ロジックをここへ集約し、行動決定や状態更新から分離している。
 * <strong>全 tick を記録してはならない</strong>ため、ほとんどの検出は決定のタイミング
 * （既定 40 ticks ごと）に前回との差分を見る形で行う。意味のある変化があったときだけ
 * {@link WolfAiAgent#onEvent} を呼ぶ。</p>
 *
 * <p>PET と FEED だけはプレイヤーの操作に対する即時の反応が必要なので、
 * {@link WolfInteractionHandler} から直接呼ばれる。</p>
 *
 * <h2>どの状態を永続化するか</h2>
 *
 * <p>Observer の内部状態は 2 種類に分かれる。区別せずに全部保存すると、
 * 再起動直後に存在しない変化を検出してしまう。</p>
 *
 * <p><strong>A. 長期的な経験として保存するもの</strong>（{@link PersistedState}）</p>
 * <ul>
 *   <li>{@code visitedBiomes} — 「初めて見た」は一度きりの経験である。消えると
 *       再起動後に同じバイオームで NEW_BIOME が再発火し、長期育成実験で
 *       好奇心が不当に上がり続ける</li>
 *   <li>{@code lastPetTick} / {@code lastSleepEventTick} — クールダウン。消えると
 *       再起動するだけでクールダウンを回避して関係性を稼げてしまう</li>
 *   <li>{@code separationReported} — 分離を報告済みかどうか。これが消えると、
 *       離れている間にセーブして再開したときに REUNION を取り逃がす</li>
 *   <li>{@code combatSequence} — 戦闘 ID の採番カウンタ。0 へ戻ると同じ個体の
 *       別セッションの戦闘が同じ combatId を持ち、解析で無関係な 2 件が結合される</li>
 * </ul>
 *
 * <p><strong>B. 一時的なランタイム状態なので保存しないもの</strong></p>
 * <ul>
 *   <li>溜まっているダメージ — 次の決定までの一時的な集計。ワールドを閉じる前の
 *       被弾を再開後の出来事として記録するのは誤りなので、保存しない</li>
 *   <li>{@code hostilesPreviouslyPresent} — 次の観測で自然に取り直せる</li>
 *   <li>{@code ownerAbsentSinceTick} — 世界が閉じている間の時間は
 *       インタラクションの不在ではない。再開時に測り直す</li>
 *   <li>戦闘の追跡状態 — 戦闘は再起動を跨がない</li>
 *   <li>{@code safeExplorationStreak} — 連続した決定の数。再起動で連続は切れる</li>
 * </ul>
 *
 * <p>スレッド安全ではない。サーバスレッド上からのみ操作すること。</p>
 */
public final class WolfEventObserver {

    /** 種別名を覚えておく相手の上限。際限なく溜め込まないための歯止め。 */
    private static final int MAX_REMEMBERED_HOSTILE_TYPES = 32;

    private final Set<String> visitedBiomes = new HashSet<>();
    private final CombatTracker combatTracker;

    private final DamageAccumulator wolfDamage = new DamageAccumulator();
    private final DamageAccumulator ownerDamage = new DamageAccumulator();
    /** 研究ログへ種別名を添えるための、この区間に関わった相手の控え。 */
    private final Map<String, String> hostileTypes = new HashMap<>();
    private boolean hostilesPreviouslyPresent;
    private final SeparationTracker separationTracker = new SeparationTracker();
    private int safeExplorationStreak;
    private long lastPetTick = Long.MIN_VALUE;
    private long lastSleepEventTick = Long.MIN_VALUE;

    /**
     * 検出器を生成する。
     *
     * @param wolfId 個体 ID。combat ID の採番に使う
     */
    public WolfEventObserver(String wolfId) {
        this.combatTracker = new CombatTracker(wolfId);
    }

    /** @return combat episode の追跡器 */
    public CombatTracker combatTracker() {
        return combatTracker;
    }

    /**
     * 永続化する Observer 状態。分類の理由はクラスの Javadoc を参照。
     *
     * @param visitedBiomes      訪問済みバイオームの ID
     * @param lastPetTick        最後に PET を受け付けたワールド時刻
     * @param lastSleepEventTick 最後に SLEEP_NEAR_PLAYER を記録したワールド時刻
     * @param separationReported 長期分離を報告済みか
     * @param combatSequence     次に採番する戦闘の連番
     */
    public record PersistedState(List<String> visitedBiomes,
                                 long lastPetTick,
                                 long lastSleepEventTick,
                                 boolean separationReported,
                                 long combatSequence) {

        /** コンパクトコンストラクタ。リストを不変化する。 */
        public PersistedState {
            visitedBiomes = visitedBiomes == null ? List.of() : List.copyOf(visitedBiomes);
        }
    }

    /**
     * 永続化すべき状態を取り出す。
     *
     * @return 保存対象の状態
     */
    public PersistedState exportState() {
        return new PersistedState(List.copyOf(visitedBiomes), lastPetTick,
                lastSleepEventTick, separationTracker.separationReported(),
                combatTracker.combatSequence());
    }

    /**
     * 永続化した状態を書き戻す。一時的なランタイム状態は触らない。
     *
     * @param state 保存されていた状態
     */
    public void importState(PersistedState state) {
        visitedBiomes.clear();
        visitedBiomes.addAll(state.visitedBiomes());
        lastPetTick = state.lastPetTick();
        lastSleepEventTick = state.lastSleepEventTick();
        separationTracker.restore(state.separationReported());
        combatTracker.restoreCombatSequence(state.combatSequence());
    }

    /**
     * 決定のたびに呼ばれ、前回からの変化を調べて出来事を発行する。
     *
     * @param wolf       対象のオオカミ
     * @param controller 個体のランタイム
     * @param worldTime  現在のワールド時刻
     */
    public void observe(WolfEntity wolf, WolfAiController controller, long worldTime) {
        WolfAiAgent agent = controller.agent();
        LivingEntity owner = wolf.getOwner();
        // 距離を含めた唯一の定義を使う。同じワールドにいるだけで「そばにいる」と
        // みなすと、数百 block 離れていても飼い主の被弾が近接扱いになり、
        // 逆に長期分離が永久に成立しない。
        boolean ownerPresent = isOwnerNearby(wolf, owner);

        emitPendingWolfDamage(wolf, agent, worldTime);
        emitPendingOwnerDamage(wolf, agent, worldTime);
        detectThreatChanges(wolf, agent, worldTime);
        resolveCombat(wolf, agent, worldTime);
        detectSeparationAndReunion(wolf, agent, ownerPresent, worldTime);
        detectNewBiome(wolf, agent, worldTime);
        detectSleep(wolf, agent, owner, ownerPresent, worldTime);
        detectSafeExploration(wolf, controller, agent, owner, ownerPresent, worldTime);
    }

    /**
     * 撫でられたことを記録する。クールダウン中は何もしない。
     *
     * @param wolf      対象のオオカミ
     * @param agent     AI エージェント
     * @param worldTime 現在のワールド時刻
     * @return 実際に PET として受け付けたら true
     */
    public boolean tryPet(WolfEntity wolf, WolfAiAgent agent, long worldTime) {
        if (lastPetTick != Long.MIN_VALUE
                && worldTime - lastPetTick < AiWolfParameters.PET_COOLDOWN_TICKS) {
            return false;
        }
        lastPetTick = worldTime;
        emit(wolf, agent, WolfEventType.PET, worldTime);
        return true;
    }

    /**
     * 餌をもらったことを記録する。
     *
     * @param wolf      対象のオオカミ
     * @param agent     AI エージェント
     * @param worldTime 現在のワールド時刻
     */
    public void feed(WolfEntity wolf, WolfAiAgent agent, long worldTime) {
        emit(wolf, agent, WolfEventType.FEED, worldTime);
    }

    /** @return PET のクールダウンが明けていれば true */
    public boolean isPetReady(long worldTime) {
        return lastPetTick == Long.MIN_VALUE
                || worldTime - lastPetTick >= AiWolfParameters.PET_COOLDOWN_TICKS;
    }

    // ------------------------------------------------------------------
    // 個別の検出
    // ------------------------------------------------------------------

    /**
     * オオカミが実際にダメージを受けた瞬間に呼ばれる。
     *
     * <p>戦闘記録へ入れるのは<strong>1 発ごと・相手ごと</strong>である。研究イベントとしての
     * 発火はここでは行わず、{@link #emitPendingWolfDamage} が決定のたびに最大 1 件だけ出す。
     * 1 発ごとに発火させると、1 回の戦闘で 3 回噛まれた個体が 3 倍の速さで学習してしまう。</p>
     *
     * <p>加害者がいないダメージ（落下・溶岩・炎など）でも内部状態は更新するが、
     * 戦闘記録は作らない。相手のいない戦闘は結末が永遠に来ない。</p>
     *
     * @param wolf             対象のオオカミ
     * @param attacker         ダメージの原因となった主体（{@code null} 可）
     * @param actualDamage     実際に失われた耐久（health + absorption の減少）
     * @param healthRatioBefore 被弾前の HP 比率
     * @param healthRatioAfter  被弾後の HP 比率
     * @param fatal            この攻撃で死亡したか
     * @param worldTime        発生ワールド時刻
     * @return 対応する戦闘の ID。戦闘外なら {@code null}
     */
    public String onWolfDamaged(WolfEntity wolf, Entity attacker, double actualDamage,
                                double healthRatioBefore, double healthRatioAfter,
                                boolean fatal, long worldTime) {
        LivingEntity hostile = combatOpponent(attacker, wolf);
        wolfDamage.add(sourceKey(hostile), actualDamage, healthRatioAfter, worldTime);
        if (hostile == null) {
            return null;
        }
        combatTracker.onWolfDamaged(hostile, actualDamage, healthRatioAfter, worldTime);
        rememberHostileType(hostile);
        if (fatal) {
            // 致死攻撃は AFTER_DAMAGE では捉えられないため、ここで確実に閉じる。
            // これがないと「オオカミが死んだ」ことしか分からず、
            // どの敵にとどめを刺されたのかが研究データから落ちる。
            combatTracker.onWolfDied();
        }
        return combatTracker.combatIdFor(hostile);
    }

    /**
     * 飼い主が実際にダメージを受けた瞬間に呼ばれる。
     *
     * <p>呼び出し側が飼い主の近傍にいる個体だけへ配るので、ここでは距離を見ない。</p>
     *
     * @param wolf         対象のオオカミ
     * @param attacker     ダメージの原因となった主体（{@code null} 可）
     * @param actualDamage 実際に失われた耐久
     * @param worldTime    発生ワールド時刻
     * @return 対応する戦闘の ID。戦闘外なら {@code null}
     */
    public String onOwnerDamaged(WolfEntity wolf, Entity attacker, double actualDamage,
                                 long worldTime) {
        LivingEntity hostile = combatOpponent(attacker, null);
        ownerDamage.add(sourceKey(hostile), actualDamage, 1.0, worldTime);
        if (hostile == null) {
            return null;
        }
        // 飼い主の被弾を起点に戦闘を開始できる。敵が飼い主を先に殴る
        // 自然な流れで DEFEND_SUCCESS を成立させるために必要。
        combatTracker.onOwnerDamaged(hostile, actualDamage, healthRatio(wolf), worldTime);
        rememberHostileType(hostile);
        return combatTracker.combatIdFor(hostile);
    }

    /**
     * 溜まっているオオカミの被弾を、出来事として 1 件だけ発行する。
     *
     * <p>傷の重さは区間内で最も低かった HP 比率で決める。誰に殴られたかとは無関係に、
     * その区間でどこまで追い込まれたかが重さだからである。帰属先（どの相手の
     * せいとして記録するか）は {@link DamageAccumulator} の選択規則で決まる。</p>
     */
    private void emitPendingWolfDamage(WolfEntity wolf, WolfAiAgent agent, long worldTime) {
        wolfDamage.drain().ifPresent(drained -> {
            emit(wolf, agent,
                    DamageSeverity.classify(drained.lowestHealthRatio(), agent.currentBehavior()),
                    worldTime, damageMetadata(drained));
            safeExplorationStreak = 0;
        });
    }

    /**
     * 溜まっている飼い主の被弾を、出来事として 1 件だけ発行する。
     */
    private void emitPendingOwnerDamage(WolfEntity wolf, WolfAiAgent agent, long worldTime) {
        ownerDamage.drain().ifPresent(drained ->
                emit(wolf, agent, WolfEventType.PLAYER_DAMAGED_NEAR_WOLF, worldTime,
                        damageMetadata(drained)));
    }

    /**
     * 被弾の出来事へ添えるメタデータを組み立てる。
     *
     * <p>複数の相手から受けた区間では、どの相手を代表として選んだのかと、
     * 区間全体でどれだけ受けたのかの両方を残す。選択規則が効いた場面を
     * 研究ログから後から検証できるようにするためである。</p>
     */
    private Map<String, String> damageMetadata(DamageAccumulator.Drained drained) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(WolfEvent.META_DAMAGE_TOTAL, format(drained.totalDamage()));
        metadata.put(WolfEvent.META_DAMAGE_ATTRIBUTED, format(drained.selectedTotal()));
        metadata.put(WolfEvent.META_DAMAGE_SOURCES, Integer.toString(drained.sourceCount()));
        if (drained.environmental()) {
            metadata.put(WolfEvent.META_DAMAGE_ORIGIN, "environment");
            return metadata;
        }
        metadata.put(WolfEvent.META_DAMAGE_ORIGIN, "hostile");
        metadata.put(WolfEvent.META_HOSTILE_ID, drained.hostileId());
        String type = hostileTypes.get(drained.hostileId());
        if (type != null) {
            metadata.put(WolfEvent.META_HOSTILE_TYPE, type);
        }
        return metadata;
    }

    /**
     * 戦闘の相手として扱える加害者を返す。環境ダメージと自傷では {@code null} を返す。
     */
    private static LivingEntity combatOpponent(Entity attacker, Entity victim) {
        if (!DamageAttribution.isCombatOpponent(attacker, CombatTracker.isHostile(attacker),
                victim != null && attacker == victim)) {
            return null;
        }
        return attacker instanceof LivingEntity living ? living : null;
    }

    private static String sourceKey(LivingEntity hostile) {
        return hostile == null ? null : CombatTracker.shortId(hostile.getUuid());
    }

    private void rememberHostileType(LivingEntity hostile) {
        if (hostileTypes.size() < MAX_REMEMBERED_HOSTILE_TYPES) {
            hostileTypes.put(CombatTracker.shortId(hostile.getUuid()), CombatTracker.typeId(hostile));
        }
    }

    private static String format(double value) {
        return Double.toString(Math.round(value * 100.0) / 100.0);
    }

    /**
     * 敵性 MOB が現れたことを検出する。いる間ずっとではなく、現れた瞬間だけ記録する。
     */
    private void detectThreatChanges(WolfEntity wolf, WolfAiAgent agent, long worldTime) {
        boolean present = !nearbyHostiles(wolf).isEmpty();
        if (present && !hostilesPreviouslyPresent) {
            emit(wolf, agent, WolfEventType.MONSTER_ENCOUNTER, worldTime);
        }
        hostilesPreviouslyPresent = present;
    }

    /**
     * 戦闘の結末を解決する。
     *
     * <p>以前は「攻撃対象が変わり、新しい対象が生きていない」ことだけで成功と判定していたが、
     * Mob A から Mob B へ狙いが移る瞬間を戦闘成功と誤認しうる問題があった。
     * 現在は相手ごとに {@link CombatEpisode} を開き、誰が誰を攻撃し、
     * 相手が倒されたか、オオカミが生きているかを明示的に追う。</p>
     *
     * <p>1 つの戦闘からは最優先の 1 件しか発火しない。判定条件は
     * {@link CombatEpisode} の Javadoc にある。</p>
     */
    private void resolveCombat(WolfEntity wolf, WolfAiAgent agent, long worldTime) {
        LivingEntity target = CombatTracker.hostileTargetOf(wolf);
        if (target != null) {
            combatTracker.onWolfTargeting(target, healthRatio(wolf), worldTime);
        }
        for (CombatEpisode episode : combatTracker.drainFinishedEpisodes(worldTime)) {
            episode.resolve().ifPresent(type -> {
                Map<String, String> metadata = new HashMap<>();
                metadata.put(WolfEvent.META_COMBAT_ID, episode.combatId());
                metadata.put(WolfEvent.META_HOSTILE_TYPE, episode.hostileType());
                metadata.put(WolfEvent.META_HOSTILE_ID, episode.hostileId());
                metadata.put(WolfEvent.META_COMBAT_TRIGGER, episode.trigger().id());
                emit(wolf, agent, type, worldTime, metadata);
                if (type == WolfEventType.PLAYER_PROTECTED_WOLF) {
                    // 研究イベントと同じ判定・同じ時刻で、プレイヤー側のログにも残す。
                    PlayerBehaviorLogger.onProtectedWolf(wolf, agent, combatTracker.openEpisodeCount() > 0,
                            episode.combatId(), episode.hostileType(), worldTime);
                }
            });
        }
    }

    /**
     * 飼い主との長時間の分離と、その後の再会を検出する。
     */
    private void detectSeparationAndReunion(WolfEntity wolf, WolfAiAgent agent,
                                            boolean ownerPresent, long worldTime) {
        // 判定は Minecraft 非依存の SeparationTracker にある。ownerPresent には
        // 距離を含んだ定義（OwnerPresence）が入っている必要がある。同じワールドに
        // いるかどうかだけを渡すと、この状態機械は一度も分離を検出しない。
        separationTracker.update(ownerPresent, worldTime)
                .ifPresent(type -> emit(wolf, agent, type, worldTime));
    }

    /**
     * 初めて訪れたバイオームを検出する。
     */
    private void detectNewBiome(WolfEntity wolf, WolfAiAgent agent, long worldTime) {
        String biome = ContextBuilder.biomeId(wolf);
        if (visitedBiomes.add(biome) && visitedBiomes.size() > 1) {
            // 最初に登録されるバイオームは「初めて見た」ではなく出発点なので数えない。
            emit(wolf, agent, WolfEventType.NEW_BIOME, worldTime);
        }
    }

    /**
     * 飼い主のそばで休んだことを検出する。
     */
    private void detectSleep(WolfEntity wolf, WolfAiAgent agent, LivingEntity owner,
                             boolean ownerPresent, long worldTime) {
        if (!ownerPresent || !owner.isSleeping()) {
            return;
        }
        if (wolf.distanceTo(owner) > AiWolfParameters.SOLO_EXPLORATION_DISTANCE) {
            return;
        }
        if (lastSleepEventTick != Long.MIN_VALUE
                && worldTime - lastSleepEventTick < AiWolfParameters.SLEEP_EVENT_COOLDOWN_TICKS) {
            return;
        }
        lastSleepEventTick = worldTime;
        emit(wolf, agent, WolfEventType.SLEEP_NEAR_PLAYER, worldTime);
    }

    /**
     * 単独での探索を無事に続けられたことを検出する。
     *
     * <p>飼い主から離れて探索している状態が一定回数続き、その間に傷つかなかった場合に
     * 1 回だけ記録する。これが繰り返されると自律性と探索選好が育つ。</p>
     */
    private void detectSafeExploration(WolfEntity wolf, WolfAiController controller, WolfAiAgent agent,
                                       LivingEntity owner, boolean ownerPresent, long worldTime) {
        boolean exploringAlone = controller.currentBehavior() == BehaviorType.EXPLORE_NEARBY
                && (!ownerPresent || wolf.distanceTo(owner) >= AiWolfParameters.SOLO_EXPLORATION_DISTANCE);

        if (!exploringAlone) {
            safeExplorationStreak = 0;
            return;
        }
        safeExplorationStreak++;
        if (safeExplorationStreak >= AiWolfParameters.SAFE_EXPLORATION_STREAK) {
            emit(wolf, agent, WolfEventType.SAFE_SOLO_EXPLORATION, worldTime);
            safeExplorationStreak = 0;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * 出来事を組み立ててエージェントへ渡す。
     *
     * @param wolf      対象のオオカミ
     * @param agent     AI エージェント
     * @param type      出来事の種別
     * @param worldTime 現在のワールド時刻
     */
    private static void emit(WolfEntity wolf, WolfAiAgent agent, WolfEventType type, long worldTime) {
        emit(wolf, agent, type, worldTime, Map.of());
    }

    /**
     * 追跡用メタデータを添えて出来事を発行する。
     *
     * @param wolf      対象のオオカミ
     * @param agent     AI エージェント
     * @param type      出来事の種別
     * @param worldTime 現在のワールド時刻
     * @param metadata  combatId / hostileType など、研究ログで原因を辿るための情報
     */
    private static void emit(WolfEntity wolf, WolfAiAgent agent, WolfEventType type,
                             long worldTime, Map<String, String> metadata) {
        LivingEntity owner = wolf.getOwner();
        double ownerHealth = owner == null ? 1.0 : healthRatio(owner);
        WolfEvent event = WolfEvent.of(type, worldTime,
                ContextBuilder.dimensionId(wolf.getWorld()), ContextBuilder.biomeId(wolf),
                wolf.getX(), wolf.getY(), wolf.getZ(),
                WolfAiController.ownerResearchId(wolf),
                healthRatio(wolf), ownerHealth, metadata);

        EventOutcome outcome = agent.onEvent(event);

        // 適用の前後を両方残す。適用後の値だけでは、飽和しかけた個体で
        // 「同じ出来事なのに実際にはほとんど動いていない」ことを見逃す。
        ResearchLogger.logEvent(EventLogJson.toJsonLine(agent.wolfId(), agent.ownerResearchId(),
                agent.condition(), event, outcome, System.currentTimeMillis(),
                new EventLogJson.Extras(WolfAiRegistry.wolfSubjectId(wolf.getUuid()),
                        WolfAiRegistry.experimentSeed(), agent.random().seed())));
    }

    /**
     * 飼い主が近傍にいるかを判定する。距離の定義は {@link OwnerPresence} 1 か所だけにある。
     *
     * @param wolf  対象のオオカミ
     * @param owner 飼い主（{@code null} 可）
     * @return 近傍にいれば true
     */
    static boolean isOwnerNearby(WolfEntity wolf, LivingEntity owner) {
        if (owner == null) {
            return false;
        }
        boolean sameWorld = owner.getWorld() == wolf.getWorld();
        double distance = sameWorld ? wolf.distanceTo(owner) : Double.NaN;
        return OwnerPresence.isNearby(true, owner.isAlive(), sameWorld, distance);
    }

    private static float healthRatio(LivingEntity entity) {
        float max = entity.getMaxHealth();
        return max <= 0.0f ? 1.0f : Math.max(0.0f, Math.min(1.0f, entity.getHealth() / max));
    }

    private static List<HostileEntity> nearbyHostiles(WolfEntity wolf) {
        double radius = AiWolfParameters.HOSTILE_SCAN_RADIUS;
        return wolf.getWorld().getEntitiesByClass(HostileEntity.class,
                Box.of(wolf.getPos(), radius * 2.0, radius * 2.0, radius * 2.0),
                hostile -> hostile.isAlive());
    }
}
