package jp.ac.naist.hai.aiwolf.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;

/** {@link PreferenceVector} の単体テスト。 */
class PreferenceVectorTest {

    private static final double EPS = 1.0e-9;

    @Test
    @DisplayName("初期選好はすべての個体・すべての行動で 0 である")
    void initialPreferencesAreAllZero() {
        PreferenceVector vector = PreferenceVector.createInitial();
        for (BehaviorType behavior : BehaviorType.ALL) {
            assertEquals(0.0, vector.get(behavior), EPS);
        }
        assertEquals(PreferenceVector.createInitial(), vector);
    }

    @Test
    @DisplayName("1 イベントでは選好がごくわずかしか動かない")
    void singleEventChangesPreferenceOnlySlightly() {
        PreferenceVector vector = PreferenceVector.createInitial();
        Map<BehaviorType, Double> impacts = new EnumMap<>(BehaviorType.class);
        impacts.put(BehaviorType.FOLLOW_CLOSE, 0.5);

        vector.applyEvent(impacts, 1.0,
                AiWolfParameters.PREFERENCE_DECAY_LAMBDA,
                AiWolfParameters.PREFERENCE_LEARNING_RATE);

        double changed = vector.get(BehaviorType.FOLLOW_CLOSE);
        assertEquals(0.03 * 0.5, changed, EPS);
        assertTrue(Math.abs(changed) < 0.05, "1 イベントで性格が激変してはならない");
    }

    @Test
    @DisplayName("同じイベントの反復で選好が単調に成長し、上限でクリップされる")
    void repeatedEventsAccumulateAndClip() {
        PreferenceVector vector = PreferenceVector.createInitial();
        Map<BehaviorType, Double> impacts = new EnumMap<>(BehaviorType.class);
        impacts.put(BehaviorType.EXPLORE_NEARBY, 1.0);

        double previous = 0.0;
        for (int i = 0; i < 500; i++) {
            vector.applyEvent(impacts, 1.0, 0.01, 0.03);
            double current = vector.get(BehaviorType.EXPLORE_NEARBY);
            assertTrue(current >= previous - EPS, "選好は単調に成長するはず");
            previous = current;
        }
        assertTrue(vector.get(BehaviorType.EXPLORE_NEARBY) <= AiWolfParameters.PREFERENCE_MAX + EPS);
        assertTrue(vector.get(BehaviorType.EXPLORE_NEARBY) > 0.5);
    }

    @Test
    @DisplayName("経験されない行動の選好は忘却項によって 0 へ戻る")
    void unusedPreferencesDecayTowardsZero() {
        PreferenceVector vector = PreferenceVector.createInitial();
        vector.set(BehaviorType.RETREAT, 0.8);

        Map<BehaviorType, Double> noImpact = new EnumMap<>(BehaviorType.class);
        for (int i = 0; i < 1000; i++) {
            vector.applyEvent(noImpact, 1.0, 0.01, 0.03);
        }
        assertTrue(Math.abs(vector.get(BehaviorType.RETREAT)) < 0.001);
    }

    @Test
    @DisplayName("salience が低いイベントは選好をほとんど動かさない")
    void lowSalienceEventsHaveSmallEffect() {
        PreferenceVector strong = PreferenceVector.createInitial();
        PreferenceVector weak = PreferenceVector.createInitial();
        Map<BehaviorType, Double> impacts = new EnumMap<>(BehaviorType.class);
        impacts.put(BehaviorType.DEFEND_PLAYER, 0.5);

        strong.applyEvent(impacts, 1.0, 0.0, 0.03);
        weak.applyEvent(impacts, 0.1, 0.0, 0.03);

        assertTrue(strong.get(BehaviorType.DEFEND_PLAYER) > weak.get(BehaviorType.DEFEND_PLAYER) * 5.0);
    }

    @Test
    @DisplayName("選好は [-1,+1] へクリップされる")
    void preferencesAreClipped() {
        PreferenceVector vector = PreferenceVector.createInitial();
        assertEquals(1.0, vector.set(BehaviorType.RETREAT, 3.0), EPS);
        assertEquals(-1.0, vector.set(BehaviorType.EXPLORE_NEARBY, -9.0), EPS);
    }

    @Test
    @DisplayName("fromValueMap は未知キーを無視する")
    void fromValueMapIgnoresUnknownKeys() {
        Map<String, Double> raw = new java.util.HashMap<>();
        raw.put("explore_nearby", 0.4);
        raw.put("obsolete_behavior", 0.9);
        PreferenceVector vector = PreferenceVector.fromValueMap(raw);
        assertEquals(0.4, vector.get(BehaviorType.EXPLORE_NEARBY), EPS);
        assertEquals(0.0, vector.get(BehaviorType.RETREAT), EPS);
    }
}
