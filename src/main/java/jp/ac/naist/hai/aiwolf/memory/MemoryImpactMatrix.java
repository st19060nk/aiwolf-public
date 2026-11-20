package jp.ac.naist.hai.aiwolf.memory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;

/**
 * 想起された記憶が行動スコアへ与える影響の行列。<strong>全個体共通・不変</strong>。
 *
 * <p>選好学習の {@code EventImpactMatrix} とは役割が異なる。あちらは
 * 「長期的な行動傾向をどう変えるか」、こちらは「いま思い出したことが
 * この瞬間の行動選択をどう傾けるか」を表す。</p>
 */
public final class MemoryImpactMatrix {

    private static final Map<WolfEventType, Map<BehaviorType, Double>> IMPACTS = build();

    private MemoryImpactMatrix() {
        throw new AssertionError("constant holder");
    }

    private static Map<WolfEventType, Map<BehaviorType, Double>> build() {
        Map<WolfEventType, Map<BehaviorType, Double>> matrix = new EnumMap<>(WolfEventType.class);
        for (WolfEventType type : WolfEventType.ALL) {
            matrix.put(type, new EnumMap<>(BehaviorType.class));
        }

        put(matrix, WolfEventType.WOLF_DAMAGED, BehaviorType.RETREAT, 0.7);
        put(matrix, WolfEventType.WOLF_DAMAGED, BehaviorType.EXPLORE_NEARBY, -0.4);
        put(matrix, WolfEventType.WOLF_DAMAGED, BehaviorType.AVOID_LOCATION, 0.5);

        put(matrix, WolfEventType.WOLF_DAMAGED_DURING_EXPLORATION, BehaviorType.RETREAT, 0.5);
        put(matrix, WolfEventType.WOLF_DAMAGED_DURING_EXPLORATION, BehaviorType.EXPLORE_NEARBY, -0.5);
        put(matrix, WolfEventType.WOLF_DAMAGED_DURING_EXPLORATION, BehaviorType.AVOID_LOCATION, 0.6);

        put(matrix, WolfEventType.NEAR_DEATH, BehaviorType.RETREAT, 0.8);
        put(matrix, WolfEventType.NEAR_DEATH, BehaviorType.AVOID_LOCATION, 0.8);
        put(matrix, WolfEventType.NEAR_DEATH, BehaviorType.EXPLORE_NEARBY, -0.6);

        put(matrix, WolfEventType.WOLF_LOW_HEALTH, BehaviorType.RETREAT, 0.5);
        put(matrix, WolfEventType.WOLF_LOW_HEALTH, BehaviorType.REST_NEAR_PLAYER, 0.4);

        put(matrix, WolfEventType.PLAYER_PROTECTED_WOLF, BehaviorType.FOLLOW_CLOSE, 0.5);
        put(matrix, WolfEventType.PLAYER_PROTECTED_WOLF, BehaviorType.APPROACH_PLAYER, 0.4);

        put(matrix, WolfEventType.PET, BehaviorType.APPROACH_PLAYER, 0.3);
        put(matrix, WolfEventType.FEED, BehaviorType.APPROACH_PLAYER, 0.3);

        put(matrix, WolfEventType.SAFE_SOLO_EXPLORATION, BehaviorType.EXPLORE_NEARBY, 0.4);
        put(matrix, WolfEventType.NEW_BIOME, BehaviorType.EXPLORE_NEARBY, 0.3);

        put(matrix, WolfEventType.MONSTER_ENCOUNTER, BehaviorType.DEFEND_PLAYER, 0.2);
        put(matrix, WolfEventType.MONSTER_ENCOUNTER, BehaviorType.AVOID_LOCATION, 0.3);

        put(matrix, WolfEventType.PLAYER_DAMAGED_NEAR_WOLF, BehaviorType.DEFEND_PLAYER, 0.5);
        put(matrix, WolfEventType.PLAYER_AND_WOLF_COMBAT, BehaviorType.DEFEND_PLAYER, 0.4);
        put(matrix, WolfEventType.DEFEND_SUCCESS, BehaviorType.DEFEND_PLAYER, 0.5);

        put(matrix, WolfEventType.LONG_SEPARATION, BehaviorType.APPROACH_PLAYER, 0.3);
        put(matrix, WolfEventType.REUNION, BehaviorType.APPROACH_PLAYER, 0.4);
        put(matrix, WolfEventType.REUNION, BehaviorType.FOLLOW_CLOSE, 0.3);

        put(matrix, WolfEventType.SLEEP_NEAR_PLAYER, BehaviorType.REST_NEAR_PLAYER, 0.3);

        Map<WolfEventType, Map<BehaviorType, Double>> frozen = new EnumMap<>(matrix);
        frozen.replaceAll((key, value) -> Collections.unmodifiableMap(value));
        return Collections.unmodifiableMap(frozen);
    }

    private static void put(Map<WolfEventType, Map<BehaviorType, Double>> matrix,
                            WolfEventType type,
                            BehaviorType behavior,
                            double impact) {
        matrix.get(type).put(behavior, impact);
    }

    /**
     * 指定した出来事種別の行動別影響度を返す。
     *
     * @param type 出来事の種別
     * @return 行動 → 影響度の不変マップ（定義がなければ空）
     */
    public static Map<BehaviorType, Double> impactsFor(WolfEventType type) {
        return IMPACTS.getOrDefault(type, Collections.emptyMap());
    }
}
