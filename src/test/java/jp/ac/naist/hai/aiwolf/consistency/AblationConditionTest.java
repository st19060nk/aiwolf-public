package jp.ac.naist.hai.aiwolf.consistency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorScore;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AdaptiveFeatures;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.learning.PreferenceVector;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 16：アブレーション条件（機能フラグ）の検証。 */
final class AblationConditionTest {

    private static final String WOLF = "abcdefab-cdef-4abc-8def-abcdefabcdef";
    private static final String DIMENSION = "minecraft:overworld";
    private static final SpatialRegion PLACE = SpatialRegion.of(DIMENSION, 10.0, 64.0, 10.0);

    /** 同じ経験（危険な場所での被弾・救援・撫で）を与えた個体。 */
    private static WolfAiAgent experienced(ExperimentalCondition condition) {
        WolfAiAgent agent = new WolfAiAgent(WOLF, condition, 42L);
        long tick = 100L;
        for (WolfEventType type : List.of(WolfEventType.WOLF_DAMAGED, WolfEventType.WOLF_DAMAGED,
                WolfEventType.WOLF_LOW_HEALTH, WolfEventType.WOLF_DAMAGED, WolfEventType.PLAYER_PROTECTED_WOLF,
                WolfEventType.PET, WolfEventType.PET, WolfEventType.FEED)) {
            agent.onEvent(WolfEvent.of(type, tick, DIMENSION, "minecraft:plains", 10.0, 64.0, 10.0,
                    "subject-x", 0.5, 1.0));
            tick += 40L;
        }
        return agent;
    }

    private static BehaviorDecision decideAtPlace(WolfAiAgent agent) {
        WolfContextSnapshot context = WolfContextSnapshot.builder().ownerDistance(6.0)
                .regionFear(agent.regionFear(PLACE)).build();
        return agent.decide(context, PLACE, "subject-x", 1_000L);
    }

    @Test
    @DisplayName("各条件の機能フラグは研究計画の定義どおり")
    void featureFlagsMatchThePlan() {
        assertEquals(new AdaptiveFeatures(false, false, false, false), ExperimentalCondition.STATIC.features());
        assertEquals(new AdaptiveFeatures(true, false, false, false), ExperimentalCondition.STATE_ONLY.features());
        assertEquals(new AdaptiveFeatures(true, true, true, false), ExperimentalCondition.STATE_MEMORY.features());
        assertEquals(new AdaptiveFeatures(true, false, false, true), ExperimentalCondition.STATE_PREFERENCE.features());
        assertEquals(new AdaptiveFeatures(true, true, true, true), ExperimentalCondition.ADAPTIVE.features());
        assertEquals(new AdaptiveFeatures(true, true, false, false), ExperimentalCondition.STATE_EPISODIC.features());
        assertEquals(new AdaptiveFeatures(true, false, true, false), ExperimentalCondition.STATE_SPATIAL.features());
    }

    @Test
    @DisplayName("既存 3 条件の ID と序数は変わらず、full_adaptive は ADAPTIVE の別名")
    void existingIdsAreStable() {
        assertEquals("static", ExperimentalCondition.STATIC.id());
        assertEquals("state_only", ExperimentalCondition.STATE_ONLY.id());
        assertEquals("adaptive", ExperimentalCondition.ADAPTIVE.id());
        assertEquals(0, ExperimentalCondition.STATIC.ordinal());
        assertEquals(1, ExperimentalCondition.STATE_ONLY.ordinal());
        assertEquals(2, ExperimentalCondition.ADAPTIVE.ordinal());
        assertEquals(ExperimentalCondition.ADAPTIVE, ExperimentalCondition.byId("FULL_ADAPTIVE").orElseThrow());
        for (ExperimentalCondition condition : ExperimentalCondition.values()) {
            assertEquals(condition, ExperimentalCondition.byId(condition.id()).orElseThrow());
        }
    }

    @Test
    @DisplayName("記憶 OFF なら episodicMemory と spatialMemory の寄与が 0、選好 OFF なら preference の寄与が 0")
    void disabledMechanismsContributeNothing() {
        for (ExperimentalCondition condition : ExperimentalCondition.values()) {
            WolfAiAgent agent = experienced(condition);
            BehaviorDecision decision = decideAtPlace(agent);
            for (BehaviorScore score : decision.scores().values()) {
                if (!condition.usesEpisodicMemory()) {
                    assertEquals(0.0, score.episodicMemory(), condition + " " + score.behavior());
                }
                if (!condition.usesSpatialMemory()) {
                    assertEquals(0.0, score.spatialMemory(), condition + " " + score.behavior());
                }
                if (!condition.usesLearnedPreference()) {
                    assertEquals(0.0, score.preference(), condition + " " + score.behavior());
                }
            }
            assertEquals(condition.usesEpisodicMemory(), agent.memoryStore().size() > 0, condition.id());
            assertEquals(condition.usesSpatialMemory(), agent.spatialMemory().size() > 0, condition.id());
        }
    }

    @Test
    @DisplayName("エピソード記憶と空間記憶は独立に ON / OFF できる")
    void episodicAndSpatialAreIndependent() {
        BehaviorDecision spatialOnly = decideAtPlace(experienced(ExperimentalCondition.STATE_SPATIAL));
        BehaviorDecision episodicOnly = decideAtPlace(experienced(ExperimentalCondition.STATE_EPISODIC));

        assertTrue(spatialOnly.scores().containsKey(BehaviorType.AVOID_LOCATION),
                "spatial memory alone must make the place dangerous");
        assertNotEquals(0.0, spatialOnly.scores().get(BehaviorType.AVOID_LOCATION).spatialMemory());
        assertNotEquals(0.0, episodicOnly.scores().get(BehaviorType.RETREAT).episodicMemory());
        assertFalse(episodicOnly.scores().containsKey(BehaviorType.AVOID_LOCATION),
                "without spatial memory the place carries no fear");
    }

    @Test
    @DisplayName("学習機構が同じなら、記憶・選好の有無に関わらず内部状態の学習は同一")
    void stateLearningIsIdenticalAcrossLearningConditions() {
        WolfInternalState reference = experienced(ExperimentalCondition.STATE_ONLY).state();
        for (ExperimentalCondition condition : ExperimentalCondition.values()) {
            if (!condition.usesPersistentStateUpdate()) {
                continue;
            }
            WolfInternalState state = experienced(condition).state();
            for (StateParameter parameter : StateParameter.values()) {
                assertEquals(reference.get(parameter), state.get(parameter), 0.0, condition + " " + parameter);
            }
        }
    }

    @Test
    @DisplayName("選好を学習しない条件では選好は初期値のまま")
    void preferencesStayInitialWithoutPreferenceLearning() {
        for (ExperimentalCondition condition : ExperimentalCondition.values()) {
            WolfAiAgent agent = experienced(condition);
            if (condition.usesLearnedPreference()) {
                assertNotEquals(PreferenceVector.createInitial(), agent.preferences(), condition.id());
            } else {
                assertEquals(PreferenceVector.createInitial(), agent.preferences(), condition.id());
            }
        }
    }
}
