package jp.ac.naist.hai.aiwolf.demo;

import java.util.ArrayList;
import java.util.List;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;

/** デモ機能のテストで共有する、決定論的な経験と文脈。 */
final class DemoFixtures {

    static final String WOLF = "77777777-7777-4777-8777-777777777777";
    static final String OTHER_WOLF = "88888888-8888-4888-8888-888888888888";
    static final String DIMENSION = "minecraft:overworld";
    static final String BIOME = "minecraft:plains";
    /** 匿名化済みの飼い主 ID（研究ログに載る形）。 */
    static final String ACTOR = "subject-0123456789ab";
    /** 生のプレイヤー UUID。表示やログに出てはならない。 */
    static final String RAW_PLAYER_UUID = "99999999-1234-4abc-8def-999999999999";
    static final long SEED = 20_261_114L;
    static final long START = 24_000L;

    /** 危険な場所（洞窟の想定）。 */
    static final double DANGER_X = 100.0;
    static final double DANGER_Y = 30.0;
    static final double DANGER_Z = 100.0;

    private DemoFixtures() {
    }

    static WolfAiAgent newAgent() {
        WolfAiAgent agent = new WolfAiAgent(WOLF, ExperimentalCondition.ADAPTIVE, SEED);
        agent.setOwnerResearchId(ACTOR);
        return agent;
    }

    static SpatialRegion dangerRegion() {
        return SpatialRegion.of(DIMENSION, DANGER_X, DANGER_Y, DANGER_Z);
    }

    static WolfEvent eventAtDanger(WolfEventType type, long tick) {
        return WolfEvent.of(type, tick, DIMENSION, BIOME, DANGER_X, DANGER_Y, DANGER_Z, ACTOR, 0.5, 1.0);
    }

    /** 危険な場所での被弾と、飼い主による救援。Demo B / C の経験に相当する。 */
    static void experienceDangerAndRescue(WolfAiAgent agent) {
        long tick = START;
        for (WolfEventType type : List.of(WolfEventType.MONSTER_ENCOUNTER, WolfEventType.WOLF_DAMAGED,
                WolfEventType.WOLF_DAMAGED, WolfEventType.WOLF_LOW_HEALTH, WolfEventType.WOLF_DAMAGED,
                WolfEventType.WOLF_DAMAGED,
                WolfEventType.PLAYER_PROTECTED_WOLF, WolfEventType.PET, WolfEventType.FEED)) {
            agent.onEvent(eventAtDanger(type, tick));
            tick += AiWolfParameters.DECISION_INTERVAL_TICKS;
        }
    }

    static WolfContextSnapshot context(int step, double regionFear) {
        boolean threat = step % 4 == 0;
        return WolfContextSnapshot.builder()
                .ownerDistance(3.0 + (step % 5) * 2.0)
                .ownerMoving(step % 2 == 0)
                .hostileMobCount(threat ? 1 : 0)
                .nearestHostileDistance(threat ? 7.0 : WolfContextSnapshot.NO_TARGET_DISTANCE)
                .regionFear(regionFear)
                .build();
    }

    /** 危険な場所で決定を続け、選ばれた行動を返す。 */
    static List<BehaviorDecision> decide(WolfAiAgent agent, int from, int to) {
        List<BehaviorDecision> decisions = new ArrayList<>();
        SpatialRegion region = dangerRegion();
        for (int step = from; step < to; step++) {
            long tick = START + 10_000L + (long) step * AiWolfParameters.DECISION_INTERVAL_TICKS;
            agent.advanceTime(AiWolfParameters.DECISION_INTERVAL_TICKS);
            decisions.add(agent.decide(context(step, agent.regionFear(region)), region, ACTOR, tick));
        }
        return decisions;
    }
}
