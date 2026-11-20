package jp.ac.naist.hai.aiwolf.simulation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;

/**
 * 1 個体の行動傾向を表す指紋（Behavioral Signature）。
 *
 * <p>評価セッション中に各マクロ行動が選ばれた割合を保持する。
 * 個体内で安定し、育成履歴の異なる個体間では差が出ることを期待する。</p>
 *
 * @param proportions       行動 → 選択割合（合計 1）
 * @param sampleCount       観測した決定回数
 * @param meanOwnerDistance 観測期間中の飼い主との平均距離 [blocks]
 */
public record BehaviouralSignature(Map<BehaviorType, Double> proportions,
                                   int sampleCount,
                                   double meanOwnerDistance) {

    /** コンパクトコンストラクタ。割合マップを不変化する。 */
    public BehaviouralSignature {
        proportions = Collections.unmodifiableMap(new EnumMap<>(proportions));
    }

    /**
     * 行動回数から指紋を作る。
     *
     * @param counts            行動 → 選択回数
     * @param meanOwnerDistance 観測期間中の飼い主との平均距離
     * @return 指紋
     */
    public static BehaviouralSignature fromCounts(Map<BehaviorType, Integer> counts, double meanOwnerDistance) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        Map<BehaviorType, Double> proportions = new EnumMap<>(BehaviorType.class);
        for (BehaviorType behavior : BehaviorType.ALL) {
            proportions.put(behavior, total == 0 ? 0.0 : counts.getOrDefault(behavior, 0) / (double) total);
        }
        return new BehaviouralSignature(proportions, total, meanOwnerDistance);
    }

    /**
     * 指定行動の割合を返す。
     *
     * @param behavior 行動
     * @return 割合 [0,1]
     */
    public double proportionOf(BehaviorType behavior) {
        return proportions.getOrDefault(behavior, 0.0);
    }

    /**
     * 他の指紋との距離（total variation distance）を返す。
     *
     * <p>0 なら完全一致、1 なら共通する行動が全くない。</p>
     *
     * @param other 比較対象
     * @return [0,1] の距離
     */
    public double distanceTo(BehaviouralSignature other) {
        double sum = 0.0;
        for (BehaviorType behavior : BehaviorType.ALL) {
            sum += Math.abs(proportionOf(behavior) - other.proportionOf(behavior));
        }
        return sum / 2.0;
    }
}
