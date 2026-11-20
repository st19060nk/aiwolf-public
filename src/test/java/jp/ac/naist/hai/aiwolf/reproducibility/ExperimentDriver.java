package jp.ac.naist.hai.aiwolf.reproducibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jp.ac.naist.hai.aiwolf.agent.WolfAiAgent;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorDecision;
import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;

/**
 * 再現性テスト用の決定論的な実験ドライバ。
 *
 * <p>同じ手順書（step 番号から出来事と文脈を決める関数）を複数の個体へ与えるためのもの。
 * 実機の代わりにここで「同一イベント列」を厳密に定義する。時刻・乱数・入力のいずれも
 * step 番号だけから決まるので、何度実行しても入力側は完全に同一である。</p>
 */
final class ExperimentDriver {

    static final String DIMENSION = "minecraft:overworld";
    static final String BIOME = "minecraft:plains";
    static final String ACTOR = "subject-000000000001";
    static final long START_TICK = 24_000L;

    /** 出来事を投入する step と、その種別。全個体で同一。 */
    private static final Map<Integer, WolfEventType> SCHEDULE = Map.ofEntries(
            Map.entry(2, WolfEventType.PET),
            Map.entry(5, WolfEventType.FEED),
            Map.entry(8, WolfEventType.MONSTER_ENCOUNTER),
            Map.entry(11, WolfEventType.PLAYER_PROTECTED_WOLF),
            Map.entry(14, WolfEventType.WOLF_DAMAGED),
            Map.entry(17, WolfEventType.DEFEND_SUCCESS),
            Map.entry(21, WolfEventType.PET),
            Map.entry(24, WolfEventType.LONG_SEPARATION),
            Map.entry(27, WolfEventType.SAFE_SOLO_EXPLORATION),
            Map.entry(30, WolfEventType.PET),
            Map.entry(34, WolfEventType.MONSTER_ENCOUNTER),
            Map.entry(38, WolfEventType.PLAYER_PROTECTED_WOLF),
            Map.entry(42, WolfEventType.FEED),
            Map.entry(47, WolfEventType.WOLF_DAMAGED));

    private ExperimentDriver() {
        throw new AssertionError("utility class");
    }

    /**
     * step 番号に対応するワールド時刻を返す。
     *
     * @param step step 番号
     * @return ワールド時刻 [ticks]
     */
    static long tickAt(int step) {
        return START_TICK + (long) step * AiWolfParameters.DECISION_INTERVAL_TICKS;
    }

    /**
     * step 番号に対応する領域を返す。3 か所を巡回する。
     *
     * @param step step 番号
     * @return 領域
     */
    static SpatialRegion regionAt(int step) {
        int cell = step % 3;
        return SpatialRegion.of(DIMENSION, cell * 64.0, 64.0, cell * 64.0);
    }

    /**
     * step 番号に対応する文脈を返す。全個体で同一である。
     *
     * @param step       step 番号
     * @param regionFear その個体がその領域に対して持つ恐怖値
     * @return 文脈
     */
    static WolfContextSnapshot contextAt(int step, double regionFear) {
        boolean threat = (step / 4) % 3 == 0;
        boolean night = (step / 12) % 2 == 1;
        return WolfContextSnapshot.builder()
                .ownerDistance(3.0 + (step % 7) * 2.0)
                .ownerMoving(step % 2 == 0)
                .hostileMobCount(threat ? 2 : 0)
                .nearestHostileDistance(threat ? 6.0 : WolfContextSnapshot.NO_TARGET_DISTANCE)
                .night(night)
                .underground(night && threat)
                .wolfHealthRatio(threat ? 0.6 : 1.0)
                .regionFear(regionFear)
                .build();
    }

    /**
     * 手順書の {@code [from, to)} 区間を個体に与え、選ばれた行動列を返す。
     *
     * @param agent 対象個体（更新される）
     * @param from  開始 step（含む）
     * @param to    終了 step（含まない）
     * @return 選ばれた行動の列
     */
    static List<BehaviorType> run(WolfAiAgent agent, int from, int to) {
        List<BehaviorType> behaviors = new ArrayList<>();
        for (int step = from; step < to; step++) {
            long tick = tickAt(step);
            SpatialRegion region = regionAt(step);

            WolfEventType scheduled = SCHEDULE.get(step);
            if (scheduled != null) {
                agent.onEvent(WolfEvent.of(scheduled, tick, DIMENSION, BIOME,
                        region.regionX() * (double) AiWolfParameters.SPATIAL_REGION_SIZE,
                        64.0,
                        region.regionZ() * (double) AiWolfParameters.SPATIAL_REGION_SIZE,
                        ACTOR, 0.8, 0.9));
            }
            agent.spatialMemory().recordVisit(region, tick);
            agent.tickMaintenance();

            BehaviorDecision decision = agent.decide(
                    contextAt(step, agent.regionFear(region)), region, ACTOR, tick);
            behaviors.add(decision.selected());
        }
        return behaviors;
    }
}
