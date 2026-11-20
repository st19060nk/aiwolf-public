package jp.ac.naist.hai.aiwolf.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * シミュレーション経路と実機経路の一致テスト。
 *
 * <p>同じ初期状態・実験条件・出来事列・文脈列・乱数シード・メンテナンス時刻を
 * 与えたとき、両経路が同じ内部状態・選好・記憶・行動確率・行動列へ到達することを
 * 確かめる。ここが一致しない限り、シミュレーションで決めたパラメータは
 * 実験に使えない。</p>
 *
 * <p>実機経路は毎 tick {@link DecisionTiming} に尋ねて決定時刻を決める。
 * その時刻列をそのまま書き出し、シミュレーション経路では同じ時刻へ直接
 * 出来事・メンテナンス・決定を与える。<strong>同じ入力を同じ順序で与えれば、
 * どちらの経路を通っても結果が同じ</strong>ことを示す形にしてある。</p>
 */
final class SimulationRuntimeParityTest {

    private static final String WOLF = "44444444-4444-4444-8444-444444444444";
    private static final long START = 1_000L;
    private static final long END = 5_000L;

    private static WolfAiAgent agent(ExperimentalCondition condition) {
        return new WolfAiAgent(WOLF, condition, 20_260_923L);
    }

    /** シミュレーション側の経路。決定時刻を与えられて直接まわす。 */
    private static List<BehaviorDecision> runSimulationPath(WolfAiAgent wolf,
                                                            ScriptedWorld world,
                                                            List<Long> decisionTicks) {
        List<BehaviorDecision> decisions = new ArrayList<>();
        long previousTick = Long.MIN_VALUE;
        for (long worldTime : decisionTicks) {
            long elapsed = DecisionTiming.elapsedSince(previousTick, worldTime);
            previousTick = worldTime;

            world.eventAt(worldTime).ifPresent(wolf::onEvent);
            wolf.advanceTime(elapsed);
            decisions.add(wolf.decide(
                    world.contextAt(worldTime, wolf.regionFear(world.regionAt(worldTime))),
                    world.regionAt(worldTime), ScriptedWorld.ACTOR, worldTime));
        }
        return decisions;
    }

    @Test
    @DisplayName("両経路の行動列・確率・乱数値がすべて一致する")
    void bothPathsProduceTheSameDecisions() {
        ScriptedWorld world = new ScriptedWorld();

        WolfAiAgent runtimeWolf = agent(ExperimentalCondition.ADAPTIVE);
        List<RuntimeLoop.Step> runtimeSteps =
                new RuntimeLoop(runtimeWolf, world).run(START, END);
        List<Long> ticks = runtimeSteps.stream().map(RuntimeLoop.Step::worldTime).toList();

        WolfAiAgent simulationWolf = agent(ExperimentalCondition.ADAPTIVE);
        List<BehaviorDecision> simulationDecisions =
                runSimulationPath(simulationWolf, world, ticks);

        assertFalse(runtimeSteps.isEmpty(), "決定が一度も起きていない");
        assertEquals(runtimeSteps.size(), simulationDecisions.size());
        for (int i = 0; i < runtimeSteps.size(); i++) {
            BehaviorDecision runtime = runtimeSteps.get(i).decision();
            BehaviorDecision simulation = simulationDecisions.get(i);
            assertEquals(simulation.selected(), runtime.selected(), "決定 " + i + " の行動");
            assertEquals(simulation.randomValue(), runtime.randomValue(), 0.0,
                    "決定 " + i + " の乱数値");
            for (BehaviorType behavior : simulation.probabilities().keySet()) {
                assertEquals(simulation.probabilities().get(behavior),
                        runtime.probabilities().get(behavior), 0.0,
                        "決定 " + i + " の " + behavior.id() + " の確率");
            }
        }
    }

    @Test
    @DisplayName("両経路の内部状態・選好・記憶が一致する")
    void bothPathsProduceTheSameInternalState() {
        ScriptedWorld world = new ScriptedWorld();

        WolfAiAgent runtimeWolf = agent(ExperimentalCondition.ADAPTIVE);
        List<Long> ticks = new RuntimeLoop(runtimeWolf, world).run(START, END)
                .stream().map(RuntimeLoop.Step::worldTime).toList();

        WolfAiAgent simulationWolf = agent(ExperimentalCondition.ADAPTIVE);
        runSimulationPath(simulationWolf, world, ticks);

        for (StateParameter parameter : StateParameter.values()) {
            assertEquals(simulationWolf.state().get(parameter),
                    runtimeWolf.state().get(parameter), 0.0, "state " + parameter.key());
        }
        for (BehaviorType behavior : BehaviorType.values()) {
            assertEquals(simulationWolf.preferences().get(behavior),
                    runtimeWolf.preferences().get(behavior), 0.0, "preference " + behavior.id());
        }
        assertEquals(simulationWolf.memoryStore().size(), runtimeWolf.memoryStore().size());
        assertEquals(simulationWolf.spatialMemory().size(), runtimeWolf.spatialMemory().size());
        assertEquals(simulationWolf.random().decisionIndex(), runtimeWolf.random().decisionIndex());
        assertEquals(simulationWolf.lastRelevantMemoryIds(), runtimeWolf.lastRelevantMemoryIds());
        assertEquals(simulationWolf.maintenanceCarryTicks(), runtimeWolf.maintenanceCarryTicks());
    }

    @Test
    @DisplayName("メンテナンスを実機経路で行わないと状態がずれる（呼ぶ必要がある理由）")
    void skippingMaintenanceBreaksParity() {
        ScriptedWorld world = new ScriptedWorld();

        WolfAiAgent withMaintenance = agent(ExperimentalCondition.ADAPTIVE);
        List<Long> ticks = new RuntimeLoop(withMaintenance, world).run(START, END)
                .stream().map(RuntimeLoop.Step::worldTime).toList();

        // Phase 13 までの実機の挙動：メンテナンスを一度も行わない
        WolfAiAgent withoutMaintenance = agent(ExperimentalCondition.ADAPTIVE);
        for (long worldTime : ticks) {
            world.eventAt(worldTime).ifPresent(withoutMaintenance::onEvent);
            withoutMaintenance.decide(
                    world.contextAt(worldTime,
                            withoutMaintenance.regionFear(world.regionAt(worldTime))),
                    world.regionAt(worldTime), ScriptedWorld.ACTOR, worldTime);
        }

        assertTrue(Math.abs(withMaintenance.state().get(StateParameter.FEAR)
                        - withoutMaintenance.state().get(StateParameter.FEAR)) > 1e-9,
                "メンテナンスの有無で fear が変わらないなら、この修正は意味を持たない");
    }

    @Test
    @DisplayName("STATE_ONLY と STATIC でも両経路が一致する")
    void parityHoldsForEveryCondition() {
        for (ExperimentalCondition condition
                : List.of(ExperimentalCondition.STATE_ONLY, ExperimentalCondition.STATIC)) {
            ScriptedWorld world = new ScriptedWorld();

            WolfAiAgent runtimeWolf = agent(condition);
            List<RuntimeLoop.Step> steps = new RuntimeLoop(runtimeWolf, world).run(START, END);
            List<Long> ticks = steps.stream().map(RuntimeLoop.Step::worldTime).toList();

            WolfAiAgent simulationWolf = agent(condition);
            List<BehaviorDecision> decisions = runSimulationPath(simulationWolf, world, ticks);

            for (int i = 0; i < steps.size(); i++) {
                assertEquals(decisions.get(i).selected(), steps.get(i).decision().selected(),
                        condition.id() + " の決定 " + i);
            }
            for (StateParameter parameter : StateParameter.values()) {
                assertEquals(simulationWolf.state().get(parameter),
                        runtimeWolf.state().get(parameter), 0.0,
                        condition.id() + " の " + parameter.key());
            }
        }
    }
}
