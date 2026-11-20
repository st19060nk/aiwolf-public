package jp.ac.naist.hai.aiwolf.core;

/**
 * 数値ユーティリティ。Minecraft に依存しないため、単体テストおよび
 * 将来的な Python / JVM シミュレーションから直接利用できる。
 *
 * <p>本クラスは状態更新で使用する演算（clamp / 線形補間 / 指数移動平均）を
 * 一箇所に集約し、実装ごとに丸め方や境界処理がぶれることを防ぐ。</p>
 *
 * <p><strong>超越関数には {@code Math} ではなく {@link StrictMath} を用いる。</strong>
 * {@code Math.exp} / {@code Math.pow} は JVM 実装や CPU によって最終桁が 1 ULP ずれることが
 * 許されており、Softmax の確率がわずかに変わるだけで乱数値との比較が逆転し、選ばれる行動が
 * 変わりうる。{@code StrictMath} は全プラットフォームでビット単位に同一の結果を返すため、
 * 「同一イベント列 + 同一シード ⇒ 完全に同一の行動列」が OS やマシンを跨いでも成立する。
 * 研究データを別環境で再解析・再現する場面で必要になる。</p>
 */
public final class MathSupport {

    private MathSupport() {
        throw new AssertionError("utility class");
    }

    /**
     * 値を [min, max] へ丸める。
     *
     * @param value 対象値（有限値でなければならない）
     * @param min   下限
     * @param max   上限（{@code min} 以上）
     * @return 丸めた値
     * @throws IllegalArgumentException 値が NaN / 無限大、または {@code min > max} の場合
     */
    public static double clamp(double value, double min, double max) {
        requireFinite(value, "value");
        if (min > max) {
            throw new IllegalArgumentException("min(" + min + ") must be <= max(" + max + ")");
        }
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    /**
     * 値を [0.0, 1.0] へ丸める。内部状態量の既定レンジ用。
     *
     * @param value 対象値
     * @return 丸めた値
     */
    public static double clamp01(double value) {
        return clamp(value, AiWolfParameters.STATE_MIN, AiWolfParameters.STATE_MAX);
    }

    /**
     * 線形補間。
     *
     * @param from 係数 0 のときの値
     * @param to   係数 1 のときの値
     * @param t    補間係数（[0,1] へ丸められる）
     * @return 補間値
     */
    public static double lerp(double from, double to, double t) {
        double clamped = clamp(t, 0.0, 1.0);
        return from + (to - from) * clamped;
    }

    /**
     * 指数移動平均（EMA）による 1 ステップ更新。
     *
     * <p>{@code next = current + learningRate * (target - current)}。
     * 学習率を十分小さくすることで「1 イベントで性格が激変しない」という
     * 研究上の要件を満たす。</p>
     *
     * @param current      現在値
     * @param target       目標値
     * @param learningRate 学習率（[0,1] へ丸められる）
     * @return 更新後の値
     */
    public static double exponentialMovingAverage(double current, double target, double learningRate) {
        requireFinite(current, "current");
        requireFinite(target, "target");
        double rate = clamp(learningRate, 0.0, 1.0);
        return current + (target - current) * rate;
    }

    /**
     * [0, 1] の状態量を [-1, +1] の符号付き特徴量へ変換する（{@code z = 2x - 1}）。
     *
     * <p>0.5 を「中立」として扱えるようにするための変換。BehaviorScorer の
     * StateEffect はすべてこの表現で計算する。</p>
     *
     * @param value [0,1] の値
     * @return [-1,+1] の値
     */
    public static double toSignedRange(double value) {
        return 2.0 * clamp01(value) - 1.0;
    }

    /**
     * 値を [-1, +1] へ丸める（選好ベクトルや MemoryEffect 用）。
     *
     * @param value 対象値
     * @return 丸めた値
     */
    public static double clipSigned(double value) {
        return clamp(value, -1.0, 1.0);
    }

    /**
     * 距離に対する指数減衰 {@code exp(-distance / scale)} を返す。
     *
     * <p>距離を [0, 1] の「近さ」へ正規化するために用いる。負の距離は 0 として扱う。</p>
     *
     * @param distance 距離（負値は 0 とみなす）
     * @param scale    減衰スケール（正の値）
     * @return [0, 1] の近さ
     */
    public static double proximity(double distance, double scale) {
        requireFinite(distance, "distance");
        if (scale <= 0.0) {
            throw new IllegalArgumentException("scale must be > 0 but was " + scale);
        }
        double d = Math.max(0.0, distance);
        return StrictMath.exp(-d / scale);
    }

    /**
     * 数値的に安定な softmax を計算する。
     *
     * <p>{@code exp((score - max) / temperature)} を用いることでオーバーフローを避ける。
     * 入力が空の場合は空配列を返す。</p>
     *
     * @param scores      スコア配列
     * @param temperature 温度（正の値）
     * @return 合計 1 の確率配列
     */
    public static double[] softmax(double[] scores, double temperature) {
        if (temperature <= 0.0) {
            throw new IllegalArgumentException("temperature must be > 0 but was " + temperature);
        }
        if (scores.length == 0) {
            return new double[0];
        }
        double max = Double.NEGATIVE_INFINITY;
        for (double score : scores) {
            requireFinite(score, "score");
            max = Math.max(max, score);
        }
        double[] exponentials = new double[scores.length];
        double sum = 0.0;
        for (int i = 0; i < scores.length; i++) {
            exponentials[i] = StrictMath.exp((scores[i] - max) / temperature);
            sum += exponentials[i];
        }
        if (sum <= 0.0 || !Double.isFinite(sum)) {
            // 数値的に破綻した場合は一様分布へフォールバックする。
            double uniform = 1.0 / scores.length;
            double[] fallback = new double[scores.length];
            java.util.Arrays.fill(fallback, uniform);
            return fallback;
        }
        for (int i = 0; i < exponentials.length; i++) {
            exponentials[i] /= sum;
        }
        return exponentials;
    }

    /**
     * 指定小数桁で丸める。研究ログ／エクスポートの桁数を揃えるために使用する。
     *
     * @param value    対象値
     * @param decimals 小数桁数（0 以上）
     * @return 丸めた値
     */
    public static double round(double value, int decimals) {
        requireFinite(value, "value");
        if (decimals < 0) {
            throw new IllegalArgumentException("decimals must be >= 0");
        }
        double factor = StrictMath.pow(10.0, decimals);
        return Math.round(value * factor) / factor;
    }

    /**
     * 有限値であることを検証する。
     *
     * @param value 対象値
     * @param name  例外メッセージ用の名前
     * @throws IllegalArgumentException NaN または無限大の場合
     */
    public static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be a finite number but was " + value);
        }
    }
}
