package jp.ac.naist.hai.aiwolf.study;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;

/**
 * プレイヤーのサンプルの「前回の位置」の管理（Phase 25 P0-4）。<strong>Minecraft に依存しない。</strong>
 *
 * <p>移動量は前回のサンプルとの差なので、前回が<strong>同じセッションの直前のサンプル</strong>でなければ意味がない。
 * 以前は前回の位置を「セッションのない状態でサンプルの tick が来たとき」にしか消していなかったため、
 * セッションを止めて 1 秒以内に次を始めると、前のセッションの最後の位置から移動量を計算していた。</p>
 *
 * <p>ここでは前回の位置にセッション ID・時刻・オオカミのワールドを持たせ、次のどれかなら移動量を計算せず、
 * その理由（{@link Missing}）を返す。理由はログの {@code motion.noBaselineReason} に書く。</p>
 *
 * @param <T> 位置の型（Minecraft 側では Vec3d の組）
 */
public final class MotionBaseline<T> {

    /**
     * 前回からこれより長く空いたら移動量を計算しない [ticks]（サンプル間隔の 3 倍）。
     * 飼い主のログアウトやオオカミのアンロードで空いた区間の移動を、1 回の移動として数えないため。
     * 計測の設定で研究条件ではないので、変えても PARAMETER_SET_VERSION は上げない。
     */
    public static final long MAX_BASELINE_GAP_TICKS = 3L * AiWolfParameters.PLAYER_SAMPLE_INTERVAL_TICKS;

    /** 移動量を計算しなかった理由。 */
    public enum Missing {
        /** この個体の最初のサンプル（またはリセットの直後）。 */
        FIRST_SAMPLE,
        /** 前回のサンプルは別のセッションのもの。 */
        NEW_SESSION,
        /** 前回から {@link #MAX_BASELINE_GAP_TICKS} より長く空いた（または時刻が戻った）。 */
        GAP,
        /** オオカミが別のワールド（ディメンション）へ移った。 */
        WORLD_CHANGED
    }

    /**
     * 観測の結果。
     *
     * @param previous      前回の位置（移動量を計算できないなら {@code null}）
     * @param intervalTicks 前回からの tick 数（計算できないなら 0）
     * @param missing       計算できない理由（計算できるなら {@code null}）
     */
    public record Result<T>(T previous, long intervalTicks, Missing missing) {
        /** @return 移動量を計算できるなら true */
        public boolean hasBaseline() {
            return missing == null;
        }
    }

    private record Entry<T>(String sessionId, long worldTime, Object world, Object owner, T position) {
    }

    private final Map<Object, Entry<T>> entries = new HashMap<>();

    /**
     * 新しいサンプルを記録し、前回との比較の可否を返す。
     *
     * @param key       個体のキー（オオカミの UUID）
     * @param sessionId 現在のセッション ID
     * @param worldTime 現在のワールド時刻
     * @param world     オオカミのいるワールド（同一性で比べる）
     * @param owner     飼い主のキー（切断時に消すため）
     * @param position  現在の位置
     * @return 結果
     */
    public Result<T> observe(Object key, String sessionId, long worldTime, Object world, Object owner, T position) {
        Objects.requireNonNull(sessionId, "sessionId");
        Entry<T> previous = entries.put(key, new Entry<>(sessionId, worldTime, world, owner, position));
        if (previous == null) {
            return new Result<>(null, 0L, Missing.FIRST_SAMPLE);
        }
        if (!previous.sessionId().equals(sessionId)) {
            return new Result<>(null, 0L, Missing.NEW_SESSION);
        }
        if (previous.world() != world) {
            return new Result<>(null, 0L, Missing.WORLD_CHANGED);
        }
        long interval = worldTime - previous.worldTime();
        if (interval <= 0 || interval > MAX_BASELINE_GAP_TICKS) {
            return new Result<>(null, 0L, Missing.GAP);
        }
        return new Result<>(previous.position(), interval, null);
    }

    /** すべて消す（セッションの開始・終了、サーバ停止）。 */
    public void clear() {
        entries.clear();
    }

    /**
     * 飼い主が一致する記録を消す（飼い主の切断）。
     *
     * @param owner 飼い主のキー
     */
    public void forgetOwner(Object owner) {
        Predicate<Entry<T>> match = entry -> Objects.equals(entry.owner(), owner);
        entries.values().removeIf(match);
    }

    /** @return 記録している個体の数（テスト用） */
    public int size() {
        return entries.size();
    }
}
