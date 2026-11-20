package jp.ac.naist.hai.aiwolf.memory;

/**
 * 検索スコア付きの記憶。研究ログで「なぜこの記憶が効いたか」を追えるよう、
 * 内訳（recency / salience / relevance）を保持したまま返す。
 *
 * @param memory    記憶
 * @param recency   新しさ [0,1]
 * @param salience  重要度 [0,1]
 * @param relevance 現在状況との関連度 [0,1]
 * @param score     合成された検索スコア
 */
public record ScoredMemory(
        WolfMemory memory,
        double recency,
        double salience,
        double relevance,
        double score) {
}
