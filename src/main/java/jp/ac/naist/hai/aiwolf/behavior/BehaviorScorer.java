package jp.ac.naist.hai.aiwolf.behavior;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jp.ac.naist.hai.aiwolf.context.ContextFeature;
import jp.ac.naist.hai.aiwolf.context.WolfContextSnapshot;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.ExperimentalCondition;
import jp.ac.naist.hai.aiwolf.learning.PreferenceVector;
import jp.ac.naist.hai.aiwolf.memory.MemorySummary;
import jp.ac.naist.hai.aiwolf.state.StateParameter;
import jp.ac.naist.hai.aiwolf.state.WolfInternalState;

/**
 * 行動スコアの計算器。本システムのアルゴリズム的中核。
 *
 * <p><strong>Minecraft API から完全に独立している。</strong>引数は内部状態・文脈スナップショット・
 * 記憶要約・選好ベクトルという純粋なデータのみであり、{@code WolfEntity} や
 * {@code ServerWorld} を参照しない。そのため Minecraft を起動せずに数万回の
 * シミュレーションを実行し、行動分布が本当に分離するかを事前検証できる。</p>
 *
 * <p>スコア式：</p>
 * <pre>
 * H_t(a) = beta_a
 *        + W_state,a  . z(S_t)        ここで z(x) = 2x - 1
 *        + W_context,a . C_t
 *        + M_t(a)                      記憶由来の寄与
 *        + rho * P_t(a)                学習済み選好
 *        + kappa * I(a = a_{t-1})      継続ボーナス
 *        - penalty(a)
 * </pre>
 *
 * <p>実験条件によって使用する成分が変わる（{@link ExperimentalCondition}）。
 * ただし行動集合・重み表・選択機構はすべての条件で同一である。</p>
 */
public final class BehaviorScorer {

    private final BehaviorWeights weights;
    private final double preferenceCoefficient;
    private final double persistenceBonus;

    /**
     * 既定のパラメータでスコアラを生成する。
     *
     * @param weights 重み表
     */
    public BehaviorScorer(BehaviorWeights weights) {
        this(weights,
                AiWolfParameters.PREFERENCE_SCORE_COEFFICIENT,
                AiWolfParameters.PERSISTENCE_BONUS);
    }

    /**
     * パラメータを明示してスコアラを生成する（感度分析用）。
     *
     * @param weights               重み表
     * @param preferenceCoefficient 選好の寄与係数 rho
     * @param persistenceBonus      継続ボーナス kappa
     */
    public BehaviorScorer(BehaviorWeights weights, double preferenceCoefficient, double persistenceBonus) {
        this.weights = Objects.requireNonNull(weights, "weights");
        this.preferenceCoefficient = preferenceCoefficient;
        this.persistenceBonus = persistenceBonus;
    }

    /**
     * 実行可能な各行動についてスコアと内訳を計算する。
     *
     * @param state            内部状態
     * @param context          文脈スナップショット
     * @param memory           記憶要約（記憶を使わない条件では {@link MemorySummary#empty()}）
     * @param preferences      学習済み選好ベクトル
     * @param currentBehavior  現在実行中の行動（なければ {@code null}）
     * @param eligible         実行可能行動集合
     * @param condition        実験条件
     * @return 行動 → スコアの不変マップ（{@code eligible} と同じキー集合）
     */
    public Map<BehaviorType, BehaviorScore> score(WolfInternalState state,
                                                  WolfContextSnapshot context,
                                                  MemorySummary memory,
                                                  PreferenceVector preferences,
                                                  BehaviorType currentBehavior,
                                                  Set<BehaviorType> eligible,
                                                  ExperimentalCondition condition) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(eligible, "eligible");
        Objects.requireNonNull(condition, "condition");

        Map<ContextFeature, Double> contextFeatures = context.toFeatureVector();
        Map<BehaviorType, BehaviorScore> scores = new EnumMap<>(BehaviorType.class);

        for (BehaviorType behavior : BehaviorType.ALL) {
            if (!eligible.contains(behavior)) {
                continue;
            }
            double base = weights.baseScore(behavior);
            double stateContribution = stateContribution(behavior, state);
            ContextParts contextParts = contextContribution(behavior, contextFeatures);
            double memoryContribution = condition.usesEpisodicMemory()
                    ? memory.contributionFor(behavior)
                    : 0.0;
            double preferenceContribution = condition.usesLearnedPreference()
                    ? preferenceCoefficient * preferences.get(behavior)
                    : 0.0;
            double persistence = behavior == currentBehavior ? persistenceBonus : 0.0;

            scores.put(behavior, BehaviorScore.of(behavior, base, stateContribution, contextParts.total(),
                    memoryContribution, preferenceContribution, persistence, 0.0,
                    contextParts.spatialMemory(), contextParts.emergency()));
        }
        return Collections.unmodifiableMap(scores);
    }

    /**
     * 内部状態由来の寄与を計算する。状態量は {@code z = 2x - 1} で [-1,+1] へ変換される。
     *
     * @param behavior 行動
     * @param state    内部状態
     * @return 状態寄与
     */
    private double stateContribution(BehaviorType behavior, WolfInternalState state) {
        double sum = 0.0;
        for (Map.Entry<StateParameter, Double> entry : weights.stateWeights(behavior).entrySet()) {
            sum += entry.getValue() * state.signed(entry.getKey());
        }
        return sum;
    }

    /**
     * 文脈由来の寄与を計算する。文脈特徴量は [0,1] のまま用いる。
     *
     * @param behavior 行動
     * @param features 文脈特徴量
     * @return 文脈寄与の全体と内訳
     */
    private ContextParts contextContribution(BehaviorType behavior, Map<ContextFeature, Double> features) {
        // total は分解導入前とまったく同じ順序で足す（ビット単位の再現性のため）。
        // 内訳は同じループで別の変数へ取り出すだけで、total には影響しない。
        double sum = 0.0;
        double spatialMemory = 0.0;
        double emergency = 0.0;
        for (Map.Entry<ContextFeature, Double> entry : weights.contextWeights(behavior).entrySet()) {
            double term = entry.getValue() * features.getOrDefault(entry.getKey(), 0.0);
            sum += term;
            if (entry.getKey() == ContextFeature.LOCATION_FEAR) {
                spatialMemory += term;
            } else if (entry.getKey() == ContextFeature.EMERGENCY_LOW_HEALTH) {
                emergency += term;
            }
        }
        return new ContextParts(sum, spatialMemory, emergency);
    }

    /**
     * 文脈寄与の全体と、その内訳。
     *
     * @param total         文脈寄与の全体
     * @param spatialMemory そのうち空間記憶（LOCATION_FEAR）由来の部分
     * @param emergency     そのうち緊急退避（EMERGENCY_LOW_HEALTH）由来の部分
     */
    private record ContextParts(double total, double spatialMemory, double emergency) {
    }
}
