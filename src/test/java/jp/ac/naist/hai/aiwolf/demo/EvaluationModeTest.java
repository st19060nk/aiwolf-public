package jp.ac.naist.hai.aiwolf.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jp.ac.naist.hai.aiwolf.agent.EventOutcome;
import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.learning.PreferenceVector;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.state.StateCategory;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 評価モード（学習凍結）が「学習だけを止め、行動選択は止めない」ことの検証。 */
final class EvaluationModeTest {

    @Test
    @DisplayName("評価モードでは記憶・空間記憶・選好・長期の内部状態が更新されない")
    void learningStopsDuringEvaluation() {
        WolfAiAgent agent = DemoFixtures.newAgent();
        DemoFixtures.experienceDangerAndRescue(agent);
        agent.setLearningFrozen(true);

        WolfInternalState stateBefore = agent.state().copy();
        PreferenceVector preferencesBefore = agent.preferences().copy();
        int memoriesBefore = agent.memoryStore().size();
        long sequenceBefore = agent.memorySequence();
        SpatialRegion region = DemoFixtures.dangerRegion();
        double fearBefore = agent.spatialMemory().get(region).fearValue();
        double positiveBefore = agent.spatialMemory().get(region).positiveValue();
        int visitsBefore = agent.spatialMemory().get(region).visitCount();

        long tick = DemoFixtures.START + 50_000L;
        for (WolfEventType type : List.of(WolfEventType.PET, WolfEventType.FEED,
                WolfEventType.PLAYER_PROTECTED_WOLF, WolfEventType.WOLF_DAMAGED,
                WolfEventType.SAFE_SOLO_EXPLORATION, WolfEventType.REUNION)) {
            EventOutcome outcome = agent.onEvent(DemoFixtures.eventAtDanger(type, tick++));
            assertNull(outcome.createdMemoryId(), "no memory may be created while frozen");
        }

        assertEquals(memoriesBefore, agent.memoryStore().size());
        assertEquals(sequenceBefore, agent.memorySequence());
        assertEquals(preferencesBefore, agent.preferences());
        assertEquals(fearBefore, agent.spatialMemory().get(region).fearValue());
        assertEquals(positiveBefore, agent.spatialMemory().get(region).positiveValue());
        assertEquals(visitsBefore, agent.spatialMemory().get(region).visitCount());
        for (StateParameter parameter : StateParameter.values()) {
            if (parameter.category() != StateCategory.EMOTIONAL) {
                assertEquals(stateBefore.get(parameter), agent.state().get(parameter),
                        parameter + " is long-term state and must not learn while frozen");
            }
        }
    }

    @Test
    @DisplayName("評価モードでも一時的な感情（STATIC 条件と同じ規則）は反応する")
    void emotionsStillReactDuringEvaluation() {
        WolfAiAgent agent = DemoFixtures.newAgent();
        agent.setLearningFrozen(true);
        double fearBefore = agent.state().fear();
        agent.onEvent(DemoFixtures.eventAtDanger(WolfEventType.WOLF_DAMAGED, DemoFixtures.START));
        assertTrue(agent.state().fear() > fearBefore);
    }

    @Test
    @DisplayName("評価モードでも行動選択（スコア計算・Softmax・乱数の進行）は動く")
    void behaviourSelectionKeepsRunning() {
        WolfAiAgent agent = DemoFixtures.newAgent();
        DemoFixtures.experienceDangerAndRescue(agent);
        agent.setLearningFrozen(true);
        long indexBefore = agent.random().decisionIndex();

        List<BehaviorDecision> decisions = DemoFixtures.decide(agent, 0, 30);

        assertEquals(30, decisions.size());
        assertEquals(indexBefore + 30, agent.random().decisionIndex());
        for (BehaviorDecision decision : decisions) {
            assertNotNull(decision.selected());
            double total = decision.probabilities().values().stream().mapToDouble(Double::doubleValue).sum();
            assertEquals(1.0, total, 1e-9);
        }
        assertEquals(decisions.get(decisions.size() - 1).selected(), agent.currentBehavior());
    }

    @Test
    @DisplayName("評価モードは同じ個体の行動を変えない（凍結は学習だけに効く）")
    void freezingDoesNotChangeDecisionsWithoutEvents() {
        WolfAiAgent frozen = DemoFixtures.newAgent();
        WolfAiAgent learning = DemoFixtures.newAgent();
        DemoFixtures.experienceDangerAndRescue(frozen);
        DemoFixtures.experienceDangerAndRescue(learning);
        frozen.setLearningFrozen(true);

        List<BehaviorDecision> a = DemoFixtures.decide(frozen, 0, 40);
        List<BehaviorDecision> b = DemoFixtures.decide(learning, 0, 40);
        for (int i = 0; i < a.size(); i++) {
            assertEquals(b.get(i).selected(), a.get(i).selected());
            assertEquals(b.get(i).scores(), a.get(i).scores());
        }
    }

    @Test
    @DisplayName("評価モードでもエピソード記憶の検索と空間記憶の参照は行動に効く")
    void memoryAndSpatialReferenceStayActive() {
        WolfAiAgent agent = DemoFixtures.newAgent();
        DemoFixtures.experienceDangerAndRescue(agent);
        agent.setLearningFrozen(true);
        SpatialRegion region = DemoFixtures.dangerRegion();

        double regionFear = agent.regionFear(region);
        assertTrue(regionFear >= AiWolfParameters.DANGEROUS_REGION_THRESHOLD,
                "the fixture must make the place dangerous, was " + regionFear);

        BehaviorDecision decision = DemoFixtures.decide(agent, 1, 2).get(0);

        assertFalse(agent.lastRelevantMemoryIds().isEmpty(), "memories must still be retrieved");
        assertTrue(decision.scores().containsKey(BehaviorType.AVOID_LOCATION),
                "spatial fear must still make AVOID_LOCATION eligible");
        assertNotEquals(0.0, decision.scores().get(BehaviorType.RETREAT).memory(),
                "retrieved danger memories must still contribute to the score");
        assertEquals(regionFear, agent.regionFear(region), "referencing must not update the region");
    }

    @Test
    @DisplayName("凍結を解除すると従来どおり学習する")
    void unfreezingResumesLearning() {
        WolfAiAgent agent = DemoFixtures.newAgent();
        agent.setLearningFrozen(true);
        agent.setLearningFrozen(false);
        int before = agent.memoryStore().size();
        EventOutcome outcome = agent.onEvent(DemoFixtures.eventAtDanger(WolfEventType.PET, DemoFixtures.START));
        assertNotNull(outcome.createdMemoryId());
        assertEquals(before + 1, agent.memoryStore().size());
    }
}
