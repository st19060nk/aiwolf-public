package jp.ac.naist.hai.aiwolf.state;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jp.ac.naist.hai.aiwolf.core.MathSupport;

/**
 * オオカミ 1 個体の内部状態ベクトル。
 *
 * <p>すべての値は {@link StateParameter#min()}〜{@link StateParameter#max()}
 * （既定 0.0〜1.0）へ常に clamp される。更新経路を {@link #set}/{@link #add}/
 * {@link #moveTowards} に限定することで、範囲外の値が永続化されることを防ぐ。</p>
 *
 * <p>本クラスは Minecraft に依存しない。行動決定アルゴリズムの検証を
 * ゲーム外（単体テスト・シミュレーション）で行えるようにするためである。</p>
 *
 * <p>スレッド安全ではない。サーバスレッド上からのみ変更すること。</p>
 */
public final class WolfInternalState {

    /** {@link StateParameter#ordinal()} を添字とする値配列。 */
    private final double[] values;

    private WolfInternalState(double[] values) {
        this.values = values;
    }

    /**
     * 全個体共通の初期状態を生成する。
     *
     * @return 初期状態
     */
    public static WolfInternalState createInitial() {
        double[] initial = new double[StateParameter.ALL.size()];
        for (StateParameter parameter : StateParameter.ALL) {
            initial[parameter.ordinal()] = parameter.initialValue();
        }
        return new WolfInternalState(initial);
    }

    /**
     * キー→値のマップから状態を復元する。
     *
     * <p>未知のキーは無視し、欠損キーには初期値を用いる。これにより
     * 状態量の追加・削除に対して前方／後方互換性を保つ。非有限値は
     * 破損データとみなして初期値へフォールバックする。</p>
     *
     * @param rawValues キー→値のマップ（{@code null} 可）
     * @return 復元した状態
     */
    public static WolfInternalState fromValueMap(Map<String, Double> rawValues) {
        WolfInternalState state = createInitial();
        if (rawValues == null) {
            return state;
        }
        for (Map.Entry<String, Double> entry : rawValues.entrySet()) {
            Optional<StateParameter> parameter = StateParameter.byKey(entry.getKey());
            Double value = entry.getValue();
            if (parameter.isEmpty() || value == null || !Double.isFinite(value)) {
                continue;
            }
            state.set(parameter.get(), value);
        }
        return state;
    }

    /**
     * 指定状態量の現在値を返す。
     *
     * @param parameter 状態量
     * @return 現在値
     */
    public double get(StateParameter parameter) {
        return values[parameter.ordinal()];
    }

    /**
     * 値を設定する（範囲外は clamp）。
     *
     * @param parameter 状態量
     * @param value     設定値（有限値）
     * @return clamp 後に実際に設定された値
     * @throws IllegalArgumentException 値が非有限の場合
     */
    public double set(StateParameter parameter, double value) {
        double clamped = MathSupport.clamp(value, parameter.min(), parameter.max());
        values[parameter.ordinal()] = clamped;
        return clamped;
    }

    /**
     * 値に増分を加える（範囲外は clamp）。イベントによる短期的変化に用いる。
     *
     * @param parameter 状態量
     * @param delta     増分（負値可）
     * @return clamp 後に実際に設定された値
     */
    public double add(StateParameter parameter, double delta) {
        return set(parameter, get(parameter) + delta);
    }

    /**
     * 指数移動平均により目標値へ少しだけ近づける。長期的な傾向形成に用いる。
     *
     * @param parameter    状態量
     * @param target       目標値
     * @param learningRate 学習率（小さいほど変化が緩やか）
     * @return 更新後の値
     */
    public double moveTowards(StateParameter parameter, double target, double learningRate) {
        double updated = MathSupport.exponentialMovingAverage(get(parameter), target, learningRate);
        return set(parameter, updated);
    }

    /**
     * 感情状態（fear, stress）をベースラインへ 1 ステップ回帰させる。
     *
     * <p>{@code x <- x + rate * (baseline - x)}。関係性状態および動機付け状態は
     * 時間経過だけでは変化させないため、本メソッドの対象外である。</p>
     *
     * @param rate 回帰率（小さいほど緩やか）
     */
    public void relaxEmotionalStatesTowardsBaseline(double rate) {
        for (StateParameter parameter : StateParameter.ALL) {
            if (parameter.category() == StateCategory.EMOTIONAL) {
                moveTowards(parameter, parameter.baselineValue(), rate);
            }
        }
    }

    /**
     * 状態量を [-1, +1] の符号付き特徴量へ変換する（{@code z = 2x - 1}）。
     *
     * <p>0.5 を中立として扱えるようにするための変換であり、
     * BehaviorScorer の StateEffect 計算はすべてこの表現を用いる。</p>
     *
     * @param parameter 状態量
     * @return [-1, +1] の値
     */
    public double signed(StateParameter parameter) {
        return MathSupport.toSignedRange(get(parameter));
    }

    /**
     * 初期値へ完全にリセットする（{@code /wolfai reset} 用）。
     */
    public void resetToInitial() {
        for (StateParameter parameter : StateParameter.ALL) {
            values[parameter.ordinal()] = parameter.initialValue();
        }
    }

    /**
     * 独立したコピーを返す。ログのスナップショット取得などに用いる。
     *
     * @return コピー
     */
    public WolfInternalState copy() {
        return new WolfInternalState(Arrays.copyOf(values, values.length));
    }

    /**
     * キー→値の順序付きマップを返す（enum 宣言順）。
     *
     * @return 値マップ
     */
    public Map<String, Double> toValueMap() {
        Map<String, Double> map = new LinkedHashMap<>();
        for (StateParameter parameter : StateParameter.ALL) {
            map.put(parameter.key(), values[parameter.ordinal()]);
        }
        return map;
    }

    /** @return trust の現在値 */
    public double trust() {
        return get(StateParameter.TRUST);
    }

    /** @return attachment の現在値 */
    public double attachment() {
        return get(StateParameter.ATTACHMENT);
    }

    /** @return fear の現在値 */
    public double fear() {
        return get(StateParameter.FEAR);
    }

    /** @return curiosity の現在値 */
    public double curiosity() {
        return get(StateParameter.CURIOSITY);
    }

    /** @return autonomy の現在値 */
    public double autonomy() {
        return get(StateParameter.AUTONOMY);
    }

    /** @return stress の現在値 */
    public double stress() {
        return get(StateParameter.STRESS);
    }

    /** @return energy の現在値 */
    public double energy() {
        return get(StateParameter.ENERGY);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WolfInternalState state)) {
            return false;
        }
        return Arrays.equals(values, state.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("WolfInternalState{");
        for (StateParameter parameter : StateParameter.ALL) {
            if (parameter.ordinal() > 0) {
                builder.append(", ");
            }
            builder.append(parameter.key())
                    .append('=')
                    .append(MathSupport.round(values[parameter.ordinal()], 3));
        }
        return builder.append('}').toString();
    }
}
