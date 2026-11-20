package jp.ac.naist.hai.aiwolf.event;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.MathSupport;

/**
 * 記録対象となる出来事の種別。
 *
 * <p>各種別は既定の valence（快・不快の向き）と arousal（強度）を持つ。
 * salience（どれだけ長期的に残すか）はここから導出される。
 * <strong>これらの値はすべての個体で共通</strong>であり、個体差は
 * 「どの出来事をどれだけ経験したか」からのみ生じる。</p>
 *
 * <p>全 tick を記録してはならない。意味のある状態変化だけをここで定義する。</p>
 */
public enum WolfEventType {

    /** 撫でられた。 */
    PET("pet", 0.5, 0.2, 0.0),

    /** 餌をもらった。 */
    FEED("feed", 0.4, 0.2, 0.0),

    /** 飼い主に守られた（飼い主が敵を倒してくれた）。 */
    PLAYER_PROTECTED_WOLF("player_protected_wolf", 0.9, 0.7, 0.10),

    /** 自身がダメージを受けた。 */
    WOLF_DAMAGED("wolf_damaged", -0.7, 0.6, 0.0),

    /** 探索中に自身がダメージを受けた。 */
    WOLF_DAMAGED_DURING_EXPLORATION("wolf_damaged_during_exploration", -0.6, 0.7, 0.0),

    /** 瀕死になった。 */
    NEAR_DEATH("near_death", -1.0, 0.9, 0.20),

    /** 自身の HP が低下した。 */
    WOLF_LOW_HEALTH("wolf_low_health", -0.8, 0.8, 0.05),

    /** 近くで飼い主がダメージを受けた。 */
    PLAYER_DAMAGED_NEAR_WOLF("player_damaged_near_wolf", -0.5, 0.6, 0.0),

    /** 敵性 MOB に遭遇した。 */
    MONSTER_ENCOUNTER("monster_encounter", -0.3, 0.5, 0.0),

    /** 飼い主と一緒に戦闘した。 */
    PLAYER_AND_WOLF_COMBAT("player_and_wolf_combat", 0.2, 0.7, 0.0),

    /** 防衛に成功した。 */
    DEFEND_SUCCESS("defend_success", 0.7, 0.7, 0.05),

    /** 単独での探索を安全に終えた。 */
    SAFE_SOLO_EXPLORATION("safe_solo_exploration", 0.4, 0.3, 0.0),

    /** 飼い主と長時間離れていた。 */
    LONG_SEPARATION("long_separation", -0.4, 0.3, 0.0),

    /** 長時間の分離のあと飼い主と再会した。 */
    REUNION("reunion", 0.6, 0.5, 0.05),

    /** 新しいバイオームへ到達した。 */
    NEW_BIOME("new_biome", 0.3, 0.4, 0.0),

    /** 飼い主の近くで休んだ。 */
    SLEEP_NEAR_PLAYER("sleep_near_player", 0.3, 0.1, 0.0);

    /** {@code values()} の防御的コピーを避けるための不変ビュー。 */
    public static final List<WolfEventType> ALL = List.of(values());

    private final String id;
    private final double defaultValence;
    private final double defaultArousal;
    private final double salienceBonus;

    WolfEventType(String id, double defaultValence, double defaultArousal, double salienceBonus) {
        this.id = id;
        this.defaultValence = defaultValence;
        this.defaultArousal = defaultArousal;
        this.salienceBonus = salienceBonus;
    }

    /**
     * 永続化・研究ログで使用する安定 ID を返す。
     *
     * @return ID
     */
    public String id() {
        return id;
    }

    /**
     * 既定の valence を返す（{@code -1.0}〜{@code +1.0}）。
     *
     * @return valence
     */
    public double defaultValence() {
        return defaultValence;
    }

    /**
     * 既定の arousal を返す（{@code 0.0}〜{@code 1.0}）。
     *
     * @return arousal
     */
    public double defaultArousal() {
        return defaultArousal;
    }

    /**
     * 重要イベントに与える salience の加算分を返す。
     *
     * @return salience ボーナス
     */
    public double salienceBonus() {
        return salienceBonus;
    }

    /**
     * valence と arousal から salience を計算する。
     *
     * <p>{@code salience = 0.5 * |valence| + 0.5 * arousal + bonus}（[0,1] へ clamp）。</p>
     *
     * @param valence valence
     * @param arousal arousal
     * @return salience
     */
    public double salienceOf(double valence, double arousal) {
        double raw = AiWolfParameters.SALIENCE_WEIGHT_VALENCE * Math.abs(valence)
                + AiWolfParameters.SALIENCE_WEIGHT_AROUSAL * MathSupport.clamp01(arousal)
                + salienceBonus;
        return MathSupport.clamp01(raw);
    }

    /**
     * 既定値から salience を計算する。
     *
     * @return salience
     */
    public double defaultSalience() {
        return salienceOf(defaultValence, defaultArousal);
    }

    /**
     * ID から種別を解決する（大文字小文字は無視）。
     *
     * @param id 種別 ID
     * @return 一致する種別。存在しなければ {@link Optional#empty()}
     */
    public static Optional<WolfEventType> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (WolfEventType type : ALL) {
            if (type.id.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
