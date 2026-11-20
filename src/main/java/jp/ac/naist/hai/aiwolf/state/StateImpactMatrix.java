package jp.ac.naist.hai.aiwolf.state;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import jp.ac.naist.hai.aiwolf.event.WolfEventType;

/**
 * 出来事が内部状態へ与える増分の行列。<strong>全個体共通・不変</strong>。
 *
 * <p>値はすべて 1 イベントあたりの増分であり、十分小さく設定している。
 * 実際の適用時には salience で重み付けされ、必ず [0,1] へ clamp される。</p>
 *
 * <p>変更した場合は {@code AiWolfParameters.PARAMETER_SET_VERSION} をインクリメントすること。</p>
 */
public final class StateImpactMatrix {

    private static final Map<WolfEventType, Map<StateParameter, Double>> DELTAS = build();

    private StateImpactMatrix() {
        throw new AssertionError("constant holder");
    }

    private static Map<WolfEventType, Map<StateParameter, Double>> build() {
        Map<WolfEventType, Map<StateParameter, Double>> matrix = new EnumMap<>(WolfEventType.class);
        for (WolfEventType type : WolfEventType.ALL) {
            matrix.put(type, new EnumMap<>(StateParameter.class));
        }

        put(matrix, WolfEventType.PET, StateParameter.TRUST, 0.02);
        put(matrix, WolfEventType.PET, StateParameter.ATTACHMENT, 0.04);
        put(matrix, WolfEventType.PET, StateParameter.STRESS, -0.03);

        put(matrix, WolfEventType.FEED, StateParameter.TRUST, 0.02);
        put(matrix, WolfEventType.FEED, StateParameter.ATTACHMENT, 0.02);
        put(matrix, WolfEventType.FEED, StateParameter.ENERGY, 0.10);
        put(matrix, WolfEventType.FEED, StateParameter.STRESS, -0.02);

        put(matrix, WolfEventType.PLAYER_PROTECTED_WOLF, StateParameter.TRUST, 0.05);
        put(matrix, WolfEventType.PLAYER_PROTECTED_WOLF, StateParameter.ATTACHMENT, 0.03);
        put(matrix, WolfEventType.PLAYER_PROTECTED_WOLF, StateParameter.FEAR, -0.02);

        put(matrix, WolfEventType.WOLF_DAMAGED, StateParameter.FEAR, 0.08);
        put(matrix, WolfEventType.WOLF_DAMAGED, StateParameter.STRESS, 0.06);

        put(matrix, WolfEventType.WOLF_DAMAGED_DURING_EXPLORATION, StateParameter.FEAR, 0.07);
        put(matrix, WolfEventType.WOLF_DAMAGED_DURING_EXPLORATION, StateParameter.STRESS, 0.05);
        put(matrix, WolfEventType.WOLF_DAMAGED_DURING_EXPLORATION, StateParameter.CURIOSITY, -0.02);

        put(matrix, WolfEventType.NEAR_DEATH, StateParameter.FEAR, 0.15);
        put(matrix, WolfEventType.NEAR_DEATH, StateParameter.STRESS, 0.12);
        put(matrix, WolfEventType.NEAR_DEATH, StateParameter.AUTONOMY, -0.03);

        put(matrix, WolfEventType.WOLF_LOW_HEALTH, StateParameter.FEAR, 0.06);
        put(matrix, WolfEventType.WOLF_LOW_HEALTH, StateParameter.STRESS, 0.05);

        put(matrix, WolfEventType.PLAYER_DAMAGED_NEAR_WOLF, StateParameter.STRESS, 0.04);
        put(matrix, WolfEventType.PLAYER_DAMAGED_NEAR_WOLF, StateParameter.FEAR, 0.02);

        put(matrix, WolfEventType.MONSTER_ENCOUNTER, StateParameter.FEAR, 0.03);
        put(matrix, WolfEventType.MONSTER_ENCOUNTER, StateParameter.STRESS, 0.02);

        put(matrix, WolfEventType.PLAYER_AND_WOLF_COMBAT, StateParameter.TRUST, 0.02);
        put(matrix, WolfEventType.PLAYER_AND_WOLF_COMBAT, StateParameter.STRESS, 0.03);

        put(matrix, WolfEventType.DEFEND_SUCCESS, StateParameter.FEAR, -0.03);
        put(matrix, WolfEventType.DEFEND_SUCCESS, StateParameter.TRUST, 0.02);
        put(matrix, WolfEventType.DEFEND_SUCCESS, StateParameter.ENERGY, -0.05);

        put(matrix, WolfEventType.SAFE_SOLO_EXPLORATION, StateParameter.AUTONOMY, 0.03);
        put(matrix, WolfEventType.SAFE_SOLO_EXPLORATION, StateParameter.CURIOSITY, 0.02);
        put(matrix, WolfEventType.SAFE_SOLO_EXPLORATION, StateParameter.FEAR, -0.02);

        put(matrix, WolfEventType.LONG_SEPARATION, StateParameter.STRESS, 0.04);
        put(matrix, WolfEventType.LONG_SEPARATION, StateParameter.AUTONOMY, 0.02);

        put(matrix, WolfEventType.REUNION, StateParameter.ATTACHMENT, 0.04);
        put(matrix, WolfEventType.REUNION, StateParameter.STRESS, -0.05);

        put(matrix, WolfEventType.NEW_BIOME, StateParameter.CURIOSITY, 0.02);

        put(matrix, WolfEventType.SLEEP_NEAR_PLAYER, StateParameter.ENERGY, 0.20);
        put(matrix, WolfEventType.SLEEP_NEAR_PLAYER, StateParameter.STRESS, -0.05);
        put(matrix, WolfEventType.SLEEP_NEAR_PLAYER, StateParameter.ATTACHMENT, 0.01);

        Map<WolfEventType, Map<StateParameter, Double>> frozen = new EnumMap<>(matrix);
        frozen.replaceAll((key, value) -> Collections.unmodifiableMap(value));
        return Collections.unmodifiableMap(frozen);
    }

    private static void put(Map<WolfEventType, Map<StateParameter, Double>> matrix,
                            WolfEventType type,
                            StateParameter parameter,
                            double delta) {
        matrix.get(type).put(parameter, delta);
    }

    /**
     * 指定した出来事の状態別増分を返す。
     *
     * @param type 出来事の種別
     * @return 状態量 → 増分の不変マップ（定義がなければ空）
     */
    public static Map<StateParameter, Double> deltasFor(WolfEventType type) {
        return DELTAS.getOrDefault(type, Collections.emptyMap());
    }
}
