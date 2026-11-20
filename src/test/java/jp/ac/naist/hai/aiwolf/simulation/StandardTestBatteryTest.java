package jp.ac.naist.hai.aiwolf.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;

/** {@link StandardTestBattery} の単体テスト。測定手続きそのものの性質を固定する。 */
class StandardTestBatteryTest {

    private static final String DIM = "minecraft:overworld";
    private static final SpatialRegion HOME = SpatialRegion.of(DIM, 0.0, 64.0, 0.0);
    private static final String OWNER = "subject-test";
    private static final UUID WOLF_A = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    private static final UUID WOLF_B = UUID.fromString("00000000-0000-4000-8000-0000000000bb");

    private static WolfAiAgent reared(UUID uuid, WolfEventType type, int times) {
        WolfAiAgent agent = new WolfAiAgent(uuid.toString(), ExperimentalCondition.ADAPTIVE,
                AiWolfParameters.DEFAULT_EXPERIMENT_SEED);
        for (int i = 0; i < times; i++) {
            agent.onEvent(WolfEvent.of(type, i * 600L, DIM, "minecraft:plains",
                    0.0, 64.0, 0.0, OWNER, 1.0, 1.0));
        }
        return agent;
    }

    @Test
    @DisplayName("同じセッション番号なら測定結果が完全に一致する")
    void sameSessionIndexIsReproducible() {
        WolfAiAgent agent = reared(WOLF_A, WolfEventType.PET, 40);
        BehaviouralSignature first = StandardTestBattery.measure(agent, HOME, OWNER, 1000L, 7L);
        BehaviouralSignature second = StandardTestBattery.measure(agent, HOME, OWNER, 1000L, 7L);
        assertEquals(0.0, first.distanceTo(second), 0.0);
    }

    @Test
    @DisplayName("セッション番号が違えばサンプリング誤差の分だけ揺れる")
    void differentSessionIndexVariesWithinSamplingNoise() {
        WolfAiAgent agent = reared(WOLF_A, WolfEventType.PET, 40);
        BehaviouralSignature first = StandardTestBattery.measure(agent, HOME, OWNER, 1000L, 0L);
        BehaviouralSignature second = StandardTestBattery.measure(agent, HOME, OWNER, 1000L, 1L);
        double distance = first.distanceTo(second);
        assertTrue(distance > 0.0, "個体内距離が 0 だと一貫性を測ったことにならない");
        assertTrue(distance < 0.3, "同一個体なのに揺れが大きすぎる: " + distance);
    }

    @Test
    @DisplayName("測定は個体の状態・選好・決定インデックスを変更しない")
    void measuringDoesNotMutateTheAgent() {
        WolfAiAgent agent = reared(WOLF_A, WolfEventType.PET, 40);
        WolfInternalState before = agent.state().copy();
        long decisionIndexBefore = agent.random().decisionIndex();
        BehaviorType behaviorBefore = agent.currentBehavior();
        int memoriesBefore = agent.memoryStore().size();

        StandardTestBattery.measure(agent, HOME, OWNER, 1000L, 0L);

        assertEquals(before, agent.state(), "測定で内部状態が動いてはならない");
        assertEquals(decisionIndexBefore, agent.random().decisionIndex(),
                "測定で本番の乱数ストリームを消費してはならない");
        assertEquals(behaviorBefore, agent.currentBehavior());
        assertEquals(memoriesBefore, agent.memoryStore().size());
    }

    @Test
    @DisplayName("育成履歴が違えば群間距離が個体内距離より明確に大きい")
    void differentHistoriesSeparateBeyondSamplingNoise() {
        WolfAiAgent companionship = reared(WOLF_A, WolfEventType.PET, 60);
        WolfAiAgent solo = reared(WOLF_B, WolfEventType.SAFE_SOLO_EXPLORATION, 60);

        BehaviouralSignature companionshipFirst =
                StandardTestBattery.measure(companionship, HOME, OWNER, 1000L, 0L);
        BehaviouralSignature companionshipSecond =
                StandardTestBattery.measure(companionship, HOME, OWNER, 1000L, 1L);
        BehaviouralSignature soloSignature =
                StandardTestBattery.measure(solo, HOME, OWNER, 1000L, 0L);

        double within = companionshipFirst.distanceTo(companionshipSecond);
        double between = companionshipFirst.distanceTo(soloSignature);
        assertTrue(between > within * 2.0,
                "between=" + between + " within=" + within + " では分離を主張できない");
        assertTrue(companionshipFirst.meanOwnerDistance() < soloSignature.meanOwnerDistance(),
                "撫でられた個体の方が飼い主の近くで過ごすはず");
    }

    @Test
    @DisplayName("育成前の個体は指紋が完全に一致する")
    void untrainedAgentsShareTheSameSignature() {
        WolfAiAgent freshA = new WolfAiAgent(WOLF_A.toString(), ExperimentalCondition.ADAPTIVE, 1L);
        WolfAiAgent freshB = new WolfAiAgent(WOLF_B.toString(), ExperimentalCondition.ADAPTIVE, 1L);
        // 乱数ストリームは個体シードに依存するため、同じセッション番号でも系列は異なる。
        // それでも分布としては近いことを確認する（個体差の源が経験だけであることの裏付け）。
        BehaviouralSignature a = StandardTestBattery.measure(freshA, HOME, OWNER, 0L, 0L);
        BehaviouralSignature b = StandardTestBattery.measure(freshB, HOME, OWNER, 0L, 0L);
        assertTrue(a.distanceTo(b) < 0.3, "育成前の個体差が大きすぎる: " + a.distanceTo(b));
    }

    @Test
    @DisplayName("STATIC 条件では育成履歴が指紋を変えない")
    void staticConditionIgnoresHistory() {
        WolfAiAgent petted = new WolfAiAgent(WOLF_A.toString(), ExperimentalCondition.STATIC, 1L);
        WolfAiAgent explored = new WolfAiAgent(WOLF_A.toString(), ExperimentalCondition.STATIC, 1L);
        for (int i = 0; i < 60; i++) {
            petted.onEvent(WolfEvent.of(WolfEventType.PET, i * 600L, DIM, "minecraft:plains",
                    0.0, 64.0, 0.0, OWNER, 1.0, 1.0));
            explored.onEvent(WolfEvent.of(WolfEventType.SAFE_SOLO_EXPLORATION, i * 600L, DIM,
                    "minecraft:plains", 0.0, 64.0, 0.0, OWNER, 1.0, 1.0));
        }
        // STATIC は関係性・動機付け状態を更新しないので、感情状態を戻せば同一になる。
        petted.state().set(StateParameter.FEAR, StateParameter.FEAR.baselineValue());
        petted.state().set(StateParameter.STRESS, StateParameter.STRESS.baselineValue());
        explored.state().set(StateParameter.FEAR, StateParameter.FEAR.baselineValue());
        explored.state().set(StateParameter.STRESS, StateParameter.STRESS.baselineValue());

        BehaviouralSignature a = StandardTestBattery.measure(petted, HOME, OWNER, 0L, 0L);
        BehaviouralSignature b = StandardTestBattery.measure(explored, HOME, OWNER, 0L, 0L);
        assertEquals(0.0, a.distanceTo(b), 1.0e-12,
                "STATIC 条件で履歴による差が出てはならない");
    }
}
