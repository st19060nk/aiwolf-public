package jp.ac.naist.hai.aiwolf.behavior;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * マクロ行動（研究で分析対象とする行動）。
 *
 * <p>行動は 2 階層に分離されている。本 enum は「研究ログ上で分布を集計する行動」であり、
 * {@code LOOK_AT_PLAYER} のような表現的な動作は {@link ExpressionBehaviorType} 側に置く。
 * これにより「FOLLOW_CLOSE が 30%、EXPLORE が 40%」といった明確な定量分析が可能になる。</p>
 *
 * <p>MVP ではこの 8 種類に限定する。</p>
 */
public enum BehaviorType {

    /** 飼い主のごく近くに留まって追従する。 */
    FOLLOW_CLOSE("follow_close"),

    /** 通常の距離で飼い主に追従する。 */
    FOLLOW_NORMAL("follow_normal"),

    /** 近傍を単独で探索する。 */
    EXPLORE_NEARBY("explore_nearby"),

    /** 飼い主へ能動的に接近する。 */
    APPROACH_PLAYER("approach_player"),

    /** 飼い主を守って敵と交戦する。 */
    DEFEND_PLAYER("defend_player"),

    /** 脅威から退避する。 */
    RETREAT("retreat"),

    /** 恐怖記憶のある region を回避する。 */
    AVOID_LOCATION("avoid_location"),

    /** 飼い主の近くで休息して活力を回復する。 */
    REST_NEAR_PLAYER("rest_near_player");

    /** {@code values()} の防御的コピーを避けるための不変ビュー。 */
    public static final List<BehaviorType> ALL = List.of(values());

    private final String id;

    BehaviorType(String id) {
        this.id = id;
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
     * ID から行動を解決する（大文字小文字は無視）。
     *
     * @param id 行動 ID
     * @return 一致する行動。存在しなければ {@link Optional#empty()}
     */
    public static Optional<BehaviorType> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (BehaviorType behavior : ALL) {
            if (behavior.id.equals(normalized)) {
                return Optional.of(behavior);
            }
        }
        return Optional.empty();
    }
}
