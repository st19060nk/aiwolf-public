package jp.ac.naist.hai.aiwolf.consistency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import jp.ac.naist.hai.aiwolf.agent.EventOutcome;
import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 1 件の出来事が個体へ及ぼす影響の大きさのテスト。
 *
 * <p>同じ出来事が内部状態・記憶・学習済み選好の 3 経路へ入ること自体は
 * 役割分担であって重複ではない。問題になるのは、3 つが同じ向きに大きな係数で
 * 働いて<strong>たった 1 回の経験で行動分布が別物になる</strong>場合である。
 * それでは「長期の相互作用履歴から個体差が育つ」という主張が成り立たず、
 * 最後に起きた出来事だけで振る舞いが決まる系になってしまう。</p>
 *
 * <p>ここでは閾値を緩めに置いている。狙いは係数を微調整することではなく、
 * 将来の変更で 1 回のイベントが支配的になったときに気づくことである。</p>
 */
final class EventImpactMagnitudeTest {

    private static final String WOLF = "11111111-1111-4111-8111-111111111111";

    /** 1 回の出来事で許す行動確率の変化量の上限。 */
    private static final double MAX_PROBABILITY_SHIFT = 0.25;

    /** 1 回の出来事で許す内部状態の変化量の上限。 */
    private static final double MAX_STATE_SHIFT = 0.20;

    /** 1 回の出来事で許す選好の変化量の上限。 */
    private static final double MAX_PREFERENCE_SHIFT = 0.10;

    private static final WolfEventType[] CHECKED = {
            WolfEventType.PLAYER_PROTECTED_WOLF,
            WolfEventType.DEFEND_SUCCESS,
            WolfEventType.WOLF_DAMAGED,
            WolfEventType.PET,
            WolfEventType.FEED,
            WolfEventType.LONG_SEPARATION,
            WolfEventType.REUNION,
    };

    private static WolfEvent event(WolfEventType type) {
        return WolfEvent.of(type, 1000L, "minecraft:overworld", "minecraft:plains",
                0.0, 64.0, 0.0, "subject-000000000001", 0.8, 0.9);
    }

    private static WolfContextSnapshot neutralContext() {
        return WolfContextSnapshot.builder()
                .ownerPresent(true)
                .ownerDistance(6.0)
                .ownerMoving(false)
                .hostileMobCount(0)
                .nearestHostileDistance(WolfContextSnapshot.NO_TARGET_DISTANCE)
                .build();
    }

    private static Map<BehaviorType, Double> probabilities(WolfAiAgent agent) {
        // 決定そのものは乱数を進めてしまうので、確率だけを読むために
        // 同じ状態から作り直した複製で測る。
        WolfAiAgent probe = WolfAiAgent.restore(agent.wolfId(), agent.condition(),
                agent.random().seed(), agent.random().decisionIndex());
        for (jp.ac.naist.hai.aiwolf.state.StateParameter parameter
                : jp.ac.naist.hai.aiwolf.state.StateParameter.values()) {
            probe.state().set(parameter, agent.state().get(parameter));
        }
        for (BehaviorType behavior : BehaviorType.values()) {
            probe.preferences().set(behavior, agent.preferences().get(behavior));
        }
        probe.restoreCurrentBehavior(agent.currentBehavior());
        BehaviorDecision decision = probe.decide(neutralContext(),
                SpatialRegion.of("minecraft:overworld", 0.0, 64.0, 0.0),
                "subject-000000000001", 2000L);
        return decision.probabilities();
    }

    @Test
    @DisplayName("1 回の出来事で行動確率が極端に変わらない")
    void oneEventDoesNotDominateTheBehaviourDistribution() {
        for (WolfEventType type : CHECKED) {
            WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 1L);
            Map<BehaviorType, Double> before = probabilities(agent);
            agent.onEvent(event(type));
            Map<BehaviorType, Double> after = probabilities(agent);

            for (BehaviorType behavior : before.keySet()) {
                double shift = Math.abs(after.getOrDefault(behavior, 0.0) - before.get(behavior));
                assertTrue(shift <= MAX_PROBABILITY_SHIFT,
                        type.id() + " の 1 回で " + behavior.id() + " の確率が "
                                + String.format("%.3f", shift) + " 動いた。"
                                + "1 回の経験で振る舞いが決まる系になっている");
            }
        }
    }

    @Test
    @DisplayName("1 回の出来事で内部状態と選好が極端に動かない")
    void oneEventMovesStateAndPreferenceModerately() {
        for (WolfEventType type : CHECKED) {
            WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 1L);
            EventOutcome outcome = agent.onEvent(event(type));

            assertTrue(outcome.largestStateChange() <= MAX_STATE_SHIFT,
                    type.id() + " が内部状態を一度に "
                            + String.format("%.3f", outcome.largestStateChange()) + " 動かした");
            assertTrue(outcome.largestPreferenceChange() <= MAX_PREFERENCE_SHIFT,
                    type.id() + " が選好を一度に "
                            + String.format("%.3f", outcome.largestPreferenceChange()) + " 動かした");
        }
    }

    @Test
    @DisplayName("繰り返せば変化は積み上がる（履歴が効いていることの確認）")
    void repeatedEventsDoAccumulate() {
        WolfAiAgent once = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 1L);
        once.onEvent(event(WolfEventType.PET));
        double afterOne = once.preferences().get(BehaviorType.FOLLOW_CLOSE);

        WolfAiAgent many = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 1L);
        for (int i = 0; i < 30; i++) {
            many.onEvent(event(WolfEventType.PET));
        }

        assertTrue(many.preferences().get(BehaviorType.FOLLOW_CLOSE) > afterOne,
                "1 回では小さくても、繰り返せば傾向として育たなければ意味がない");
    }

    @Test
    @DisplayName("変化量は state と preference の差分として取り出せる")
    void outcomeReportsTheActualChange() {
        WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 1L);
        EventOutcome outcome = agent.onEvent(event(WolfEventType.PLAYER_PROTECTED_WOLF));

        outcome.stateDelta().forEach((parameter, delta) -> assertEquals(
                outcome.stateAfter().get(parameter) - outcome.stateBefore().get(parameter),
                delta, 1e-12, parameter.key()));
        outcome.preferenceDelta().forEach((behavior, delta) -> assertEquals(
                outcome.preferenceAfter().get(behavior) - outcome.preferenceBefore().get(behavior),
                delta, 1e-12, behavior.id()));
        assertFalse(outcome.createdMemoryId() == null, "ADAPTIVE なら記憶が作られる");
    }

    @Test
    @DisplayName("飽和した個体では同じ出来事でも変化が小さくなる")
    void saturatedWolvesChangeLess() {
        WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 1L);
        EventOutcome first = agent.onEvent(event(WolfEventType.PET));
        for (int i = 0; i < 200; i++) {
            agent.onEvent(event(WolfEventType.PET));
        }
        EventOutcome last = agent.onEvent(event(WolfEventType.PET));

        assertTrue(last.largestStateChange() <= first.largestStateChange(),
                "適用後の値だけを記録していると、この違いがログから見えない");
    }
}
