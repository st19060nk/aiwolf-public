package jp.ac.naist.hai.aiwolf.memory;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;

/**
 * 量子化された空間領域。
 *
 * <p>正確なブロック座標をそのまま記憶すると、1 ブロックずれただけで「同じ場所」と
 * 認識できなくなる。そこで一定サイズ（既定 24 ブロック）の立方体へ量子化し、
 * その単位で恐怖・安心を蓄積する。</p>
 *
 * <p>Minecraft に依存しないよう、ディメンションは文字列 ID として保持する。</p>
 *
 * @param dimension ディメンション ID（例 {@code "minecraft:overworld"}）
 * @param regionX   量子化後の X
 * @param regionY   量子化後の Y
 * @param regionZ   量子化後の Z
 */
public record SpatialRegion(String dimension, int regionX, int regionY, int regionZ) {

    /**
     * ブロック座標から領域を求める。
     *
     * @param dimension ディメンション ID
     * @param x         ブロック X
     * @param y         ブロック Y
     * @param z         ブロック Z
     * @return 量子化された領域
     */
    public static SpatialRegion of(String dimension, double x, double y, double z) {
        return of(dimension, x, y, z, AiWolfParameters.SPATIAL_REGION_SIZE);
    }

    /**
     * 領域サイズを明示してブロック座標から領域を求める。
     *
     * @param dimension  ディメンション ID
     * @param x          ブロック X
     * @param y          ブロック Y
     * @param z          ブロック Z
     * @param regionSize 領域 1 辺のサイズ（正の値）
     * @return 量子化された領域
     */
    public static SpatialRegion of(String dimension, double x, double y, double z, int regionSize) {
        if (regionSize <= 0) {
            throw new IllegalArgumentException("regionSize must be > 0 but was " + regionSize);
        }
        return new SpatialRegion(dimension,
                Math.floorDiv((int) Math.floor(x), regionSize),
                Math.floorDiv((int) Math.floor(y), regionSize),
                Math.floorDiv((int) Math.floor(z), regionSize));
    }

    /**
     * 研究ログ用の安定キーを返す。
     *
     * @return {@code "<dimension>:<rx>,<ry>,<rz>"} 形式のキー
     */
    public String key() {
        return dimension + ":" + regionX + "," + regionY + "," + regionZ;
    }
}
