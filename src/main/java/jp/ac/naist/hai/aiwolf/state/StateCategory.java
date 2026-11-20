package jp.ac.naist.hai.aiwolf.state;

/**
 * 内部状態量の分類。更新ダイナミクスが分類ごとに異なるため、
 * {@code StateUpdater} は本分類に基づいて処理を分岐する。
 */
public enum StateCategory {

    /**
     * 関係性状態（trust, attachment）。
     * 飼い主との経験によってのみ変化し、時間経過だけでは基本的に減衰しない。
     */
    RELATIONAL,

    /**
     * 感情状態（fear, stress）。
     * イベントで急激に変化し、その後ベースラインへ徐々に戻る。
     */
    EMOTIONAL,

    /**
     * 動機付け状態（curiosity, autonomy, energy）。
     * 探索・休息などの行動およびイベントによって変化する。
     */
    MOTIVATIONAL
}
