package jp.ac.naist.hai.aiwolf.mc;

import java.util.Objects;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.util.math.Vec3d;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.demo.BehaviorHistory;
import jp.ac.naist.hai.aiwolf.log.DecisionLogJson;
import jp.ac.naist.hai.aiwolf.log.ResearchLogger;
import jp.ac.naist.hai.aiwolf.runtime.DecisionTiming;

/**
 * 1 個体分の Minecraft 側ランタイム。
 *
 * <p>{@link WolfAiAgent}（純粋な意思決定）に、ゲーム世界でしか分からない情報
 * ——決定のタイミング、飼い主が動いているか、直近に戦闘があったか——を足して束ねる。
 * 意思決定そのものは一切行わず、エージェントへ委譲する。</p>
 *
 * <p>スレッド安全ではない。サーバスレッド上からのみ操作すること。</p>
 */
public final class WolfAiController {

    private final WolfAiAgent agent;
    private final WolfEventObserver observer;

    private long lastDecisionTick = Long.MIN_VALUE;
    private long behaviourStartTick = Long.MIN_VALUE;
    private BehaviorDecision lastDecision;
    private Vec3d lastOwnerPos;
    private boolean ownerMoving;
    private long lastCombatTick = Long.MIN_VALUE;

    /**
     * ランタイムを生成する。
     *
     * @param agent 対応する AI エージェント
     */
    public WolfAiController(WolfAiAgent agent) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.observer = new WolfEventObserver(agent.wolfId());
    }

    /** @return AI エージェント */
    public WolfAiAgent agent() {
        return agent;
    }

    /** @return 出来事の検出器 */
    public WolfEventObserver observer() {
        return observer;
    }

    /** @return 直近の決定記録（未決定なら {@code null}） */
    public BehaviorDecision lastDecision() {
        return lastDecision;
    }

    /** @return 現在実行中の行動（未決定なら {@code null}） */
    public BehaviorType currentBehavior() {
        return agent.currentBehavior();
    }

    /** @return 最後に意思決定したワールド時刻。未決定なら {@link Long#MIN_VALUE} */
    public long lastDecisionTick() {
        return lastDecisionTick;
    }

    /** @return 現在行動を開始したワールド時刻。未決定なら {@link Long#MIN_VALUE} */
    public long behaviourStartTick() {
        return behaviourStartTick;
    }

    /**
     * 決定タイミングを復元する（永続化からの読み戻し専用）。
     *
     * <p>最低行動継続時間の判定に使うため、復元しないと再起動直後に
     * 本来なら継続すべき行動が即座に再選択されうる。</p>
     *
     * @param lastDecisionTick   最後に意思決定したワールド時刻
     * @param behaviourStartTick 現在行動を開始したワールド時刻
     */
    public void restoreDecisionTiming(long lastDecisionTick, long behaviourStartTick) {
        this.lastDecisionTick = lastDecisionTick;
        this.behaviourStartTick = behaviourStartTick;
    }

    /** @return 飼い主が移動中と判定されているか */
    public boolean isOwnerMoving() {
        return ownerMoving;
    }

    /**
     * 直近の戦闘の度合いを返す。時間とともに 0 へ減衰する。
     *
     * @return [0,1] の値
     */
    public double recentCombat() {
        if (lastCombatTick == Long.MIN_VALUE) {
            return 0.0;
        }
        long elapsed = lastDecisionTick - lastCombatTick;
        if (elapsed >= AiWolfParameters.RECENT_COMBAT_WINDOW_TICKS) {
            return 0.0;
        }
        return 1.0 - (double) elapsed / AiWolfParameters.RECENT_COMBAT_WINDOW_TICKS;
    }

    /**
     * 戦闘があったことを記録する。
     *
     * @param worldTime 現在のワールド時刻
     */
    public void markCombat(long worldTime) {
        this.lastCombatTick = worldTime;
    }

    /**
     * 行動を再評価すべきかを判定する。
     *
     * <p>通常は {@code DECISION_INTERVAL_TICKS} ごとだが、緊急時は最小継続時間を
     * 無視して即時に再評価する。毎 tick の再評価は行わない。</p>
     *
     * @param wolf      対象のオオカミ
     * @param worldTime 現在のワールド時刻
     * @return 再評価すべきなら true
     */
    public boolean shouldDecide(WolfEntity wolf, long worldTime) {
        // 判定そのものは Minecraft 非依存の DecisionTiming にある。
        // 以前はここで lastDecision（transient）が null かを見ていたため、
        // セーブから復元した個体はロード直後に必ず 1 回余分に決定していた。
        // decisionIndex と Softmax のサンプリング回数が継続実行とずれ、
        // 「同一イベント列 + 同一シード ⇒ 同一行動列」が再起動で崩れていた。
        return DecisionTiming.shouldDecide(agent.currentBehavior() != null, isEmergency(wolf),
                lastDecisionTick, behaviourStartTick, worldTime);
    }

    /**
     * 緊急事態かを判定する。瀕死・被弾・飼い主の被弾は最小継続時間を上書きする。
     *
     * @param wolf 対象のオオカミ
     * @return 緊急なら true
     */
    private boolean isEmergency(WolfEntity wolf) {
        if (wolf.hurtTime > 0) {
            return true;
        }
        float max = wolf.getMaxHealth();
        if (max > 0.0f && wolf.getHealth() / max < AiWolfParameters.EMERGENCY_HEALTH_THRESHOLD) {
            return true;
        }
        LivingEntity owner = wolf.getOwner();
        return owner != null && owner.isAlive() && owner.hurtTime > 0;
    }

    /**
     * 1 回の意思決定を行い、選ばれた行動を返す。
     *
     * @param wolf      対象のオオカミ
     * @param worldTime 現在のワールド時刻
     * @return 決定記録
     */
    public BehaviorDecision decide(WolfEntity wolf, long worldTime) {
        updateOwnerMovement(wolf);
        if (wolf.getTarget() != null || wolf.hurtTime > 0) {
            markCombat(worldTime);
        }
        long elapsed = DecisionTiming.elapsedSince(lastDecisionTick, worldTime);
        lastDecisionTick = worldTime;

        // 行動を選ぶ前に、前回の決定からの変化を出来事として取り込む。
        // これにより「経験 → 内部状態・記憶・選好 → 次の行動」という順序が保たれる。
        observer.observe(wolf, this, worldTime);

        // 経過時間ぶんの定期メンテナンス（感情のベースライン回帰・energy 動態）。
        // シミュレーションは「出来事 → メンテナンス」の順で進むので、ここでも
        // observe のあとに置く。単位は呼び出し回数ではなく経過 tick なので、
        // 決定間隔が違ってもシミュレーションと同じ時間変化になる。
        agent.advanceTime(elapsed);

        WolfContextSnapshot context = ContextBuilder.build(wolf, this);
        BehaviorType previous = agent.currentBehavior();
        BehaviorDecision decision = agent.decide(context, ContextBuilder.currentRegion(wolf),
                ownerResearchId(wolf), worldTime);

        if (previous != decision.selected() || behaviourStartTick == Long.MIN_VALUE) {
            behaviourStartTick = worldTime;
        }
        lastDecision = decision;
        WolfAiRegistry.markDirty();
        // /wolfai summary 用の観測記録。決定の結果を写すだけで、個体には何も返さない。
        BehaviorHistory.SHARED.record(agent.wolfId(), worldTime, decision.selected(),
                context.ownerPresent(), context.ownerDistance());

        ResearchLogger.logDecision(DecisionLogJson.toJsonLine(
                agent.wolfId(), agent.ownerResearchId(), agent.condition(),
                WolfAiRegistry.experimentSeed(), agent.random().seed(), worldTime,
                System.currentTimeMillis(),
                ContextBuilder.dimensionId(wolf.getWorld()), ContextBuilder.biomeId(wolf),
                wolf.getX(), wolf.getY(), wolf.getZ(),
                agent.state(), decision, agent.lastRelevantMemoryIds(),
                new DecisionLogJson.Extras(WolfAiRegistry.wolfSubjectId(wolf.getUuid()), agent.lastContext(),
                        agent.lastRetrievedMemories(), agent.preferences())));
        return decision;
    }

    /**
     * 飼い主の移動状況を、前回の決定時点からの移動距離で判定する。
     *
     * <p>プレイヤーの速度はサーバ側では当てにならないため、位置の差分で見る。</p>
     *
     * @param wolf 対象のオオカミ
     */
    private void updateOwnerMovement(WolfEntity wolf) {
        LivingEntity owner = wolf.getOwner();
        if (owner == null || !owner.isAlive() || owner.getWorld() != wolf.getWorld()) {
            ownerMoving = false;
            lastOwnerPos = null;
            return;
        }
        Vec3d current = owner.getPos();
        ownerMoving = lastOwnerPos != null
                && current.distanceTo(lastOwnerPos) >= AiWolfParameters.OWNER_MOVING_THRESHOLD;
        lastOwnerPos = current;
    }

    /**
     * 飼い主の匿名化済み研究 ID を返す。
     *
     * <p>プレイヤー名も UUID も研究データには残さない。ワールドごとの salt を付けて
     * ハッシュ化した ID だけを使う。同じワールド内では同じ参加者が常に同じ ID になるため、
     * 「同一参加者との共有経験」という研究上の同一性は保たれる。</p>
     *
     * @param wolf 対象のオオカミ
     * @return 研究 ID。飼い主が未設定なら {@code null}
     */
    public static String ownerResearchId(WolfEntity wolf) {
        return WolfAiRegistry.anonymousId(wolf.getOwnerUuid());
    }
}
