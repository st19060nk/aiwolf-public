package jp.ac.naist.hai.aiwolf.learning;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.MathSupport;

/**
 * 学習済み行動選好ベクトル。長期的な個体差を生み出す中核データ。
 *
 * <p><strong>初期値はすべての個体・すべての行動で 0 である。</strong>
 * 個体差は「何をどれだけ経験したか」からのみ生じなければならない。
 * ここに固定的な性格ラベルや個体別の初期バイアスを入れてはならない。</p>
 *
 * <p>更新式（全個体共通）：</p>
 * <pre>
 * P_b &lt;- clip( (1 - lambda) * P_b + eta * salience * impact(event, b),  -1, +1 )
 * </pre>
 *
 * <p>学習率 eta は十分小さく設定し、1 回の出来事で行動傾向が激変しないようにする。</p>
 *
 * <p>スレッド安全ではない。サーバスレッド上からのみ変更すること。</p>
 */
public final class PreferenceVector {

    /** {@link BehaviorType#ordinal()} を添字とする選好配列。 */
    private final double[] preferences;

    private PreferenceVector(double[] preferences) {
        this.preferences = preferences;
    }

    /**
     * すべて 0 の選好ベクトルを生成する。
     *
     * @return 初期選好ベクトル
     */
    public static PreferenceVector createInitial() {
        return new PreferenceVector(new double[BehaviorType.ALL.size()]);
    }

    /**
     * 行動 ID → 値のマップから復元する。未知のキーは無視し、欠損は 0 とする。
     *
     * @param rawValues 行動 ID → 値
     * @return 復元した選好ベクトル
     */
    public static PreferenceVector fromValueMap(Map<String, Double> rawValues) {
        PreferenceVector vector = createInitial();
        if (rawValues == null) {
            return vector;
        }
        for (Map.Entry<String, Double> entry : rawValues.entrySet()) {
            Double value = entry.getValue();
            if (value == null || !Double.isFinite(value)) {
                continue;
            }
            BehaviorType.byId(entry.getKey())
                    .ifPresent(behavior -> vector.set(behavior, value));
        }
        return vector;
    }

    /**
     * 指定行動の選好値を返す。
     *
     * @param behavior 行動
     * @return [-1,+1] の選好値
     */
    public double get(BehaviorType behavior) {
        return preferences[behavior.ordinal()];
    }

    /**
     * 選好値を直接設定する（永続化からの復元・テスト用）。範囲外はクリップされる。
     *
     * @param behavior 行動
     * @param value    設定値
     * @return クリップ後の値
     */
    public double set(BehaviorType behavior, double value) {
        double clipped = MathSupport.clamp(value,
                AiWolfParameters.PREFERENCE_MIN, AiWolfParameters.PREFERENCE_MAX);
        preferences[behavior.ordinal()] = clipped;
        return clipped;
    }

    /**
     * イベント 1 件を選好ベクトルへ反映する。
     *
     * <p>影響行列に現れない行動にも忘却項 {@code (1 - lambda)} は適用される。
     * これにより、経験されない行動の選好は時間とともに 0 へ戻る。</p>
     *
     * @param eventImpacts 行動ごとの影響度（全個体共通の行列から得たもの）
     * @param salience     出来事の重要度 [0,1]
     * @param decayLambda  忘却係数 lambda
     * @param learningRate 学習率 eta
     */
    public void applyEvent(Map<BehaviorType, Double> eventImpacts,
                           double salience,
                           double decayLambda,
                           double learningRate) {
        double clampedSalience = MathSupport.clamp01(salience);
        double lambda = MathSupport.clamp(decayLambda, 0.0, 1.0);
        double eta = MathSupport.clamp(learningRate, 0.0, 1.0);
        for (BehaviorType behavior : BehaviorType.ALL) {
            double impact = 0.0;
            if (eventImpacts != null) {
                Double raw = eventImpacts.get(behavior);
                if (raw != null && Double.isFinite(raw)) {
                    impact = raw;
                }
            }
            double updated = (1.0 - lambda) * get(behavior) + eta * clampedSalience * impact;
            set(behavior, updated);
        }
    }

    /**
     * すべての選好を 0 へ戻す（{@code /wolfai reset} 用）。
     */
    public void resetToInitial() {
        Arrays.fill(preferences, 0.0);
    }

    /**
     * 独立したコピーを返す。
     *
     * @return コピー
     */
    public PreferenceVector copy() {
        return new PreferenceVector(Arrays.copyOf(preferences, preferences.length));
    }

    /**
     * 行動 ID → 値の順序付きマップを返す（enum 宣言順）。
     *
     * @return 値マップ
     */
    public Map<String, Double> toValueMap() {
        Map<String, Double> map = new LinkedHashMap<>();
        for (BehaviorType behavior : BehaviorType.ALL) {
            map.put(behavior.id(), preferences[behavior.ordinal()]);
        }
        return map;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PreferenceVector vector)) {
            return false;
        }
        return Arrays.equals(preferences, vector.preferences);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(preferences);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("PreferenceVector{");
        for (BehaviorType behavior : BehaviorType.ALL) {
            if (behavior.ordinal() > 0) {
                builder.append(", ");
            }
            builder.append(behavior.id())
                    .append('=')
                    .append(MathSupport.round(preferences[behavior.ordinal()], 4));
        }
        return builder.append('}').toString();
    }
}
