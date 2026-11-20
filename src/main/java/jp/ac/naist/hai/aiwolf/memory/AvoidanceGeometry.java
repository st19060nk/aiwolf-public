package jp.ac.naist.hai.aiwolf.memory;

import java.util.ArrayList;
import java.util.List;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.Vector3;

/**
 * 恐怖を記憶した領域から離れる方向の計算。Minecraft に依存しない。
 *
 * <p><strong>なぜ必要か。</strong>「現在位置からランダムに遠ざかる」だけでは、
 * 研究上意図した「その場所を避ける」行動にならない。それは退避（RETREAT）と
 * 区別できず、空間記憶が行動へ結び付いている証拠にならない。
 * AVOID_LOCATION は<strong>恐怖領域の中心から離れる</strong>方向へ動かなければならない。</p>
 *
 * <p>方向だけを決め、実際に歩ける地点かどうかの判定は Minecraft 側に任せる。
 * 崖・溶岩・壁の内部などは経路探索で弾かれる必要があるため、
 * 候補を複数出して呼び出し側が順に試せる形にしてある。</p>
 */
public final class AvoidanceGeometry {

    /** 候補方向を左右に振る角度 [rad]。真正面だけだと壁で詰まる。 */
    private static final double[] CANDIDATE_ANGLES = {
            0.0, Math.PI / 6.0, -Math.PI / 6.0, Math.PI / 3.0, -Math.PI / 3.0
    };

    /** 中心と位置が一致したときに使う既定の逃走方向（+X）。決定論的にするため固定する。 */
    private static final Vector3 FALLBACK_DIRECTION = new Vector3(1.0, 0.0, 0.0);

    private AvoidanceGeometry() {
        throw new AssertionError("utility class");
    }

    /**
     * 領域の中心座標を返す。
     *
     * @param region     領域
     * @param regionSize 領域 1 辺のサイズ [blocks]
     * @return 中心座標
     */
    public static Vector3 regionCenter(SpatialRegion region, int regionSize) {
        double half = regionSize / 2.0;
        return new Vector3(
                region.regionX() * (double) regionSize + half,
                region.regionY() * (double) regionSize + half,
                region.regionZ() * (double) regionSize + half);
    }

    /**
     * 既定の領域サイズで中心座標を返す。
     *
     * @param region 領域
     * @return 中心座標
     */
    public static Vector3 regionCenter(SpatialRegion region) {
        return regionCenter(region, AiWolfParameters.SPATIAL_REGION_SIZE);
    }

    /**
     * 恐怖領域の中心から離れる水平方向の単位ベクトルを返す。
     *
     * <p>中心と現在位置が一致する場合は {@link #FALLBACK_DIRECTION} を返す。
     * ここで 0 除算による NaN を navigation へ渡すと挙動が壊れるため、
     * <strong>必ず有限で長さ 1 のベクトルを返す</strong>。</p>
     *
     * @param wolfPosition 現在位置
     * @param fearedCenter 恐怖領域の中心
     * @return 脱出方向（水平、単位長）
     */
    public static Vector3 escapeDirection(Vector3 wolfPosition, Vector3 fearedCenter) {
        Vector3 away = wolfPosition.minus(fearedCenter).horizontalNormalized();
        return away.equals(Vector3.ZERO) ? FALLBACK_DIRECTION : away;
    }

    /**
     * 脱出方向をもとに移動先の候補を生成する。
     *
     * <p>先頭が最も素直に「中心から離れる」候補で、以降は左右へ振った代替候補になる。
     * 呼び出し側は歩ける地点が見つかるまで順に試すこと。</p>
     *
     * @param wolfPosition 現在位置
     * @param fearedCenter 恐怖領域の中心
     * @param distance     進む距離 [blocks]
     * @return 候補座標のリスト（空にならない）
     */
    public static List<Vector3> escapeCandidates(Vector3 wolfPosition, Vector3 fearedCenter,
                                                 double distance) {
        Vector3 direction = escapeDirection(wolfPosition, fearedCenter);
        List<Vector3> candidates = new ArrayList<>(CANDIDATE_ANGLES.length);
        for (double angle : CANDIDATE_ANGLES) {
            Vector3 rotated = direction.rotatedAroundY(angle).horizontalNormalized();
            Vector3 target = wolfPosition.plus(rotated.scale(distance));
            if (target.isFinite()) {
                candidates.add(target);
            }
        }
        if (candidates.isEmpty()) {
            candidates.add(wolfPosition.plus(FALLBACK_DIRECTION.scale(distance)));
        }
        return candidates;
    }

    /**
     * 候補が本当に中心から遠ざかっているかを判定する。
     *
     * <p>呼び出し側が経路探索の結果を採用する前に確認するためのもの。
     * 経路探索が「歩けるが中心に近づく」地点を返した場合、それは回避行動として不適切である。</p>
     *
     * @param wolfPosition 現在位置
     * @param fearedCenter 恐怖領域の中心
     * @param candidate    候補座標
     * @return 中心からの水平距離が増えていれば true
     */
    public static boolean movesAwayFrom(Vector3 wolfPosition, Vector3 fearedCenter, Vector3 candidate) {
        double before = wolfPosition.minus(fearedCenter).horizontalLength();
        double after = candidate.minus(fearedCenter).horizontalLength();
        return after > before;
    }
}
