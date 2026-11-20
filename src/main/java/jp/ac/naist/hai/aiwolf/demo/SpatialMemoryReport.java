package jp.ac.naist.hai.aiwolf.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.memory.RegionMemory;
import jp.ac.naist.hai.aiwolf.memory.SpatialMemory;
import jp.ac.naist.hai.aiwolf.memory.SpatialRegion;

/**
 * {@code /wolfai memory spatial} の出力を組み立てる。<strong>Minecraft に依存しない。</strong>
 *
 * <p>空間記憶を読むだけで変更しない（{@link SpatialMemory#get} / {@link SpatialMemory#regions}
 * は読み取り専用のビューを返す）。表示する恐怖値は、行動計算が実際に使う
 * 実効恐怖値（{@link RegionMemory#effectiveFear()} = fear − 0.5 × positive）である。</p>
 */
public final class SpatialMemoryReport {

    /** 「近く」とみなす領域の範囲（チェビシェフ距離、領域単位）。5 領域 = 120 blocks。 */
    public static final int NEARBY_REGION_RANGE = 5;

    private SpatialMemoryReport() {
        throw new AssertionError("utility class");
    }

    /**
     * レポートの行を組み立てる。
     *
     * @param memory  空間記憶（変更しない）
     * @param current 個体がいま居る領域
     * @return 表示行
     */
    public static List<String> lines(SpatialMemory memory, SpatialRegion current) {
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(current, "current");
        List<String> lines = new ArrayList<>();

        lines.add("Current Region  " + describeLocation(current));
        RegionMemory here = memory.get(current);
        if (here == null) {
            lines.add("  (この場所での経験はまだありません)  Fear: 0.00");
        } else {
            lines.add(String.format(Locale.ROOT, "  Fear: %.2f  (raw fear %.2f / positive %.2f)",
                    here.effectiveFear(), here.fearValue(), here.positiveValue()));
            lines.add(String.format(Locale.ROOT, "  Experience Count: %d", here.visitCount()));
            lines.add("  " + dangerVerdict(here.effectiveFear()));
        }

        Map.Entry<SpatialRegion, RegionMemory> strongest = strongestNearbyNegative(memory, current);
        if (strongest == null) {
            lines.add(String.format(Locale.ROOT,
                    "Strongest Nearby Negative Region: なし（半径 %d blocks）",
                    NEARBY_REGION_RANGE * AiWolfParameters.SPATIAL_REGION_SIZE));
        } else {
            SpatialRegion region = strongest.getKey();
            RegionMemory value = strongest.getValue();
            lines.add("Strongest Nearby Negative Region");
            lines.add(String.format(Locale.ROOT, "  %s  Fear=%.2f  n=%d  (%s)",
                    describeLocation(region), value.effectiveFear(), value.visitCount(),
                    region.equals(current) ? "現在地" : distanceText(current, region)));
        }

        int dangerous = 0;
        for (RegionMemory value : memory.regions().values()) {
            if (value.effectiveFear() >= AiWolfParameters.DANGEROUS_REGION_THRESHOLD) {
                dangerous++;
            }
        }
        lines.add(String.format(Locale.ROOT, "Known regions: %d  dangerous (>= %.2f): %d",
                memory.size(), AiWolfParameters.DANGEROUS_REGION_THRESHOLD, dangerous));
        return lines;
    }

    /**
     * 近傍（同じディメンション・{@link #NEARBY_REGION_RANGE} 領域以内）で実効恐怖値が最大の領域。
     *
     * @param memory  空間記憶
     * @param current 現在の領域
     * @return 領域と記憶。恐怖値が正の領域がなければ {@code null}
     */
    public static Map.Entry<SpatialRegion, RegionMemory> strongestNearbyNegative(SpatialMemory memory,
                                                                               SpatialRegion current) {
        Map.Entry<SpatialRegion, RegionMemory> best = null;
        double bestFear = 0.0;
        for (Map.Entry<SpatialRegion, RegionMemory> entry : memory.regions().entrySet()) {
            SpatialRegion region = entry.getKey();
            if (!Objects.equals(region.dimension(), current.dimension())) {
                continue;
            }
            int distance = Math.max(Math.abs(region.regionX() - current.regionX()),
                    Math.max(Math.abs(region.regionY() - current.regionY()),
                            Math.abs(region.regionZ() - current.regionZ())));
            if (distance > NEARBY_REGION_RANGE) {
                continue;
            }
            double fear = entry.getValue().effectiveFear();
            if (fear > bestFear) {
                best = entry;
                bestFear = fear;
            }
        }
        return best;
    }

    /**
     * 領域の中心のブロック座標を返す（撮影時に同じ場所へ戻るため）。
     *
     * @param region 領域
     * @return {@code "x=108 y=36 z=108"} の形
     */
    public static String describeLocation(SpatialRegion region) {
        int size = AiWolfParameters.SPATIAL_REGION_SIZE;
        return String.format(Locale.ROOT, "x=%d y=%d z=%d",
                region.regionX() * size + size / 2,
                region.regionY() * size + size / 2,
                region.regionZ() * size + size / 2);
    }

    private static String dangerVerdict(double fear) {
        return fear >= AiWolfParameters.DANGEROUS_REGION_THRESHOLD
                ? String.format(Locale.ROOT, "危険な場所として記憶（AVOID_LOCATION が選択肢に入る: >= %.2f）",
                        AiWolfParameters.DANGEROUS_REGION_THRESHOLD)
                : String.format(Locale.ROOT, "危険とみなす閾値 %.2f 未満", AiWolfParameters.DANGEROUS_REGION_THRESHOLD);
    }

    private static String distanceText(SpatialRegion from, SpatialRegion to) {
        int size = AiWolfParameters.SPATIAL_REGION_SIZE;
        double dx = (to.regionX() - from.regionX()) * (double) size;
        double dy = (to.regionY() - from.regionY()) * (double) size;
        double dz = (to.regionZ() - from.regionZ()) * (double) size;
        return String.format(Locale.ROOT, "約 %.0f blocks 先", Math.sqrt(dx * dx + dy * dy + dz * dz));
    }
}
