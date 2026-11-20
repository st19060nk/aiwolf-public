package jp.ac.naist.hai.aiwolf.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;

/**
 * 台本どおりに進む世界。Minecraft の代わりに文脈と出来事を供給する。
 *
 * <p>実機の {@code ContextBuilder} と {@code WolfEventObserver} が作るものと
 * 同じ型（{@link WolfContextSnapshot} と {@link WolfEvent}）を、
 * ワールド時刻だけから決定論的に作る。Minecraft のエンティティを
 * 単体テストへ持ち込まずに、変換後のロジックだけを比較するための土台である。</p>
 */
final class ScriptedWorld {

    static final String DIMENSION = "minecraft:overworld";
    static final String BIOME = "minecraft:taiga";
    static final String ACTOR = "subject-000000000001";

    /** 出来事を投入するワールド時刻と種別。両経路で同一。 */
    private final Map<Long, WolfEventType> schedule = new HashMap<>();

    ScriptedWorld() {
        long base = 1_000L;
        schedule.put(base + 120L, WolfEventType.PET);
        schedule.put(base + 360L, WolfEventType.MONSTER_ENCOUNTER);
        schedule.put(base + 600L, WolfEventType.PLAYER_PROTECTED_WOLF);
        schedule.put(base + 960L, WolfEventType.FEED);
        schedule.put(base + 1_320L, WolfEventType.WOLF_DAMAGED);
        schedule.put(base + 1_800L, WolfEventType.DEFEND_SUCCESS);
        schedule.put(base + 2_400L, WolfEventType.PET);
        schedule.put(base + 3_000L, WolfEventType.SAFE_SOLO_EXPLORATION);
        schedule.put(base + 3_600L, WolfEventType.NEW_BIOME);
        schedule.put(base + 4_200L, WolfEventType.SLEEP_NEAR_PLAYER);
    }

    /**
     * その時刻に発生する出来事を返す。
     *
     * @param worldTime ワールド時刻
     * @return 出来事。なければ {@link Optional#empty()}
     */
    Optional<WolfEvent> eventAt(long worldTime) {
        WolfEventType type = schedule.get(worldTime);
        if (type == null) {
            return Optional.empty();
        }
        return Optional.of(WolfEvent.of(type, worldTime, DIMENSION, BIOME,
                regionAnchor(worldTime), 64.0, regionAnchor(worldTime), ACTOR, 0.8, 0.9));
    }

    /**
     * その時刻の文脈を返す。
     *
     * @param worldTime  ワールド時刻
     * @param regionFear その個体がその領域に対して持つ恐怖値
     * @return 文脈
     */
    WolfContextSnapshot contextAt(long worldTime, double regionFear) {
        long step = worldTime / AiWolfParameters.DECISION_INTERVAL_TICKS;
        boolean threat = (step / 5) % 4 == 0;
        boolean night = (step / 30) % 2 == 1;
        return WolfContextSnapshot.builder()
                .ownerPresent(true)
                .ownerDistance(3.0 + (step % 9) * 1.5)
                .ownerMoving(step % 2 == 0)
                .hostileMobCount(threat ? 2 : 0)
                .nearestHostileDistance(threat ? 6.5 : WolfContextSnapshot.NO_TARGET_DISTANCE)
                .night(night)
                .underground(night && threat)
                .wolfHealthRatio(threat ? 0.65 : 1.0)
                .regionFear(regionFear)
                .build();
    }

    /**
     * その時刻の領域を返す。
     *
     * @param worldTime ワールド時刻
     * @return 領域
     */
    SpatialRegion regionAt(long worldTime) {
        return SpatialRegion.of(DIMENSION, regionAnchor(worldTime), 64.0, regionAnchor(worldTime));
    }

    private static double regionAnchor(long worldTime) {
        return (worldTime / 1_200L % 3L) * 48.0;
    }
}
