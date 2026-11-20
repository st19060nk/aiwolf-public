package jp.ac.naist.hai.aiwolf.behavior;

import java.util.Map;

/**
 * 1 回の行動決定の完全な記録。
 *
 * <p>研究ログにはこのレコードの内容をすべて保存する。選ばれた行動だけでなく
 * 全候補のスコア内訳・確率・乱数値まで残すことで、
 * 「なぜその行動をしたのか」を後から完全に再構成・検証できる。</p>
 *
 * @param decisionIndex       個体ごとの決定連番（乱数の再現に必須）
 * @param selected            選択された行動
 * @param scores              候補行動 → スコア内訳
 * @param probabilities       候補行動 → Softmax 確率
 * @param selectedProbability 選択された行動の確率
 * @param randomValue         選択に用いた [0,1) の乱数値
 * @param temperature         使用した Softmax 温度
 */
public record BehaviorDecision(
        long decisionIndex,
        BehaviorType selected,
        Map<BehaviorType, BehaviorScore> scores,
        Map<BehaviorType, Double> probabilities,
        double selectedProbability,
        double randomValue,
        double temperature) {

    /**
     * 選択された行動のスコア内訳を返す。
     *
     * @return スコア内訳
     */
    public BehaviorScore selectedScore() {
        return scores.get(selected);
    }
}
