package jp.ac.naist.hai.aiwolf.consistency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 実験条件の回帰テスト。
 *
 * <p>3 条件の違いは<strong>何を蓄積するか</strong>だけであり、
 * 使える行動・スコア式の骨格・Softmax・実行系はすべて共通でなければならない。
 * STATIC がバニラのオオカミのように振る舞ってしまうと、
 * 「行動レパートリの豊かさ」と「経験への適応」を分離できず、
 * 観測された差がどちらによるものか言えなくなる。</p>
 */
final class ResearchConditionRegressionTest {

    private static final String WOLF = "66666666-6666-4666-8666-666666666666";
    private static final SpatialRegion REGION =
            SpatialRegion.of("minecraft:overworld", 0.0, 64.0, 0.0);

    private static WolfAiAgent agent(ExperimentalCondition condition) {
        return new WolfAiAgent(WOLF, condition, 20_260_923L);
    }

    private static WolfContextSnapshot contextAt(int step) {
        boolean threat = (step / 4) % 3 == 0;
        return WolfContextSnapshot.builder()
                .ownerPresent(true)
                .ownerDistance(3.0 + (step % 7) * 2.0)
                .ownerMoving(step % 2 == 0)
                .hostileMobCount(threat ? 2 : 0)
                .nearestHostileDistance(threat ? 6.0 : WolfContextSnapshot.NO_TARGET_DISTANCE)
                .night((step / 12) % 2 == 1)
                .build();
    }

    /** 3 条件すべてに同じ経験と同じ文脈を与える。 */
    private static List<BehaviorType> rearAndObserve(WolfAiAgent wolf) {
        List<BehaviorType> behaviours = new ArrayList<>();
        for (int step = 0; step < 40; step++) {
            long worldTime = 1_000L + (long) step * AiWolfParameters.DECISION_INTERVAL_TICKS;
            if (step % 3 == 0) {
                wolf.onEvent(WolfEvent.of(WolfEventType.PET, worldTime,
                        "minecraft:overworld", "minecraft:plains", 0.0, 64.0, 0.0,
                        "subject-000000000001", 1.0, 1.0));
            }
            if (step % 7 == 0) {
                wolf.onEvent(WolfEvent.of(WolfEventType.PLAYER_PROTECTED_WOLF, worldTime,
                        "minecraft:overworld", "minecraft:plains", 0.0, 64.0, 0.0,
                        "subject-000000000001", 0.5, 0.9));
            }
            wolf.advanceTime(AiWolfParameters.DECISION_INTERVAL_TICKS);
            BehaviorDecision decision = wolf.decide(contextAt(step), REGION,
                    "subject-000000000001", worldTime);
            behaviours.add(decision.selected());
        }
        return behaviours;
    }

    @Test
    @DisplayName("ADAPTIVE は状態・記憶・選好のすべてが動く")
    void adaptiveAccumulatesEverything() {
        WolfAiAgent wolf = agent(ExperimentalCondition.ADAPTIVE);
        double attachmentBefore = wolf.state().get(StateParameter.ATTACHMENT);
        double followBefore = wolf.preferences().get(BehaviorType.FOLLOW_CLOSE);
        rearAndObserve(wolf);

        assertTrue(wolf.state().get(StateParameter.ATTACHMENT) > attachmentBefore);
        assertTrue(wolf.memoryStore().size() > 0);
        assertTrue(wolf.spatialMemory().size() > 0);
        assertTrue(wolf.preferences().get(BehaviorType.FOLLOW_CLOSE) > followBefore);
    }

    @Test
    @DisplayName("STATE_ONLY は状態だけが動き、記憶と選好は動かない")
    void stateOnlyAccumulatesStateOnly() {
        WolfAiAgent wolf = agent(ExperimentalCondition.STATE_ONLY);
        double attachmentBefore = wolf.state().get(StateParameter.ATTACHMENT);
        double followBefore = wolf.preferences().get(BehaviorType.FOLLOW_CLOSE);
        rearAndObserve(wolf);

        assertTrue(wolf.state().get(StateParameter.ATTACHMENT) > attachmentBefore,
                "内部状態は履歴で動く");
        assertEquals(0, wolf.memoryStore().size(), "エピソード記憶は蓄積しない");
        assertEquals(0, wolf.spatialMemory().size(), "空間記憶も蓄積しない");
        assertEquals(followBefore, wolf.preferences().get(BehaviorType.FOLLOW_CLOSE), 0.0,
                "選好学習は行わない");
    }

    @Test
    @DisplayName("STATIC は長期的な適応をいっさい行わない")
    void staticAccumulatesNothing() {
        WolfAiAgent wolf = agent(ExperimentalCondition.STATIC);
        double attachmentBefore = wolf.state().get(StateParameter.ATTACHMENT);
        double trustBefore = wolf.state().get(StateParameter.TRUST);
        double followBefore = wolf.preferences().get(BehaviorType.FOLLOW_CLOSE);
        rearAndObserve(wolf);

        assertEquals(attachmentBefore, wolf.state().get(StateParameter.ATTACHMENT), 0.0,
                "関係的状態は蓄積しない");
        assertEquals(trustBefore, wolf.state().get(StateParameter.TRUST), 0.0);
        assertEquals(0, wolf.memoryStore().size());
        assertEquals(0, wolf.spatialMemory().size());
        assertEquals(followBefore, wolf.preferences().get(BehaviorType.FOLLOW_CLOSE), 0.0);
    }

    @Test
    @DisplayName("STATIC も現在の文脈へは反応する（バニラのオオカミではない）")
    void staticStillRespondsToTheCurrentContext() {
        WolfAiAgent wolf = agent(ExperimentalCondition.STATIC);
        List<BehaviorType> behaviours = rearAndObserve(wolf);

        assertTrue(behaviours.stream().distinct().count() >= 2,
                "文脈が変われば行動も変わること。1 種類しか出ないならバニラ化している");
    }

    @Test
    @DisplayName("3 条件が同じ行動集合・同じ実行系を使う")
    void everyConditionSharesTheSameBehaviourSet() {
        java.util.Set<BehaviorType> reference = null;
        for (ExperimentalCondition condition : ExperimentalCondition.values()) {
            WolfAiAgent wolf = agent(condition);
            BehaviorDecision decision = wolf.decide(contextAt(0), REGION,
                    "subject-000000000001", 1_000L);

            assertFalse(decision.probabilities().isEmpty());
            assertEquals(1.0, decision.probabilities().values().stream()
                            .mapToDouble(Double::doubleValue).sum(), 1e-9,
                    condition.id() + " の確率が正規化されていない");
            if (reference == null) {
                reference = decision.probabilities().keySet();
            } else {
                assertEquals(reference, decision.probabilities().keySet(),
                        condition.id() + " が別の行動集合を使っている。"
                                + "条件で使える行動が変わると、レパートリの差と適応の差を分離できない");
            }
        }
        assertTrue(reference != null && !reference.isEmpty());
    }

    @Test
    @DisplayName("3 条件が同じ初期状態から始まる")
    void everyConditionStartsFromTheSameInitialState() {
        WolfAiAgent adaptive = agent(ExperimentalCondition.ADAPTIVE);
        WolfAiAgent stateOnly = agent(ExperimentalCondition.STATE_ONLY);
        WolfAiAgent statics = agent(ExperimentalCondition.STATIC);

        for (StateParameter parameter : StateParameter.values()) {
            assertEquals(adaptive.state().get(parameter), stateOnly.state().get(parameter), 0.0);
            assertEquals(adaptive.state().get(parameter), statics.state().get(parameter), 0.0);
        }
        for (BehaviorType behavior : BehaviorType.values()) {
            assertEquals(adaptive.preferences().get(behavior),
                    statics.preferences().get(behavior), 0.0);
        }
    }

    @Test
    @DisplayName("条件によって移動速度や攻撃力が変わらない")
    void conditionsDoNotChangeEntityCapabilities() {
        // 実行系のパラメータ（速度・距離）は条件を引数に取らない。
        // 引数に取り始めたら、その時点で条件が能力差になってしまう。
        assertEquals(2.5, AiWolfParameters.FOLLOW_CLOSE_DISTANCE, 0.0);
        assertEquals(7.0, AiWolfParameters.FOLLOW_NORMAL_DISTANCE, 0.0);
        assertEquals(48.0, AiWolfParameters.OWNER_PRESENCE_RADIUS, 0.0);
    }
}
