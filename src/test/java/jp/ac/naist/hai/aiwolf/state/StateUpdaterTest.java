package jp.ac.naist.hai.aiwolf.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;

/** {@link StateUpdater} の単体テスト。 */
class StateUpdaterTest {

    private static final double EPS = 1.0e-9;

    private static WolfEvent event(WolfEventType type) {
        return WolfEvent.of(type, 1000L, "minecraft:overworld", "minecraft:plains",
                0.0, 64.0, 0.0, "subject-1", 1.0, 1.0);
    }

    @Test
    @DisplayName("状態増分は salience で重み付けされる")
    void deltasAreScaledBySalience() {
        WolfInternalState state = WolfInternalState.createInitial();
        WolfEvent pet = event(WolfEventType.PET);

        StateUpdater.applyEvent(state, pet, ExperimentalCondition.ADAPTIVE);

        // PET: trust +0.02, attachment +0.04, stress -0.03。salience = 0.5*0.5 + 0.5*0.2 = 0.35
        assertEquals(0.5 + 0.02 * 0.35, state.trust(), EPS);
        assertEquals(0.5 + 0.04 * 0.35, state.attachment(), EPS);
        assertEquals(0.2 - 0.03 * 0.35, state.stress(), EPS);
    }

    @Test
    @DisplayName("STATIC 条件では感情状態だけが動き、関係性状態は変化しない")
    void staticConditionOnlyMovesEmotionalStates() {
        WolfInternalState state = WolfInternalState.createInitial();
        StateUpdater.applyEvent(state, event(WolfEventType.PET), ExperimentalCondition.STATIC);

        assertEquals(0.5, state.trust(), EPS, "STATIC では関係性状態に履歴が残ってはならない");
        assertEquals(0.5, state.attachment(), EPS);
        assertTrue(state.stress() < 0.2, "一時的な感情状態は動いてよい");
    }

    @Test
    @DisplayName("STATE_ONLY と ADAPTIVE は同じ状態更新を行う")
    void stateOnlyAndAdaptiveUpdateStatesIdentically() {
        WolfInternalState stateOnly = WolfInternalState.createInitial();
        WolfInternalState adaptive = WolfInternalState.createInitial();
        WolfEvent damaged = event(WolfEventType.WOLF_DAMAGED);

        StateUpdater.applyEvent(stateOnly, damaged, ExperimentalCondition.STATE_ONLY);
        StateUpdater.applyEvent(adaptive, damaged, ExperimentalCondition.ADAPTIVE);

        for (StateParameter parameter : StateParameter.ALL) {
            assertEquals(adaptive.get(parameter), stateOnly.get(parameter), 0.0);
        }
    }

    @Test
    @DisplayName("繰り返し適用しても値域を超えない")
    void repeatedEventsStayClamped() {
        WolfInternalState state = WolfInternalState.createInitial();
        for (int i = 0; i < 5000; i++) {
            StateUpdater.applyEvent(state, event(WolfEventType.NEAR_DEATH), ExperimentalCondition.ADAPTIVE);
        }
        for (StateParameter parameter : StateParameter.ALL) {
            double value = state.get(parameter);
            assertTrue(value >= 0.0 && value <= 1.0, parameter.key() + " out of range: " + value);
        }
        assertEquals(1.0, state.fear(), EPS);
    }

    @Test
    @DisplayName("探索では energy が減り、休息では回復する")
    void energyDynamicsDependOnBehaviour() {
        WolfInternalState exploring = WolfInternalState.createInitial();
        exploring.set(StateParameter.ENERGY, 0.5);
        StateUpdater.applyEnergyDynamics(exploring, BehaviorType.EXPLORE_NEARBY);
        assertTrue(exploring.energy() < 0.5);

        WolfInternalState resting = WolfInternalState.createInitial();
        resting.set(StateParameter.ENERGY, 0.5);
        StateUpdater.applyEnergyDynamics(resting, BehaviorType.REST_NEAR_PLAYER);
        assertTrue(resting.energy() > 0.5);
    }

    @Test
    @DisplayName("感情状態は繰り返しの回帰でベースラインへ戻る")
    void relaxationReturnsToBaseline() {
        WolfInternalState state = WolfInternalState.createInitial();
        state.set(StateParameter.FEAR, 0.95);
        state.set(StateParameter.STRESS, 0.90);
        for (int i = 0; i < 3000; i++) {
            StateUpdater.relaxEmotionalStates(state);
        }
        assertEquals(StateParameter.FEAR.baselineValue(), state.fear(), 1.0e-6);
        assertEquals(StateParameter.STRESS.baselineValue(), state.stress(), 1.0e-6);
    }
}
