package jp.ac.naist.hai.aiwolf.consistency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.learning.EventImpactMatrix;
import jp.ac.naist.hai.aiwolf.memory.MemoryImpactMatrix;
import jp.ac.naist.hai.aiwolf.state.StateImpactMatrix;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Event → State → Memory → Preference の整合性テスト。
 *
 * <p>1 つの出来事は 3 つの経路へ入る。state（いまどう感じているか）、
 * 記憶（その場所・その相手で何が起きたか）、選好（どの行動が報われたか）である。
 * これは重複ではなく役割分担だが、次の 2 つは実際に起こる欠陥なので固定しておく。</p>
 *
 * <ol>
 *   <li><strong>経路の抜け。</strong>どれか 1 つの行列に項目を書き忘れると、
 *       その出来事はログには出るのに一部の経路へ入らない。個体差の形成が
 *       静かに弱まり、原因はログを見ても分からない。</li>
 *   <li><strong>符号の不一致。</strong>state では良い出来事なのに選好では負、
 *       のような矛盾があると「経験から一貫した傾向が育つ」という主張が崩れる。</li>
 * </ol>
 *
 * <p>係数の大きさそのものはここでは判定しない。値の調整は
 * {@code AiWolfParameters.PARAMETER_SET_VERSION} を上げてパイロットを
 * やり直す作業であり、テストで勝手に決めるべきものではない。</p>
 */
final class CausalChainConsistencyTest {

    private static final String WOLF = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    @Test
    @DisplayName("すべての出来事が state を動かす")
    void everyEventTypeAffectsState() {
        for (WolfEventType type : WolfEventType.values()) {
            Map<StateParameter, Double> deltas = StateImpactMatrix.deltasFor(type);
            assertFalse(deltas.isEmpty(), type.id() + " が state へ入らない");
            assertTrue(deltas.values().stream().anyMatch(delta -> delta != 0.0),
                    type.id() + " の state 変化がすべて 0 になっている");
        }
    }

    @Test
    @DisplayName("すべての出来事が選好を動かす")
    void everyEventTypeAffectsPreference() {
        for (WolfEventType type : WolfEventType.values()) {
            Map<BehaviorType, Double> impacts = EventImpactMatrix.impactsFor(type);
            assertFalse(impacts.isEmpty(), type.id() + " が選好へ入らない");
            assertTrue(impacts.values().stream().anyMatch(impact -> impact != 0.0),
                    type.id() + " の選好変化がすべて 0 になっている");
        }
    }

    @Test
    @DisplayName("すべての出来事が記憶経路で行動へ効く")
    void everyEventTypeAffectsBehaviourThroughMemory() {
        for (WolfEventType type : WolfEventType.values()) {
            Map<BehaviorType, Double> impacts = MemoryImpactMatrix.impactsFor(type);
            assertFalse(impacts.isEmpty(), type.id() + " が記憶経路で効かない");
        }
    }

    @Test
    @DisplayName("出来事の valence と state・選好の符号が矛盾しない")
    void signsAgreeAcrossPaths() {
        for (WolfEventType type : WolfEventType.values()) {
            double attachment = StateImpactMatrix.deltasFor(type)
                    .getOrDefault(StateParameter.ATTACHMENT, 0.0);
            double towardsOwner = EventImpactMatrix.impactsFor(type)
                    .getOrDefault(BehaviorType.APPROACH_PLAYER, 0.0);
            if (attachment != 0.0 && towardsOwner != 0.0) {
                assertTrue(attachment * towardsOwner > 0.0,
                        type.id() + ": attachment と approach_player の符号が逆を向いている");
            }
        }
    }

    @Test
    @DisplayName("1 つの出来事は state・記憶・選好へちょうど 1 回ずつ入る")
    void oneEventEntersEachPathExactlyOnce() {
        WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 1L);
        WolfEvent event = WolfEvent.of(WolfEventType.PLAYER_PROTECTED_WOLF, 1000L,
                "minecraft:overworld", "minecraft:plains", 0.0, 64.0, 0.0,
                "subject-000000000001", 0.5, 0.9);

        double attachmentBefore = agent.state().get(StateParameter.ATTACHMENT);
        double followBefore = agent.preferences().get(BehaviorType.FOLLOW_CLOSE);
        agent.onEvent(event);
        double attachmentAfterOne = agent.state().get(StateParameter.ATTACHMENT);
        double followAfterOne = agent.preferences().get(BehaviorType.FOLLOW_CLOSE);

        assertEquals(1, agent.memoryStore().size(), "1 件の出来事から記憶は 1 件");
        double expectedDelta = StateImpactMatrix.deltasFor(WolfEventType.PLAYER_PROTECTED_WOLF)
                .getOrDefault(StateParameter.ATTACHMENT, 0.0) * event.salience();
        assertEquals(expectedDelta, attachmentAfterOne - attachmentBefore, 1e-9,
                "state の変化が salience × 係数の 1 倍でないなら二重に適用されている");
        assertTrue(followAfterOne > followBefore);
    }

    @Test
    @DisplayName("同じ戦闘から 2 件発火すれば影響は 2 倍になる（重複発火を防ぐ理由）")
    void duplicateEventsWouldDoubleTheImpact() {
        WolfEvent event = WolfEvent.of(WolfEventType.PLAYER_PROTECTED_WOLF, 1000L,
                "minecraft:overworld", "minecraft:plains", 0.0, 64.0, 0.0,
                "subject-000000000001", 0.5, 0.9);

        WolfAiAgent once = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 1L);
        once.onEvent(event);
        WolfAiAgent twice = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, 1L);
        twice.onEvent(event);
        twice.onEvent(event);

        assertTrue(twice.state().get(StateParameter.TRUST) > once.state().get(StateParameter.TRUST),
                "だから CombatEpisode は 1 戦闘 1 件しか返さない");
        assertEquals(2, twice.memoryStore().size());
    }

    @Test
    @DisplayName("STATIC 条件では履歴が 3 経路すべてに残らない")
    void staticConditionAccumulatesNothing() {
        WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.STATIC, 1L);
        double followBefore = agent.preferences().get(BehaviorType.FOLLOW_CLOSE);
        for (int i = 0; i < 20; i++) {
            agent.onEvent(WolfEvent.of(WolfEventType.PET, 1000L + i * 100L,
                    "minecraft:overworld", "minecraft:plains", 0.0, 64.0, 0.0,
                    "subject-000000000001", 1.0, 1.0));
        }

        assertEquals(0, agent.memoryStore().size());
        assertEquals(0, agent.spatialMemory().size());
        assertEquals(followBefore, agent.preferences().get(BehaviorType.FOLLOW_CLOSE), 0.0);
    }
}
