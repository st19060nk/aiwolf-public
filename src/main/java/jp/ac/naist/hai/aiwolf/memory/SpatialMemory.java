package jp.ac.naist.hai.aiwolf.memory;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.event.WolfEvent;

/**
 * 1 個体分の空間記憶。領域ごとの恐怖・安心・訪問回数を保持する。
 *
 * <p>正確なブロック座標ではなく {@link SpatialRegion} 単位で保持するため、
 * 「同じ洞窟へ戻ってきた」という判定が安定して行える。</p>
 *
 * <p>保持数には上限があり、超過時は重要度の低い領域から削除する。
 * これにより長時間プレイでもメモリ使用量が線形に増えない。</p>
 *
 * <p>スレッド安全ではない。サーバスレッド上からのみ変更すること。</p>
 */
public final class SpatialMemory {

    private final Map<SpatialRegion, RegionMemory> regions = new LinkedHashMap<>();
    private final int maxRegions;

    /** 既定の容量で空間記憶を生成する。 */
    public SpatialMemory() {
        this(AiWolfParameters.MAX_SPATIAL_REGIONS);
    }

    /**
     * 容量を明示して空間記憶を生成する。
     *
     * @param maxRegions 保持する領域の最大数（正の値）
     */
    public SpatialMemory(int maxRegions) {
        if (maxRegions <= 0) {
            throw new IllegalArgumentException("maxRegions must be > 0 but was " + maxRegions);
        }
        this.maxRegions = maxRegions;
    }

    /**
     * 出来事を該当領域へ反映する。
     *
     * @param event 出来事
     */
    public void recordEvent(WolfEvent event) {
        Objects.requireNonNull(event, "event");
        RegionMemory memory = regions.computeIfAbsent(event.region(), key -> new RegionMemory());
        memory.recordOutcome(event.valence(), event.arousal());
        memory.recordVisit(event.worldTime());
        pruneIfNeeded();
    }

    /**
     * 領域への訪問を記録する。
     *
     * @param region    領域
     * @param worldTime 現在のワールド時刻
     */
    public void recordVisit(SpatialRegion region, long worldTime) {
        Objects.requireNonNull(region, "region");
        regions.computeIfAbsent(region, key -> new RegionMemory()).recordVisit(worldTime);
        pruneIfNeeded();
    }

    /**
     * 永続化から領域を復元する。剪定は行わない。
     *
     * @param region 領域
     * @param memory 場所記憶
     */
    public void restoreRegion(SpatialRegion region, RegionMemory memory) {
        regions.put(Objects.requireNonNull(region, "region"), Objects.requireNonNull(memory, "memory"));
    }

    /**
     * 領域の実効恐怖値を返す。未知の領域は 0（＝恐れる理由がない）。
     *
     * @param region 領域（{@code null} 可）
     * @return [0,1] の実効恐怖値
     */
    public double fearAt(SpatialRegion region) {
        if (region == null) {
            return 0.0;
        }
        RegionMemory memory = regions.get(region);
        return memory == null ? 0.0 : memory.effectiveFear();
    }

    /**
     * 領域の場所記憶を返す。
     *
     * @param region 領域
     * @return 場所記憶。未知なら {@code null}
     */
    public RegionMemory get(SpatialRegion region) {
        return regions.get(region);
    }

    /**
     * 保持している領域の不変ビューを返す（エクスポート・デバッグ用）。
     *
     * @return 領域 → 場所記憶
     */
    public Map<SpatialRegion, RegionMemory> regions() {
        return Collections.unmodifiableMap(regions);
    }

    /** @return 保持している領域数 */
    public int size() {
        return regions.size();
    }

    /**
     * 容量超過時に重要度の低い領域から削除する。
     *
     * <p>訪問回数が多い領域はわずかに残りやすくしている。日常的に通る場所の情報を
     * 一時的な出来事より優先して保持するためである。</p>
     */
    private void pruneIfNeeded() {
        while (regions.size() > maxRegions) {
            SpatialRegion weakest = regions.entrySet().stream()
                    .min(Comparator.comparingDouble(entry ->
                            entry.getValue().importance() + Math.min(0.1, entry.getValue().visitCount() * 0.001)))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (weakest == null) {
                return;
            }
            regions.remove(weakest);
        }
    }
}
