package jp.ac.naist.hai.aiwolf.behavior;

/**
 * 表現行動。マクロ行動の実行中に重ねて発生する短い動作。
 *
 * <p>これらはスコアリングや Softmax 選択の対象ではなく、研究ログの行動分布にも
 * 含めない。マクロ行動の分布を汚さずに「生き物らしさ」を与えるための層である。</p>
 *
 * <p>例：{@link BehaviorType#FOLLOW_CLOSE} の実行中に時々
 * {@link #LOOK_AT_PLAYER} を発生させる。</p>
 */
public enum ExpressionBehaviorType {

    /** 飼い主の方を見る。 */
    LOOK_AT_PLAYER,

    /** 視線を外す。 */
    LOOK_AWAY,

    /** 首をかしげる。 */
    HEAD_TILT,

    /** 短く立ち止まる。 */
    SHORT_PAUSE,

    /** 尻尾を動かす。 */
    TAIL_MOVEMENT,

    /** 一歩だけ飼い主へ近づく。 */
    SMALL_APPROACH
}
