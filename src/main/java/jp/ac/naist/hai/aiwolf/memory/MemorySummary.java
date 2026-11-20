package jp.ac.naist.hai.aiwolf.memory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.MathSupport;

/**
 * エピソード記憶の検索結果を、行動スコアへ加算できる形にまとめた要約。
 *
 * <p>BehaviorScorer は個々の {@code WolfMemory} を直接参照しない。検索（Top-K 抽出）と
 * 影響度の算出は記憶層の責務であり、スコアラはその結果だけを受け取る。
 * これによりスコアラは Minecraft からもデータ構造の詳細からも独立する。</p>
 *
 * <p>各行動への寄与は [-1, +1] にクリップされる。</p>
 */
public final class MemorySummary {

    private static final MemorySummary EMPTY =
            new MemorySummary(Collections.emptyMap(), Collections.emptyList());

    private final Map<BehaviorType, Double> behaviorContributions;
    private final List<String> relevantMemoryIds;

    /**
     * 要約を生成する。
     *
     * @param behaviorContributions 行動ごとの寄与（[-1,+1] へクリップされる）
     * @param relevantMemoryIds     根拠となった記憶 ID（研究ログの追跡用）
     */
    public MemorySummary(Map<BehaviorType, Double> behaviorContributions, List<String> relevantMemoryIds) {
        Objects.requireNonNull(behaviorContributions, "behaviorContributions");
        Objects.requireNonNull(relevantMemoryIds, "relevantMemoryIds");
        Map<BehaviorType, Double> clipped = new EnumMap<>(BehaviorType.class);
        for (Map.Entry<BehaviorType, Double> entry : behaviorContributions.entrySet()) {
            Double value = entry.getValue();
            if (entry.getKey() == null || value == null || !Double.isFinite(value)) {
                continue;
            }
            clipped.put(entry.getKey(), MathSupport.clipSigned(value));
        }
        this.behaviorContributions = Collections.unmodifiableMap(clipped);
        this.relevantMemoryIds = List.copyOf(relevantMemoryIds);
    }

    /**
     * 記憶の影響がない要約を返す（STATIC / STATE_ONLY 条件や記憶が空の場合）。
     *
     * @return 空の要約
     */
    public static MemorySummary empty() {
        return EMPTY;
    }

    /**
     * 指定行動への記憶由来の寄与を返す。
     *
     * @param behavior 行動
     * @return [-1,+1] の寄与。該当がなければ 0
     */
    public double contributionFor(BehaviorType behavior) {
        return behaviorContributions.getOrDefault(behavior, 0.0);
    }

    /**
     * 根拠となった記憶 ID を返す（研究ログの {@code relevantMemoryIds}）。
     *
     * @return 記憶 ID の不変リスト
     */
    public List<String> relevantMemoryIds() {
        return relevantMemoryIds;
    }

    /**
     * 行動ごとの寄与の不変ビューを返す。
     *
     * @return 寄与マップ
     */
    public Map<BehaviorType, Double> behaviorContributions() {
        return behaviorContributions;
    }

    /**
     * 記憶の影響が一切ないかを返す。
     *
     * @return 影響がなければ true
     */
    public boolean isEmpty() {
        return behaviorContributions.isEmpty();
    }

    @Override
    public String toString() {
        Map<String, Double> readable = new LinkedHashMap<>();
        for (BehaviorType behavior : BehaviorType.ALL) {
            double value = contributionFor(behavior);
            if (value != 0.0) {
                readable.put(behavior.id(), value);
            }
        }
        return "MemorySummary" + readable;
    }
}
