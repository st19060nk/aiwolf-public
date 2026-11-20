package jp.ac.naist.hai.aiwolf.memory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jp.ac.naist.hai.aiwolf.behavior.BehaviorType;
import jp.ac.naist.hai.aiwolf.core.MathSupport;

/**
 * 想起された記憶を {@link MemorySummary}（行動別の寄与）へ変換する。
 *
 * <pre>
 * MemoryEffect(b) = clip( sum_i RetrievalScore_i * MemoryImpact(type_i, b) * |valence_i| , -1, +1 )
 * </pre>
 *
 * <p>{@code |valence|} を掛けることで、感情的に薄い出来事の記憶が
 * 強い出来事の記憶と同じ重みで効いてしまうことを防いでいる。</p>
 *
 * <p>BehaviorScorer はこの計算結果だけを受け取るため、記憶構造の詳細から独立している。</p>
 */
public final class MemoryImpactCalculator {

    private MemoryImpactCalculator() {
        throw new AssertionError("utility class");
    }

    /**
     * 想起された記憶群から行動別の寄与を計算する。
     *
     * @param retrieved 検索済みの記憶（{@link MemoryRetriever} の出力）
     * @return 記憶要約
     */
    public static MemorySummary summarise(List<ScoredMemory> retrieved) {
        Objects.requireNonNull(retrieved, "retrieved");
        Map<BehaviorType, Double> contributions = new EnumMap<>(BehaviorType.class);
        List<String> memoryIds = new ArrayList<>(retrieved.size());

        for (ScoredMemory scored : retrieved) {
            WolfMemory memory = scored.memory();
            memoryIds.add(memory.memoryId());
            double strength = Math.abs(memory.valence());
            for (Map.Entry<BehaviorType, Double> entry
                    : MemoryImpactMatrix.impactsFor(memory.type()).entrySet()) {
                double contribution = scored.score() * entry.getValue() * strength;
                contributions.merge(entry.getKey(), contribution, Double::sum);
            }
        }

        contributions.replaceAll((behavior, value) -> MathSupport.clipSigned(value));
        return new MemorySummary(contributions, memoryIds);
    }
}
