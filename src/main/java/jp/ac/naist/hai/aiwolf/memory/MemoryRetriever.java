package jp.ac.naist.hai.aiwolf.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import jp.ac.naist.hai.aiwolf.core.AiWolfParameters;
import jp.ac.naist.hai.aiwolf.core.MathSupport;

/**
 * 現在状況に関連する記憶の Top-K 検索。
 *
 * <p>200 件すべてを行動計算へ入れるのは計算量の面でも解釈の面でも不適切なので、
 * 次のスコアで上位 K 件だけを取り出す。</p>
 *
 * <pre>
 * RetrievalScore = 0.25 * Recency + 0.35 * Salience + 0.40 * Relevance
 * Relevance      = 0.50 * eventMatch + 0.30 * regionMatch + 0.20 * actorMatch
 * </pre>
 *
 * <p><strong>LLM の埋め込みは使わない。</strong>研究の再現性を保つため、関連度は
 * 完全にルールベースで計算する。</p>
 */
public final class MemoryRetriever {

    private MemoryRetriever() {
        throw new AssertionError("utility class");
    }

    /**
     * 既定の K で検索する。
     *
     * @param store 記憶保管庫
     * @param query 問い合わせ条件
     * @return 検索スコア降順の上位 K 件
     */
    public static List<ScoredMemory> retrieve(MemoryStore store, MemoryQuery query) {
        return retrieve(store, query, AiWolfParameters.MEMORY_RETRIEVAL_TOP_K);
    }

    /**
     * K を明示して検索する。
     *
     * @param store 記憶保管庫
     * @param query 問い合わせ条件
     * @param topK  取り出す件数（正の値）
     * @return 検索スコア降順の上位 K 件
     */
    public static List<ScoredMemory> retrieve(MemoryStore store, MemoryQuery query, int topK) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(query, "query");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0 but was " + topK);
        }

        List<ScoredMemory> scored = new ArrayList<>(store.size());
        for (WolfMemory memory : store.memories()) {
            double recency = memory.recency(query.currentWorldTime(), AiWolfParameters.RECENCY_TAU_TICKS);
            double salience = memory.salience();
            double relevance = relevance(memory, query);
            double score = AiWolfParameters.RETRIEVAL_WEIGHT_RECENCY * recency
                    + AiWolfParameters.RETRIEVAL_WEIGHT_SALIENCE * salience
                    + AiWolfParameters.RETRIEVAL_WEIGHT_RELEVANCE * relevance;
            scored.add(new ScoredMemory(memory, recency, salience, relevance, score));
        }

        // 同点時の順序を決定論的にするため、スコア降順・記憶 ID 昇順で安定化する。
        scored.sort(Comparator.comparingDouble(ScoredMemory::score).reversed()
                .thenComparing(entry -> entry.memory().memoryId()));
        return List.copyOf(scored.subList(0, Math.min(topK, scored.size())));
    }

    /**
     * 記憶と現在状況の関連度を計算する。
     *
     * @param memory 記憶
     * @param query  問い合わせ条件
     * @return [0,1] の関連度
     */
    public static double relevance(WolfMemory memory, MemoryQuery query) {
        double eventMatch = query.currentEventType() != null && query.currentEventType() == memory.type()
                ? 1.0 : 0.0;
        double regionMatch = regionMatch(memory.region(), query.region());
        double actorMatch = query.actorId() != null && query.actorId().equals(memory.actorId()) ? 1.0 : 0.0;
        return MathSupport.clamp01(
                AiWolfParameters.RELEVANCE_WEIGHT_EVENT * eventMatch
                        + AiWolfParameters.RELEVANCE_WEIGHT_REGION * regionMatch
                        + AiWolfParameters.RELEVANCE_WEIGHT_ACTOR * actorMatch);
    }

    /**
     * 領域の一致度を返す。完全一致で 1.0、隣接領域なら割引値、それ以外は 0。
     *
     * @param memoryRegion  記憶の領域
     * @param currentRegion 現在の領域
     * @return [0,1] の一致度
     */
    private static double regionMatch(SpatialRegion memoryRegion, SpatialRegion currentRegion) {
        if (memoryRegion == null || currentRegion == null) {
            return 0.0;
        }
        if (memoryRegion.equals(currentRegion)) {
            return 1.0;
        }
        if (!memoryRegion.dimension().equals(currentRegion.dimension())) {
            return 0.0;
        }
        int distance = Math.max(Math.abs(memoryRegion.regionX() - currentRegion.regionX()),
                Math.max(Math.abs(memoryRegion.regionY() - currentRegion.regionY()),
                        Math.abs(memoryRegion.regionZ() - currentRegion.regionZ())));
        return distance <= 1 ? AiWolfParameters.ADJACENT_REGION_MATCH : 0.0;
    }
}
