package jp.ac.naist.hai.aiwolf.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;
import jp.ac.naist.hai.aiwolf.state.StateParameter;

/** {@link WolfAiAgent} の結合テスト（Minecraft 非依存）。 */
class WolfAiAgentTest {

    private static final String DIM = "minecraft:overworld";
    private static final SpatialRegion HOME = SpatialRegion.of(DIM, 0.0, 64.0, 0.0);
    private static final String OWNER = "subject-1";

    private static final UUID WOLF_A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID WOLF_B = UUID.fromString("00000000-0000-4000-8000-0000000000b2");

    private static WolfAiAgent agent(UUID uuid, ExperimentalCondition condition) {
        return new WolfAiAgent(uuid.toString(), condition, AiWolfParameters.DEFAULT_EXPERIMENT_SEED);
    }

    private static void feedEvents(WolfAiAgent agent, WolfEventType type, int times) {
        for (int i = 0; i < times; i++) {
            agent.onEvent(WolfEvent.of(type, i * 600L, DIM, "minecraft:plains",
                    0.0, 64.0, 0.0, OWNER, 1.0, 1.0));
        }
    }

    private static List<BehaviorType> runSession(WolfAiAgent agent, int decisions) {
        List<BehaviorType> chosen = new ArrayList<>();
        WolfContextSnapshot context = WolfContextSnapshot.builder().ownerDistance(8.0).build();
        long worldTime = 100_000L;
        for (int i = 0; i < decisions; i++) {
            worldTime += AiWolfParameters.DECISION_INTERVAL_TICKS;
            chosen.add(agent.decide(context, HOME, OWNER, worldTime).selected());
            agent.tickMaintenance();
        }
        return chosen;
    }

    private static double proportion(List<BehaviorType> chosen, BehaviorType behavior) {
        return chosen.stream().filter(b -> b == behavior).count() / (double) chosen.size();
    }

    @Test
    @DisplayName("新規個体はすべて同一の内部状態・選好から始まる")
    void newAgentsStartIdentical() {
        WolfAiAgent first = agent(WOLF_A, ExperimentalCondition.ADAPTIVE);
        WolfAiAgent second = agent(WOLF_B, ExperimentalCondition.ADAPTIVE);
        assertEquals(first.state(), second.state());
        assertEquals(first.preferences(), second.preferences());
        assertEquals(0, first.memoryStore().size());
    }

    @Test
    @DisplayName("ADAPTIVE では出来事が記憶・状態・選好すべてに反映される")
    void adaptiveConditionAccumulatesEverything() {
        WolfAiAgent wolf = agent(WOLF_A, ExperimentalCondition.ADAPTIVE);
        feedEvents(wolf, WolfEventType.PET, 20);

        assertEquals(20, wolf.memoryStore().size());
        assertTrue(wolf.state().attachment() > 0.5);
        assertTrue(wolf.preferences().get(BehaviorType.APPROACH_PLAYER) > 0.0);
    }

    @Test
    @DisplayName("STATIC では記憶も選好も蓄積されず、関係性状態も動かない")
    void staticConditionAccumulatesNothingPersistent() {
        WolfAiAgent wolf = agent(WOLF_A, ExperimentalCondition.STATIC);
        feedEvents(wolf, WolfEventType.PET, 20);

        assertEquals(0, wolf.memoryStore().size());
        assertEquals(0.5, wolf.state().attachment(), 1.0e-9);
        assertEquals(0.0, wolf.preferences().get(BehaviorType.APPROACH_PLAYER), 1.0e-9);
    }

    @Test
    @DisplayName("STATE_ONLY では状態は動くがエピソード記憶は残らない")
    void stateOnlyConditionUpdatesStateButNotMemory() {
        WolfAiAgent wolf = agent(WOLF_A, ExperimentalCondition.STATE_ONLY);
        feedEvents(wolf, WolfEventType.PET, 20);

        assertTrue(wolf.state().attachment() > 0.5);
        assertEquals(0, wolf.memoryStore().size());
        assertEquals(0.0, wolf.preferences().get(BehaviorType.APPROACH_PLAYER), 1.0e-9);
    }

    @Test
    @DisplayName("危険な場所の経験が region の恐怖値として残る")
    void dangerousExperienceBecomesRegionFear() {
        WolfAiAgent wolf = agent(WOLF_A, ExperimentalCondition.ADAPTIVE);
        assertEquals(0.0, wolf.regionFear(HOME), 0.0);
        feedEvents(wolf, WolfEventType.NEAR_DEATH, 6);
        assertTrue(wolf.regionFear(HOME) > 0.3, "危険を繰り返した region は恐れられるべき");
    }

    @Test
    @DisplayName("育成履歴が違えば同じ文脈でも行動分布が変わる")
    void differentRearingProducesDifferentBehaviourDistribution() {
        WolfAiAgent companionship = agent(WOLF_A, ExperimentalCondition.ADAPTIVE);
        feedEvents(companionship, WolfEventType.PET, 60);

        WolfAiAgent solo = agent(WOLF_B, ExperimentalCondition.ADAPTIVE);
        feedEvents(solo, WolfEventType.SAFE_SOLO_EXPLORATION, 60);

        List<BehaviorType> companionshipChoices = runSession(companionship, 400);
        List<BehaviorType> soloChoices = runSession(solo, 400);

        assertTrue(proportion(companionshipChoices, BehaviorType.APPROACH_PLAYER)
                        > proportion(soloChoices, BehaviorType.APPROACH_PLAYER),
                "撫でられ続けた個体の方が接近しやすいはず");
        assertTrue(proportion(soloChoices, BehaviorType.EXPLORE_NEARBY)
                        > proportion(companionshipChoices, BehaviorType.EXPLORE_NEARBY),
                "単独探索の個体の方が探索しやすいはず");
    }

    @Test
    @DisplayName("STATIC では育成履歴が違っても行動分布が分かれない")
    void staticConditionDoesNotDivergeWithRearing() {
        WolfAiAgent companionship = agent(WOLF_A, ExperimentalCondition.STATIC);
        feedEvents(companionship, WolfEventType.PET, 60);
        WolfAiAgent solo = agent(WOLF_A, ExperimentalCondition.STATIC);
        feedEvents(solo, WolfEventType.SAFE_SOLO_EXPLORATION, 60);

        List<BehaviorType> companionshipChoices = runSession(companionship, 400);
        List<BehaviorType> soloChoices = runSession(solo, 400);

        for (BehaviorType behavior : BehaviorType.ALL) {
            assertEquals(proportion(companionshipChoices, behavior),
                    proportion(soloChoices, behavior), 0.05,
                    "STATIC 条件で履歴による差が出てはならない: " + behavior.id());
        }
    }

    @Test
    @DisplayName("同じ履歴・同じシードなら行動列が完全に再現される")
    void identicalHistoryAndSeedReproducesSequence() {
        WolfAiAgent first = agent(WOLF_A, ExperimentalCondition.ADAPTIVE);
        WolfAiAgent second = agent(WOLF_A, ExperimentalCondition.ADAPTIVE);
        feedEvents(first, WolfEventType.PET, 30);
        feedEvents(second, WolfEventType.PET, 30);
        assertEquals(runSession(first, 200), runSession(second, 200));
    }

    @Test
    @DisplayName("定期メンテナンスで感情状態が回復し energy が変動する")
    void maintenanceRelaxesEmotionAndMovesEnergy() {
        WolfAiAgent wolf = agent(WOLF_A, ExperimentalCondition.ADAPTIVE);
        wolf.state().set(StateParameter.FEAR, 0.9);
        double energyBefore = wolf.state().energy();
        for (int i = 0; i < 100; i++) {
            wolf.tickMaintenance();
        }
        assertTrue(wolf.state().fear() < 0.9);
        assertTrue(wolf.state().energy() < energyBefore, "行動していれば energy は消耗する");
    }
}
