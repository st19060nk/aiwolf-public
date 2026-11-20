package jp.ac.naist.hai.aiwolf.mc;

import java.util.EnumSet;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.WolfEntity;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;

/**
 * 研究 AI の行動をバニラのゴールシステムへ載せるゴール。
 *
 * <p>飼い慣らされたオオカミ 1 体につき 1 つ追加される。意思決定は
 * {@link WolfAiController} が、移動への落とし込みは {@link BehaviorExecutor} が担当し、
 * このクラスは<strong>いつ決めていつ実行するか</strong>だけを扱う。</p>
 *
 * <p>バニラとの分担：</p>
 * <ul>
 *   <li>座り状態、泳ぎ、危険からの自動回避は優先度が上のバニラゴールがそのまま担当する</li>
 *   <li>攻撃対象が生きている間はこのゴールが場所を譲り、バニラの近接戦闘ゴールが動かす</li>
 *   <li>経路探索そのものもバニラの {@code EntityNavigation} を使う</li>
 * </ul>
 *
 * <p>意思決定は毎 tick 行わない。{@code DECISION_INTERVAL_TICKS} ごと、
 * および緊急時のみ再評価する。</p>
 */
public final class AiWolfGoal extends Goal {

    /**
     * ゴールの優先度。バニラのオオカミでは追従と徘徊がこれより下の優先度にあるため、
     * 本ゴールがそれらを置き換える。戦闘は {@link #canStart()} で明示的に譲るので、
     * バニラの正確な優先度に依存しない。
     */
    public static final int PRIORITY = 4;

    private final WolfEntity wolf;
    private BehaviorType appliedBehavior;

    /**
     * ゴールを生成する。
     *
     * @param wolf 対象のオオカミ
     */
    public AiWolfGoal(WolfEntity wolf) {
        this.wolf = wolf;
        setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (!wolf.isTamed() || wolf.isInSittingPose() || wolf.isLeashed()) {
            return false;
        }
        if (wolf.getWorld().isClient()) {
            return false;
        }

        WolfAiController controller = WolfAiRegistry.getOrCreate(wolf);
        long worldTime = wolf.getWorld().getTime();
        if (controller.shouldDecide(wolf, worldTime)) {
            controller.decide(wolf, worldTime);
        }

        BehaviorType behavior = controller.currentBehavior();
        if (behavior == null) {
            return false;
        }
        // 攻撃対象が生きている間はバニラの近接戦闘へ移動制御を譲る。
        return !(behavior == BehaviorType.DEFEND_PLAYER && hasLiveTarget());
    }

    @Override
    public boolean shouldContinue() {
        return canStart();
    }

    @Override
    public boolean shouldRunEveryTick() {
        return true;
    }

    @Override
    public void start() {
        appliedBehavior = null;
        tick();
    }

    @Override
    public void stop() {
        appliedBehavior = null;
        wolf.getNavigation().stop();
    }

    @Override
    public void tick() {
        WolfAiController controller = WolfAiRegistry.getOrCreate(wolf);
        BehaviorType behavior = controller.currentBehavior();
        if (behavior == null) {
            return;
        }
        // 行動が変わったとき、または経路を走り終えたときにだけ移動を指示し直す。
        if (behavior != appliedBehavior || wolf.getNavigation().isIdle()) {
            BehaviorExecutor.apply(wolf, behavior, controller);
            appliedBehavior = behavior;
        }
    }

    private boolean hasLiveTarget() {
        LivingEntity target = wolf.getTarget();
        return target != null && target.isAlive();
    }
}
