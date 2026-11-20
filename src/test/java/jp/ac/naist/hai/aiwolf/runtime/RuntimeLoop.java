package jp.ac.naist.hai.aiwolf.runtime;

import java.util.ArrayList;
import java.util.List;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;

/**
 * Minecraft 実機の意思決定ループを、エンティティ抜きで再現したもの。
 *
 * <p>{@code WolfAiGoal} が毎 tick {@code shouldDecide} を尋ね、真なら
 * {@code WolfAiController.decide()} を呼ぶ、という実機の流れをそのままなぞる。
 * decide の中の順序——経過 tick の算出 → 出来事の取り込み → 経過時間ぶんの
 * メンテナンス → 行動決定——も実機と同じにしてある。</p>
 *
 * <p>タイミング判定は本番と同じ {@link DecisionTiming} を呼ぶ。ここで判定を
 * 書き写すと、本番のコードが変わってもテストが気づかない。</p>
 */
final class RuntimeLoop {

    /** 1 回の決定の記録。 */
    record Step(long worldTime, BehaviorDecision decision, long decisionIndexAfter) { }

    private final WolfAiAgent agent;
    private final ScriptedWorld world;

    private long lastDecisionTick = Long.MIN_VALUE;
    private long behaviourStartTick = Long.MIN_VALUE;

    RuntimeLoop(WolfAiAgent agent, ScriptedWorld world) {
        this.agent = agent;
        this.world = world;
    }

    /**
     * 決定タイミングを復元する（セーブからの読み戻しの模擬）。
     *
     * @param lastDecisionTick   最後に決定したワールド時刻
     * @param behaviourStartTick 現在行動を開始したワールド時刻
     */
    void restoreTiming(long lastDecisionTick, long behaviourStartTick) {
        this.lastDecisionTick = lastDecisionTick;
        this.behaviourStartTick = behaviourStartTick;
    }

    long lastDecisionTick() {
        return lastDecisionTick;
    }

    long behaviourStartTick() {
        return behaviourStartTick;
    }

    /**
     * {@code [from, to)} の各 tick を進め、行われた決定を返す。
     *
     * @param from 開始ワールド時刻（含む）
     * @param to   終了ワールド時刻（含まない）
     * @return 行われた決定の列
     */
    List<Step> run(long from, long to) {
        List<Step> steps = new ArrayList<>();
        for (long worldTime = from; worldTime < to; worldTime++) {
            if (!DecisionTiming.shouldDecide(agent.currentBehavior() != null, false,
                    lastDecisionTick, behaviourStartTick, worldTime)) {
                continue;
            }
            steps.add(decide(worldTime));
        }
        return steps;
    }

    /** 実機の {@code WolfAiController.decide()} と同じ順序で 1 回決定する。 */
    private Step decide(long worldTime) {
        long elapsed = DecisionTiming.elapsedSince(lastDecisionTick, worldTime);
        lastDecisionTick = worldTime;

        world.eventAt(worldTime).ifPresent(agent::onEvent);
        agent.advanceTime(elapsed);

        var previous = agent.currentBehavior();
        BehaviorDecision decision = agent.decide(
                world.contextAt(worldTime, agent.regionFear(world.regionAt(worldTime))),
                world.regionAt(worldTime), ScriptedWorld.ACTOR, worldTime);
        if (previous != decision.selected() || behaviourStartTick == Long.MIN_VALUE) {
            behaviourStartTick = worldTime;
        }
        return new Step(worldTime, decision, agent.random().decisionIndex());
    }
}
