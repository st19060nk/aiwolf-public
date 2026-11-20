package jp.ac.naist.hai.aiwolf.context;

import java.util.List;

/**
 * BehaviorScorer が参照する文脈特徴量。すべて [0, 1] に正規化されている。
 *
 * <p>状態量（{@code z = 2x - 1} で [-1,+1] へ変換）とは異なり、文脈特徴量は
 * 「その条件がどれだけ成立しているか」を表す非負の量として扱う。</p>
 */
public enum ContextFeature {

    /** 飼い主への近さ {@code exp(-d / scale)}。1 に近いほど密着している。 */
    NEAR_OWNER("near_owner"),

    /** 飼い主からの遠さ（{@code 1 - NEAR_OWNER}）。 */
    OWNER_DISTANCE_FAR("owner_distance_far"),

    /** 総合脅威スコア。 */
    THREAT("threat"),

    /** 現在 region の恐怖値（空間記憶由来）。 */
    LOCATION_FEAR("location_fear"),

    /** 飼い主が攻撃を受けている。 */
    OWNER_UNDER_ATTACK("owner_under_attack"),

    /** 自身が攻撃を受けている。 */
    WOLF_UNDER_ATTACK("wolf_under_attack"),

    /** 自身の HP 低下度。 */
    WOLF_HEALTH_LOW("wolf_health_low"),

    /** 飼い主の HP 低下度。 */
    OWNER_HEALTH_LOW("owner_health_low"),

    /** 夜である。 */
    NIGHT("night"),

    /** 地下にいる。 */
    UNDERGROUND("underground"),

    /** 飼い主が移動中である。 */
    OWNER_MOVING("owner_moving"),

    /** 直近に戦闘があった。 */
    RECENT_COMBAT("recent_combat"),

    /**
     * 緊急事態（HP が緊急閾値未満）。
     *
     * <p>if 分岐で行動を強制する代わりに、この特徴量へ非常に大きな重みを与えることで
     * 退避を優勢にする。スコア構造が保たれるため研究ログの解析が一貫する。</p>
     */
    EMERGENCY_LOW_HEALTH("emergency_low_health");

    /** {@code values()} の防御的コピーを避けるための不変ビュー。 */
    public static final List<ContextFeature> ALL = List.of(values());

    private final String id;

    ContextFeature(String id) {
        this.id = id;
    }

    /**
     * 研究ログで使用する安定 ID を返す。
     *
     * @return ID
     */
    public String id() {
        return id;
    }
}
