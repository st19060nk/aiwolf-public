package jp.ac.naist.hai.aiwolf.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 時間依存の内部状態更新が、シミュレーションと実機で一致することのテスト。
 *
 * <p>Phase 13 までは実機で {@code tickMaintenance()} が一度も呼ばれていなかった。
 * fear と stress がベースラインへ戻らず energy も動かないため、
 * シミュレーションで見たパラメータの挙動が実機にまったく当てはまらない状態だった。</p>
 *
 * <p>さらに、単に実機でも呼ぶだけでは足りない。シミュレーションは 1 イベントにつき
 * 600 tick 進めて 1 回呼び、実機は 40 tick ごとに決定する。「呼ばれた回数」で数えると
 * 時間の進み方が 15 倍ずれる。単位を経過 tick にすることで初めて一致する。</p>
 */
final class MaintenanceParityTest {

    private static final String WOLF = "11111111-1111-4111-8111-111111111111";
    private static final long INTERVAL = AiWolfParameters.MAINTENANCE_INTERVAL_TICKS;

    private static WolfAiAgent agent() {
        return new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 7L);
    }

    private static WolfEvent frighteningEvent(long worldTime) {
        return WolfEvent.of(WolfEventType.NEAR_DEATH, worldTime,
                "minecraft:overworld", "minecraft:plains", 0.0, 64.0, 0.0,
                "subject-000000000001", 0.1, 1.0);
    }

    @Test
    @DisplayName("経過 tick が同じなら決定間隔が違っても内部状態が一致する")
    void sameElapsedTicksGiveTheSameState() {
        // シミュレーション経路：600 tick を 1 回で進める
        WolfAiAgent simulation = agent();
        simulation.onEvent(frighteningEvent(1000L));
        simulation.advanceTime(INTERVAL * 4);

        // 実機経路：40 tick ごとに 60 回（= 2400 tick）
        WolfAiAgent runtime = agent();
        runtime.onEvent(frighteningEvent(1000L));
        for (int i = 0; i < 60; i++) {
            runtime.advanceTime(AiWolfParameters.DECISION_INTERVAL_TICKS);
        }

        for (StateParameter parameter : StateParameter.values()) {
            assertEquals(simulation.state().get(parameter), runtime.state().get(parameter), 0.0,
                    "経過 tick が同じなら " + parameter.key() + " も一致しなければならない");
        }
    }

    @Test
    @DisplayName("間隔に満たない経過では更新せず、端数を持ち越す")
    void shortIntervalsAccumulate() {
        WolfAiAgent wolf = agent();

        assertEquals(0, wolf.advanceTime(AiWolfParameters.DECISION_INTERVAL_TICKS));
        assertEquals(AiWolfParameters.DECISION_INTERVAL_TICKS, wolf.maintenanceCarryTicks());
        assertEquals(1, wolf.advanceTime(INTERVAL));
        assertEquals(AiWolfParameters.DECISION_INTERVAL_TICKS, wolf.maintenanceCarryTicks(),
                "端数は捨てずに次へ持ち越す");
    }

    @Test
    @DisplayName("恐怖はベースラインへ戻り、放置し続けても行き過ぎない")
    void fearRelaxesTowardsBaseline() {
        WolfAiAgent wolf = agent();
        wolf.onEvent(frighteningEvent(1000L));
        double afterScare = wolf.state().get(StateParameter.FEAR);
        assertTrue(afterScare > AiWolfParameters.INITIAL_FEAR, "まず恐怖が上がること");

        wolf.advanceTime(INTERVAL * 200);
        double relaxed = wolf.state().get(StateParameter.FEAR);

        assertTrue(relaxed < afterScare, "安全な時間が続けば恐怖は下がる");
        assertTrue(relaxed >= 0.0 && relaxed <= 1.0);
    }

    @Test
    @DisplayName("経過 0 以下では何も起こらない")
    void nonPositiveElapsedDoesNothing() {
        WolfAiAgent wolf = agent();
        wolf.onEvent(frighteningEvent(1000L));
        double before = wolf.state().get(StateParameter.FEAR);

        assertEquals(0, wolf.advanceTime(0L));
        assertEquals(0, wolf.advanceTime(-500L));
        assertEquals(before, wolf.state().get(StateParameter.FEAR), 0.0);
    }

    @Test
    @DisplayName("ワールド時刻が大きく飛んでも更新回数に上限がある")
    void hugeJumpsAreCapped() {
        WolfAiAgent wolf = agent();

        int applied = wolf.advanceTime(INTERVAL * 10_000);

        assertEquals(AiWolfParameters.MAX_MAINTENANCE_STEPS_PER_CALL, applied,
                "/time set や長時間の放置で数万回の更新が走ってはならない");
        for (StateParameter parameter : StateParameter.values()) {
            double value = wolf.state().get(parameter);
            assertTrue(value >= 0.0 && value <= 1.0, parameter.key() + " が値域を外れた");
        }
    }

    @Test
    @DisplayName("メンテナンスの端数はセーブ・復元を跨いで保たれる")
    void carrySurvivesRestore() {
        WolfAiAgent wolf = agent();
        wolf.advanceTime(INTERVAL + 123L);
        assertEquals(123L, wolf.maintenanceCarryTicks());

        WolfAiAgent restored = WolfAiAgent.restore(WOLF, ExperimentalCondition.ADAPTIVE,
                wolf.random().seed(), wolf.random().decisionIndex());
        restored.restoreMaintenanceCarryTicks(wolf.maintenanceCarryTicks());

        // 端数 123 + 477 でちょうど 1 回ぶんになる。切り捨てられていれば 0 回。
        assertEquals(1, restored.advanceTime(INTERVAL - 123L),
                "端数を保存しないと、セーブのたびに感情の回復が遅れる");
    }

    @Test
    @DisplayName("シミュレーションの 1 イベント間隔と更新間隔が一致している")
    void simulationIntervalMatchesMaintenanceInterval() {
        assertEquals(600, AiWolfParameters.MAINTENANCE_INTERVAL_TICKS,
                "ここを変えるとシミュレーションの既存結果と比較できなくなる");
    }
}
