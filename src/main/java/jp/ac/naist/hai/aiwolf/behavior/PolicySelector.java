package jp.ac.naist.hai.aiwolf.behavior;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.MathSupport;
import jp.ac.naist.hai.aiwolf.random.WolfRandomProvider;

/**
 * Softmax による確率的行動選択。
 *
 * <pre>
 * P(b) = exp(S_b / tau) / sum_i exp(S_i / tau)
 * </pre>
 *
 * <p>常に最大スコアの行動を選ぶと機械的に見えるため、スコアを確率分布へ変換して
 * サンプリングする。数値安定性のため {@code exp((S - Smax) / tau)} を用いる
 * （{@link MathSupport#softmax}）。</p>
 *
 * <p>乱数は {@link WolfRandomProvider} から供給され、{@code (seed, decisionIndex)} の
 * 純粋関数として決まる。したがって同一イベント列・同一シードなら行動列は完全に再現する。</p>
 *
 * <p>Minecraft に依存しない。</p>
 */
public final class PolicySelector {

    private final double temperature;

    /**
     * 既定の温度でセレクタを生成する。
     */
    public PolicySelector() {
        this(AiWolfParameters.SOFTMAX_TEMPERATURE);
    }

    /**
     * 温度を明示してセレクタを生成する（pilot 調整・感度分析用）。
     *
     * @param temperature Softmax 温度（正の値）。低いほど決定論的
     */
    public PolicySelector(double temperature) {
        if (!(temperature > 0.0) || !Double.isFinite(temperature)) {
            throw new IllegalArgumentException("temperature must be a positive finite number but was " + temperature);
        }
        this.temperature = temperature;
    }

    /** @return Softmax 温度 */
    public double temperature() {
        return temperature;
    }

    /**
     * スコアから行動を 1 つサンプリングする。
     *
     * @param scores 候補行動 → スコア内訳（空であってはならない）
     * @param random 乱数供給器（decisionIndex が 1 つ進む）
     * @return 決定記録
     * @throws IllegalArgumentException 候補が空の場合
     */
    public BehaviorDecision select(Map<BehaviorType, BehaviorScore> scores, WolfRandomProvider random) {
        Objects.requireNonNull(scores, "scores");
        Objects.requireNonNull(random, "random");
        if (scores.isEmpty()) {
            throw new IllegalArgumentException("scores must not be empty");
        }

        List<BehaviorType> candidates = new ArrayList<>(scores.keySet());
        double[] rawScores = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            rawScores[i] = scores.get(candidates.get(i)).finalScore();
        }

        double[] probabilities = MathSupport.softmax(rawScores, temperature);
        long decisionIndex = random.decisionIndex();
        double randomValue = random.nextDecisionRandom();

        int selectedIndex = sampleIndex(probabilities, randomValue);
        BehaviorType selected = candidates.get(selectedIndex);

        Map<BehaviorType, Double> probabilityMap = new EnumMap<>(BehaviorType.class);
        for (int i = 0; i < candidates.size(); i++) {
            probabilityMap.put(candidates.get(i), probabilities[i]);
        }

        return new BehaviorDecision(
                decisionIndex,
                selected,
                Collections.unmodifiableMap(new EnumMap<>(scores)),
                Collections.unmodifiableMap(probabilityMap),
                probabilities[selectedIndex],
                randomValue,
                temperature);
    }

    /**
     * 累積確率と乱数値から添字をサンプリングする。
     *
     * <p>浮動小数点誤差で累積和が 1 に届かない場合に備え、最後の要素をフォールバックとする。</p>
     *
     * @param probabilities 確率配列（合計 1）
     * @param randomValue   [0,1) の乱数値
     * @return 選択された添字
     */
    private static int sampleIndex(double[] probabilities, double randomValue) {
        double cumulative = 0.0;
        for (int i = 0; i < probabilities.length; i++) {
            cumulative += probabilities[i];
            if (randomValue < cumulative) {
                return i;
            }
        }
        return probabilities.length - 1;
    }
}
