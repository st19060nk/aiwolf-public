package jp.ac.naist.hai.aiwolf.runtime;

import java.util.Optional;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.event.WolfEventType;

/**
 * 長期分離と再会の判定。Minecraft に依存しない。
 *
 * <p>入力は「飼い主がいま近傍にいるか」だけである。<strong>近傍の定義に距離が
 * 入っていないと、この状態機械は一度も動かない。</strong>以前は同じワールドに
 * いるかどうかだけを見ていたため、飼い主が数百 block 離れていても「近くにいる」と
 * 判定され、ネザーへ行くなどディメンションを変えない限り
 * {@code LONG_SEPARATION} も {@code REUNION} も発生しなかった。</p>
 *
 * <p>分離は 1 回の離脱につき 1 度しか報告しない。再会も分離を報告したあとにだけ
 * 発生する。離れるたび近づくたびにイベントを出すと、拠点の周りを行き来しているだけで
 * 関係性が上下し、履歴として意味を持たなくなる。</p>
 */
public final class SeparationTracker {

    private long ownerAbsentSinceTick = Long.MIN_VALUE;
    private boolean separationReported;

    /**
     * 現在の在否を与えて、発火すべきイベントを返す。
     *
     * @param ownerNearby 飼い主が近傍にいるか
     * @param worldTime   現在のワールド時刻
     * @return 発火すべきイベント。なければ {@link Optional#empty()}
     */
    public Optional<WolfEventType> update(boolean ownerNearby, long worldTime) {
        if (!ownerNearby) {
            if (ownerAbsentSinceTick == Long.MIN_VALUE) {
                ownerAbsentSinceTick = worldTime;
                return Optional.empty();
            }
            if (!separationReported
                    && worldTime - ownerAbsentSinceTick >= AiWolfParameters.LONG_SEPARATION_TICKS) {
                separationReported = true;
                return Optional.of(WolfEventType.LONG_SEPARATION);
            }
            return Optional.empty();
        }

        boolean reunited = separationReported;
        ownerAbsentSinceTick = Long.MIN_VALUE;
        separationReported = false;
        return reunited ? Optional.of(WolfEventType.REUNION) : Optional.empty();
    }

    /** @return 長期分離を報告済みか（永続化用） */
    public boolean separationReported() {
        return separationReported;
    }

    /**
     * 分離の報告済みフラグを復元する。
     *
     * <p>不在の開始時刻は復元しない。ワールドが閉じている間の時間は
     * 「飼い主と離れていた時間」ではないためである。</p>
     *
     * @param separationReported 保存されていたフラグ
     */
    public void restore(boolean separationReported) {
        this.separationReported = separationReported;
        this.ownerAbsentSinceTick = Long.MIN_VALUE;
    }
}
