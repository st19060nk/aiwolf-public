package jp.ac.naist.hai.aiwolf.behavior;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import jp.ac.naist.hai.aiwolf.context.ContextFeature;
import jp.ac.naist.hai.aiwolf.state.StateParameter;

/**
 * 行動スコアの重み表。<strong>全個体共通・不変</strong>。
 *
 * <p>これは「性格」ではなく、<em>現在の内部状態と文脈がどの行動を誘発するか</em>を
 * 定義する写像である。個体差はこの表からは生じない。個体差は
 * 内部状態・エピソード記憶・学習済み選好という「経験の蓄積」からのみ生じる。</p>
 *
 * <p>状態重みは {@code z = 2x - 1} で [-1,+1] へ変換した値に掛ける。
 * 文脈重みは [0,1] の特徴量にそのまま掛ける。</p>
 *
 * <p>重みを変更した場合は {@code AiWolfParameters.PARAMETER_SET_VERSION} を
 * インクリメントすること。</p>
 */
public final class BehaviorWeights {

    private final Map<BehaviorType, Double> baseScores;
    private final Map<BehaviorType, Map<StateParameter, Double>> stateWeights;
    private final Map<BehaviorType, Map<ContextFeature, Double>> contextWeights;

    private BehaviorWeights(Map<BehaviorType, Double> baseScores,
                            Map<BehaviorType, Map<StateParameter, Double>> stateWeights,
                            Map<BehaviorType, Map<ContextFeature, Double>> contextWeights) {
        this.baseScores = baseScores;
        this.stateWeights = stateWeights;
        this.contextWeights = contextWeights;
    }

    /**
     * MVP の既定重み表を返す。
     *
     * <p>設計メモ：仕様表では RETREAT の負の要因として「energy +0.2」が挙げられている。
     * これは「活力が高いほど退避を選びにくい」という意図と解釈し、重み {@code -0.2} として
     * 符号化した。解釈を変更する場合はこの 1 箇所を修正すればよい。
     * 同様に「lowEnergy +1.0」は {@code ENERGY -1.0} として符号化している。</p>
     *
     * @return 既定の重み表
     */
    public static BehaviorWeights createDefault() {
        Map<BehaviorType, Double> base = new EnumMap<>(BehaviorType.class);
        Map<BehaviorType, Map<StateParameter, Double>> state = new EnumMap<>(BehaviorType.class);
        Map<BehaviorType, Map<ContextFeature, Double>> context = new EnumMap<>(BehaviorType.class);
        for (BehaviorType behavior : BehaviorType.ALL) {
            base.put(behavior, 0.0);
            state.put(behavior, new EnumMap<>(StateParameter.class));
            context.put(behavior, new EnumMap<>(ContextFeature.class));
        }

        // FOLLOW_NORMAL は既定の落ち着いた行動なので、わずかな基礎選好を与える。
        base.put(BehaviorType.FOLLOW_NORMAL, 0.20);

        // ---- FOLLOW_CLOSE -------------------------------------------------
        state.get(BehaviorType.FOLLOW_CLOSE).put(StateParameter.ATTACHMENT, 0.9);
        state.get(BehaviorType.FOLLOW_CLOSE).put(StateParameter.FEAR, 0.7);
        state.get(BehaviorType.FOLLOW_CLOSE).put(StateParameter.STRESS, 0.4);
        state.get(BehaviorType.FOLLOW_CLOSE).put(StateParameter.AUTONOMY, -0.7);
        context.get(BehaviorType.FOLLOW_CLOSE).put(ContextFeature.THREAT, 0.5);
        context.get(BehaviorType.FOLLOW_CLOSE).put(ContextFeature.NIGHT, 0.15);

        // ---- FOLLOW_NORMAL ------------------------------------------------
        state.get(BehaviorType.FOLLOW_NORMAL).put(StateParameter.TRUST, 0.4);
        state.get(BehaviorType.FOLLOW_NORMAL).put(StateParameter.ATTACHMENT, 0.3);
        state.get(BehaviorType.FOLLOW_NORMAL).put(StateParameter.FEAR, -0.1);
        context.get(BehaviorType.FOLLOW_NORMAL).put(ContextFeature.OWNER_MOVING, 0.3);

        // ---- EXPLORE_NEARBY -----------------------------------------------
        state.get(BehaviorType.EXPLORE_NEARBY).put(StateParameter.CURIOSITY, 1.0);
        state.get(BehaviorType.EXPLORE_NEARBY).put(StateParameter.AUTONOMY, 0.8);
        state.get(BehaviorType.EXPLORE_NEARBY).put(StateParameter.ENERGY, 0.4);
        state.get(BehaviorType.EXPLORE_NEARBY).put(StateParameter.FEAR, -0.8);
        state.get(BehaviorType.EXPLORE_NEARBY).put(StateParameter.STRESS, -0.7);
        context.get(BehaviorType.EXPLORE_NEARBY).put(ContextFeature.THREAT, -0.6);
        context.get(BehaviorType.EXPLORE_NEARBY).put(ContextFeature.LOCATION_FEAR, -0.4);

        // ---- APPROACH_PLAYER ----------------------------------------------
        state.get(BehaviorType.APPROACH_PLAYER).put(StateParameter.ATTACHMENT, 0.9);
        state.get(BehaviorType.APPROACH_PLAYER).put(StateParameter.TRUST, 0.4);
        state.get(BehaviorType.APPROACH_PLAYER).put(StateParameter.AUTONOMY, -0.5);
        context.get(BehaviorType.APPROACH_PLAYER).put(ContextFeature.OWNER_DISTANCE_FAR, 0.6);
        context.get(BehaviorType.APPROACH_PLAYER).put(ContextFeature.NEAR_OWNER, -0.4);

        // ---- DEFEND_PLAYER ------------------------------------------------
        state.get(BehaviorType.DEFEND_PLAYER).put(StateParameter.TRUST, 0.7);
        state.get(BehaviorType.DEFEND_PLAYER).put(StateParameter.ATTACHMENT, 0.5);
        state.get(BehaviorType.DEFEND_PLAYER).put(StateParameter.FEAR, -0.5);
        state.get(BehaviorType.DEFEND_PLAYER).put(StateParameter.ENERGY, 0.4);
        context.get(BehaviorType.DEFEND_PLAYER).put(ContextFeature.OWNER_UNDER_ATTACK, 1.0);
        context.get(BehaviorType.DEFEND_PLAYER).put(ContextFeature.THREAT, 0.8);
        context.get(BehaviorType.DEFEND_PLAYER).put(ContextFeature.OWNER_HEALTH_LOW, 0.5);

        // ---- RETREAT ------------------------------------------------------
        state.get(BehaviorType.RETREAT).put(StateParameter.FEAR, 1.1);
        state.get(BehaviorType.RETREAT).put(StateParameter.STRESS, 0.8);
        state.get(BehaviorType.RETREAT).put(StateParameter.ENERGY, -0.2);
        context.get(BehaviorType.RETREAT).put(ContextFeature.WOLF_HEALTH_LOW, 1.2);
        context.get(BehaviorType.RETREAT).put(ContextFeature.WOLF_UNDER_ATTACK, 0.6);
        context.get(BehaviorType.RETREAT).put(ContextFeature.THREAT, 0.4);
        context.get(BehaviorType.RETREAT).put(ContextFeature.EMERGENCY_LOW_HEALTH,
                jp.ac.naist.hai.aiwolf.core.AiWolfParameters.EMERGENCY_RETREAT_BONUS);

        // ---- AVOID_LOCATION -----------------------------------------------
        state.get(BehaviorType.AVOID_LOCATION).put(StateParameter.FEAR, 0.5);
        state.get(BehaviorType.AVOID_LOCATION).put(StateParameter.CURIOSITY, -0.3);
        context.get(BehaviorType.AVOID_LOCATION).put(ContextFeature.LOCATION_FEAR, 1.3);

        // ---- REST_NEAR_PLAYER ---------------------------------------------
        state.get(BehaviorType.REST_NEAR_PLAYER).put(StateParameter.ENERGY, -1.0);
        state.get(BehaviorType.REST_NEAR_PLAYER).put(StateParameter.ATTACHMENT, 0.4);
        state.get(BehaviorType.REST_NEAR_PLAYER).put(StateParameter.STRESS, 0.3);
        context.get(BehaviorType.REST_NEAR_PLAYER).put(ContextFeature.THREAT, -1.0);
        context.get(BehaviorType.REST_NEAR_PLAYER).put(ContextFeature.NEAR_OWNER, 0.3);

        return new BehaviorWeights(unmodifiable(base), unmodifiableNested(state), unmodifiableNested(context));
    }

    /**
     * 基礎選好 beta_b を返す。
     *
     * @param behavior 行動
     * @return 基礎スコア
     */
    public double baseScore(BehaviorType behavior) {
        return baseScores.getOrDefault(behavior, 0.0);
    }

    /**
     * 状態重みの不変ビューを返す。
     *
     * @param behavior 行動
     * @return 状態量 → 重み
     */
    public Map<StateParameter, Double> stateWeights(BehaviorType behavior) {
        return stateWeights.getOrDefault(behavior, Collections.emptyMap());
    }

    /**
     * 文脈重みの不変ビューを返す。
     *
     * @param behavior 行動
     * @return 文脈特徴量 → 重み
     */
    public Map<ContextFeature, Double> contextWeights(BehaviorType behavior) {
        return contextWeights.getOrDefault(behavior, Collections.emptyMap());
    }

    private static <K extends Enum<K>> Map<K, Double> unmodifiable(Map<K, Double> source) {
        return Collections.unmodifiableMap(source);
    }

    private static <K extends Enum<K>, V extends Enum<V>> Map<K, Map<V, Double>> unmodifiableNested(
            Map<K, Map<V, Double>> source) {
        Map<K, Map<V, Double>> copy = new EnumMap<>(source);
        copy.replaceAll((key, value) -> Collections.unmodifiableMap(value));
        return Collections.unmodifiableMap(copy);
    }
}
