package jp.ac.naist.hai.aiwolf.core;

/**
 * 「飼い主が近くにいる」の唯一の定義。Minecraft に依存しない。
 *
 * <p><strong>なぜ 1 か所にまとめるのか。</strong>同じ「近くにいる」という概念に
 * 別々の条件を書くと、矛盾した記録が生まれる。実際に起きていたのは次の 2 つである。</p>
 *
 * <ul>
 *   <li>文脈生成は距離 48 block を見ていたのに、出来事の検出は同じワールドにいるかどうかしか
 *       見ていなかった。数百 block 離れていても「飼い主のそばで被弾した」
 *       （{@code PLAYER_DAMAGED_NEAR_WOLF}）として記録されていた。</li>
 *   <li>同じ理由で、同じワールドにいる限り {@code LONG_SEPARATION} が永久に成立せず、
 *       したがって {@code REUNION} も一度も起きなかった。
 *       ネザーへ行くなどディメンションを変えない限り、分離と再会は観測されなかった。</li>
 * </ul>
 *
 * <p>距離の閾値は {@link AiWolfParameters#OWNER_PRESENCE_RADIUS} 1 つだけを使う。
 * 文脈・出来事検出・分離判定がすべて同じ値を参照する。</p>
 */
public final class OwnerPresence {

    private OwnerPresence() {
        throw new AssertionError("utility class");
    }

    /**
     * 飼い主が近傍にいるかを判定する。
     *
     * @param ownerExists 飼い主エンティティが存在するか
     * @param ownerAlive  飼い主が生存しているか
     * @param sameWorld   オオカミと同じワールド（ディメンション）にいるか
     * @param distance    オオカミとの距離 [blocks]
     * @return 近傍にいれば true
     */
    public static boolean isNearby(boolean ownerExists, boolean ownerAlive,
                                   boolean sameWorld, double distance) {
        return isNearby(ownerExists, ownerAlive, sameWorld, distance,
                AiWolfParameters.OWNER_PRESENCE_RADIUS);
    }

    /**
     * 半径を明示して判定する。テストと、将来の条件別半径のための入口。
     *
     * <p>境界はちょうど半径の位置を「近傍」に含む（{@code distance <= radius}）。
     * 境界の扱いを実装ごとに変えないため、ここでだけ定義する。</p>
     *
     * @param ownerExists 飼い主エンティティが存在するか
     * @param ownerAlive  飼い主が生存しているか
     * @param sameWorld   同じワールドにいるか
     * @param distance    距離 [blocks]。{@link Double#NaN} は不在として扱う
     * @param radius      近傍とみなす半径 [blocks]
     * @return 近傍にいれば true
     */
    public static boolean isNearby(boolean ownerExists, boolean ownerAlive,
                                   boolean sameWorld, double distance, double radius) {
        if (!ownerExists || !ownerAlive || !sameWorld) {
            return false;
        }
        if (Double.isNaN(distance)) {
            return false;
        }
        return distance <= radius;
    }
}
