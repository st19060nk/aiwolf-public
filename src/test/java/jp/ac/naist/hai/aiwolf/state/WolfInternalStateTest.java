package jp.ac.naist.hai.aiwolf.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;

/** {@link WolfInternalState} の単体テスト。 */
class WolfInternalStateTest {

    private static final double EPS = 1.0e-9;

    @Test
    @DisplayName("初期状態はすべての個体で完全に同一である（研究上の最重要要件）")
    void initialStateIsIdenticalForEveryIndividual() {
        WolfInternalState first = WolfInternalState.createInitial();
        WolfInternalState second = WolfInternalState.createInitial();
        assertEquals(first, second);
        assertNotSame(first, second);
        assertEquals(AiWolfParameters.INITIAL_FEAR, first.fear(), EPS);
        assertEquals(0.30, first.fear(), EPS);
        assertEquals(0.50, first.trust(), EPS);
        assertEquals(1.00, first.energy(), EPS);
        assertEquals(0.20, first.stress(), EPS);
    }

    @Test
    @DisplayName("set / add は必ず 0.0〜1.0 へ clamp される")
    void valuesAreAlwaysClamped() {
        WolfInternalState state = WolfInternalState.createInitial();
        assertEquals(1.0, state.set(StateParameter.TRUST, 5.0), EPS);
        assertEquals(0.0, state.set(StateParameter.FEAR, -2.0), EPS);
        assertEquals(1.0, state.add(StateParameter.TRUST, 0.5), EPS);
        assertEquals(0.0, state.add(StateParameter.FEAR, -0.5), EPS);
    }

    @Test
    @DisplayName("非有限値の設定は拒否される")
    void nonFiniteValuesAreRejected() {
        WolfInternalState state = WolfInternalState.createInitial();
        assertThrows(IllegalArgumentException.class, () -> state.set(StateParameter.TRUST, Double.NaN));
        assertEquals(0.5, state.trust(), EPS);
    }

    @Test
    @DisplayName("signed は 0.5 を 0 とする [-1,+1] 表現を返す")
    void signedConvertsToNeutralCentredRange() {
        WolfInternalState state = WolfInternalState.createInitial();
        state.set(StateParameter.ATTACHMENT, 0.8);
        state.set(StateParameter.AUTONOMY, 0.2);
        assertEquals(0.6, state.signed(StateParameter.ATTACHMENT), EPS);
        assertEquals(-0.6, state.signed(StateParameter.AUTONOMY), EPS);
        assertEquals(0.0, state.signed(StateParameter.CURIOSITY), EPS);
    }

    @Test
    @DisplayName("感情状態のみがベースラインへ回帰し、関係性状態は変化しない")
    void onlyEmotionalStatesRelaxTowardsBaseline() {
        WolfInternalState state = WolfInternalState.createInitial();
        state.set(StateParameter.FEAR, 0.8);
        state.set(StateParameter.TRUST, 0.8);
        state.set(StateParameter.CURIOSITY, 0.8);

        state.relaxEmotionalStatesTowardsBaseline(0.02);

        // fear = 0.8 + 0.02 * (0.30 - 0.8) = 0.79
        assertEquals(0.79, state.fear(), EPS);
        assertEquals(0.8, state.trust(), EPS, "関係性状態は時間経過で減衰しない");
        assertEquals(0.8, state.curiosity(), EPS, "動機付け状態は自動回帰しない");
    }

    @Test
    @DisplayName("感情状態の回帰は多数回でベースラインへ収束する")
    void emotionalRelaxationConvergesToBaseline() {
        WolfInternalState state = WolfInternalState.createInitial();
        state.set(StateParameter.FEAR, 0.9);
        for (int i = 0; i < 2000; i++) {
            state.relaxEmotionalStatesTowardsBaseline(AiWolfParameters.EMOTIONAL_RELAXATION_RATE);
        }
        assertEquals(AiWolfParameters.BASELINE_FEAR, state.fear(), 1.0e-6);
    }

    @Test
    @DisplayName("fromValueMap は未知キーを無視し、欠損キーへ初期値を用いる")
    void fromValueMapIsForwardAndBackwardCompatible() {
        Map<String, Double> raw = new HashMap<>();
        raw.put("trust", 0.9);
        raw.put("unknown_parameter", 0.42);
        raw.put("fear", Double.NaN);

        WolfInternalState state = WolfInternalState.fromValueMap(raw);

        assertEquals(0.9, state.trust(), EPS);
        assertEquals(AiWolfParameters.INITIAL_FEAR, state.fear(), EPS);
        assertEquals(AiWolfParameters.INITIAL_ENERGY, state.energy(), EPS);
    }

    @Test
    @DisplayName("copy は独立したインスタンスを返す")
    void copyIsIndependent() {
        WolfInternalState original = WolfInternalState.createInitial();
        WolfInternalState copy = original.copy();
        copy.set(StateParameter.TRUST, 0.9);
        assertEquals(0.5, original.trust(), EPS);
        assertEquals(0.9, copy.trust(), EPS);
    }

    @Test
    @DisplayName("状態量の分類が仕様どおりである")
    void stateCategoriesMatchSpecification() {
        assertEquals(StateCategory.RELATIONAL, StateParameter.TRUST.category());
        assertEquals(StateCategory.RELATIONAL, StateParameter.ATTACHMENT.category());
        assertEquals(StateCategory.EMOTIONAL, StateParameter.FEAR.category());
        assertEquals(StateCategory.EMOTIONAL, StateParameter.STRESS.category());
        assertEquals(StateCategory.MOTIVATIONAL, StateParameter.CURIOSITY.category());
        assertEquals(StateCategory.MOTIVATIONAL, StateParameter.AUTONOMY.category());
        assertEquals(StateCategory.MOTIVATIONAL, StateParameter.ENERGY.category());
        assertTrue(StateParameter.ALL.size() == 7);
    }
}
