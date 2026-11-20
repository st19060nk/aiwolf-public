package jp.ac.naist.hai.aiwolf.runtime;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;

/**
 * 「いつ次の行動を決めるか」の判定。Minecraft に依存しない。
 *
 * <p><strong>なぜ切り出したのか。</strong>ここは再現性の要である。判定が
 * Minecraft のエンティティに埋まっていると単体テストできず、実際に
 * 「セーブから復元した個体はロード直後に必ず 1 回余分に決定する」という欠陥が
 * 誰にも気づかれないまま残っていた。余分な決定は decisionIndex を 1 つ進め、
 * 以降の乱数列と行動列を継続実行とずらす。</p>
 *
 * <p>判定に使うのは「行動が決まっているか」「緊急か」「経過 tick」だけで、
 * ランタイムに残っているだけの一時オブジェクトは見ない。
 * 一時オブジェクトはセーブを跨がないので、それを条件にすると
 * 復元した個体と継続中の個体で挙動が変わる。</p>
 */
public final class DecisionTiming {

    private DecisionTiming() {
        throw new AssertionError("utility class");
    }

    /**
     * 行動を再評価すべきかを判定する。
     *
     * @param hasBehavior        現在実行中の行動があるか
     * @param emergency          緊急事態か（被弾・瀕死・飼い主の被弾）
     * @param lastDecisionTick   最後に決定したワールド時刻。未決定なら {@link Long#MIN_VALUE}
     * @param behaviourStartTick 現在行動を開始したワールド時刻。未決定なら {@link Long#MIN_VALUE}
     * @param worldTime          現在のワールド時刻
     * @return 再評価すべきなら true
     */
    public static boolean shouldDecide(boolean hasBehavior,
                                       boolean emergency,
                                       long lastDecisionTick,
                                       long behaviourStartTick,
                                       long worldTime) {
        // まだ一度も決めていない個体。ここだけが即時決定の対象である。
        if (!hasBehavior) {
            return true;
        }
        if (emergency) {
            return true;
        }
        // 行動は復元できたがタイミングが分からない（format 1 以前のセーブ）。
        // その場で決め直して基準時刻を作る。
        if (lastDecisionTick == Long.MIN_VALUE || behaviourStartTick == Long.MIN_VALUE) {
            return true;
        }
        // ワールド時刻が巻き戻った場合は基準を取り直す。
        if (worldTime < lastDecisionTick) {
            return true;
        }
        if (worldTime - behaviourStartTick < AiWolfParameters.MINIMUM_BEHAVIOR_DURATION_TICKS) {
            return false;
        }
        return worldTime - lastDecisionTick >= AiWolfParameters.DECISION_INTERVAL_TICKS;
    }

    /**
     * 前回の決定からの経過 tick を返す。
     *
     * <p>初回と、ワールド時刻が巻き戻った場合は 0 を返す。負の経過時間で
     * メンテナンスを走らせると時間が逆行する。</p>
     *
     * @param lastDecisionTick 最後に決定したワールド時刻
     * @param worldTime        現在のワールド時刻
     * @return 経過 tick（0 以上）
     */
    public static long elapsedSince(long lastDecisionTick, long worldTime) {
        if (lastDecisionTick == Long.MIN_VALUE || worldTime < lastDecisionTick) {
            return 0L;
        }
        return worldTime - lastDecisionTick;
    }
}
