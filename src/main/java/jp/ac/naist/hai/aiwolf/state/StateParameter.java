package jp.ac.naist.hai.aiwolf.state;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;

/**
 * オオカミの内部状態量の定義。
 *
 * <p>状態量を enum として定義することで、追加・削除時に永続化・エクスポート・
 * ログ出力が自動的に追従する。<strong>固定的な「性格ラベル」は意図的に存在しない。</strong>
 * 個体差はこれらの連続量および学習済み行動選好が経験によって変化することでのみ生じる
 * （Personality is not stored. Personality is observed.）。</p>
 *
 * <p>初期値・ベースラインはすべての個体で共通である。</p>
 */
public enum StateParameter {

    /** 飼い主への信頼。経験によってのみ変化する関係性状態。 */
    TRUST("trust", StateCategory.RELATIONAL,
            AiWolfParameters.INITIAL_TRUST, AiWolfParameters.INITIAL_TRUST, "飼い主への信頼"),

    /** 飼い主への愛着。経験によってのみ変化する関係性状態。 */
    ATTACHMENT("attachment", StateCategory.RELATIONAL,
            AiWolfParameters.INITIAL_ATTACHMENT, AiWolfParameters.INITIAL_ATTACHMENT, "飼い主への愛着"),

    /** 恐怖感受性。イベントで急変し、ベースラインへ徐々に回帰する感情状態。 */
    FEAR("fear", StateCategory.EMOTIONAL,
            AiWolfParameters.INITIAL_FEAR, AiWolfParameters.BASELINE_FEAR, "恐怖感受性"),

    /** ストレス。イベントで急変し、ベースラインへ徐々に回帰する感情状態。 */
    STRESS("stress", StateCategory.EMOTIONAL,
            AiWolfParameters.INITIAL_STRESS, AiWolfParameters.BASELINE_STRESS, "ストレス"),

    /** 好奇心。探索行動を促進する動機付け状態。 */
    CURIOSITY("curiosity", StateCategory.MOTIVATIONAL,
            AiWolfParameters.INITIAL_CURIOSITY, AiWolfParameters.INITIAL_CURIOSITY, "好奇心"),

    /** 自律性。単独行動を促進し、近距離追従を抑制する動機付け状態。 */
    AUTONOMY("autonomy", StateCategory.MOTIVATIONAL,
            AiWolfParameters.INITIAL_AUTONOMY, AiWolfParameters.INITIAL_AUTONOMY, "自律性"),

    /** 活力。低いほど休息が選ばれやすくなる動機付け状態。 */
    ENERGY("energy", StateCategory.MOTIVATIONAL,
            AiWolfParameters.INITIAL_ENERGY, AiWolfParameters.INITIAL_ENERGY, "活力");

    /** {@code values()} の防御的コピーを避けるための不変ビュー。 */
    public static final List<StateParameter> ALL = List.of(values());

    private final String key;
    private final StateCategory category;
    private final double initialValue;
    private final double baselineValue;
    private final String description;

    StateParameter(String key,
                   StateCategory category,
                   double initialValue,
                   double baselineValue,
                   String description) {
        this.key = key;
        this.category = category;
        this.initialValue = initialValue;
        this.baselineValue = baselineValue;
        this.description = description;
    }

    /**
     * NBT / JSON / コマンドで使用する安定キーを返す。enum 名の変更に影響されない。
     *
     * @return キー文字列
     */
    public String key() {
        return key;
    }

    /**
     * 更新ダイナミクスの分類を返す。
     *
     * @return 分類
     */
    public StateCategory category() {
        return category;
    }

    /**
     * 全個体共通の初期値を返す。
     *
     * @return 初期値
     */
    public double initialValue() {
        return initialValue;
    }

    /**
     * 感情状態が時間とともに回帰するベースライン値を返す。
     * 関係性・動機付け状態では自動回帰を行わないため初期値と同じ値を返す。
     *
     * @return ベースライン値
     */
    public double baselineValue() {
        return baselineValue;
    }

    /**
     * 人間可読の説明を返す（デバッグ表示用）。
     *
     * @return 説明
     */
    public String description() {
        return description;
    }

    /**
     * 下限値を返す。
     *
     * @return 下限
     */
    public double min() {
        return AiWolfParameters.STATE_MIN;
    }

    /**
     * 上限値を返す。
     *
     * @return 上限
     */
    public double max() {
        return AiWolfParameters.STATE_MAX;
    }

    /**
     * キーから状態量を解決する（大文字小文字は無視）。
     *
     * @param key キー文字列
     * @return 一致する状態量。存在しなければ {@link Optional#empty()}
     */
    public static Optional<StateParameter> byKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (StateParameter parameter : ALL) {
            if (parameter.key.equals(normalized)) {
                return Optional.of(parameter);
            }
        }
        return Optional.empty();
    }
}
